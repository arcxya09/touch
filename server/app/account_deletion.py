from base64 import urlsafe_b64encode
from uuid import UUID

from sqlalchemy import delete, or_, select

from .models import AdminSession, Attachment, Contact, Conversation, Message, MobileSession, SyncEvent, User, now
from .services import emit


def lock_accounts(db):
    # Rare admin operation in a <=100-user service. Lock in the same stable order
    # as contact/message writes, including peers added just before deletion.
    return {u.id: u for u in db.scalars(select(User).order_by(User.id).with_for_update()
                                       .execution_options(populate_existing=True))}


def delete_account(db, user):
    """Caller holds account locks; all changes commit with the admin audit record."""
    peers = set()
    contacts = db.scalars(select(Contact).where(or_(Contact.a == user.id, Contact.b == user.id))).all()
    for contact in contacts:
        peers.add(contact.b if contact.a == user.id else contact.a)
        db.delete(contact)

    conversations = db.scalars(select(Conversation).where(
        or_(Conversation.a == user.id, Conversation.b == user.id)).order_by(Conversation.id).with_for_update()).all()
    for conversation in conversations:
        peers.add(conversation.b if conversation.a == user.id else conversation.a)
        field = "a_clear" if conversation.a == user.id else "b_clear"
        setattr(conversation, field, conversation.next_seq)
        db.execute(delete(Message).where(Message.conversation_id == conversation.id,
                                          Message.seq <= min(conversation.a_clear, conversation.b_clear)))

    user.active = False
    user.deleted_at = now()
    user.session_epoch += 1
    user.password_hash = ""
    user.must_change_password = False
    # '~' cannot occur in a registered username; the UUID encoding is unique and
    # fits the existing 32-character column. A reused name gets a new identity.
    user.username = "~" + urlsafe_b64encode(UUID(user.id).bytes).decode().rstrip("=")
    user.display_name = "已删除账号"
    user.bio, user.avatar, user.avatar_version = "", None, None
    user.read_receipts_enabled = False
    for model in (MobileSession, AdminSession, SyncEvent):
        db.execute(delete(model).where(model.user_id == user.id))
    db.execute(delete(Attachment).where(Attachment.owner_id == user.id,
        ~select(Message.id).where(Message.attachment_id == Attachment.id).exists()))
    # File removal remains after commit in maintenance; peers keep their copies.
    emit(db, [peer_id for peer_id in peers if db.get(User, peer_id).deleted_at is None], "contacts_changed", {})
