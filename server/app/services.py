import json

from fastapi import HTTPException
from sqlalchemy import delete, func, or_, select, update
from sqlalchemy.orm import Session

from .models import Attachment, Contact, Conversation, Message, SyncEvent, User, now


def pair(a: str, b: str) -> str:
    return ":".join(sorted((a, b)))


def user_json(user: User, private=False):
    result = {"id": user.id, "username": "deleted" if user.deleted_at else user.username,
            "display_name": user.display_name, "bio": user.bio, "avatar_version": user.avatar_version,
            "must_change_password": user.must_change_password, "is_admin": user.is_admin}
    if private:
        result["read_receipts_enabled"] = bool(user.is_admin and user.read_receipts_enabled)
    return result


def attachment_json(item: Attachment):
    return {"id": item.id, "name": item.name, "mime": item.mime, "kind": item.kind,
            "size": item.size, "sha256": item.sha256}


def message_json(db: Session, message: Message):
    attachment = db.get(Attachment, message.attachment_id) if message.attachment_id else None
    return {"id": message.id, "conversation_id": message.conversation_id,
            "sender_id": message.sender_id, "client_id": message.client_id, "seq": message.seq,
            "kind": message.kind, "text": message.text, "created_at": message.created_at,
            "attachment": attachment_json(attachment) if attachment else None,
            "reply_to": ({"id": message.reply_to_id, "seq": message.reply_to_seq,
                          "created_at": message.reply_to_created_at} if message.reply_to_id else None)}


def emit(db: Session, users: list[str], kind: str, payload: dict):
    # Increment each user's counter under a row lock, in stable order. Unlike a DB
    # sequence, this cannot commit a later cursor ahead of an earlier transaction.
    for user_id in sorted(set(users)):
        seq = db.scalar(update(User).where(User.id == user_id)
                        .values(sync_seq=User.sync_seq + 1).returning(User.sync_seq))
        db.add(SyncEvent(user_id=user_id, seq=seq, kind=kind,
                         payload=json.dumps(payload, ensure_ascii=False)))


def conversation_for(db: Session, conversation_id: str, user_id: str, lock=False):
    query = select(Conversation).where(Conversation.id == conversation_id)
    if lock:
        query = query.with_for_update()
    conversation = db.scalar(query.execution_options(populate_existing=True))
    if not conversation or user_id not in (conversation.a, conversation.b):
        raise HTTPException(404, "会话不存在")
    return conversation


def visible_after(conversation: Conversation, user_id: str):
    return conversation.a_clear if user_id == conversation.a else conversation.b_clear


def conversation_json(db: Session, conversation: Conversation, user_id: str, after_time: int = 0):
    clear = visible_after(conversation, user_id)
    read = conversation.a_read if user_id == conversation.a else conversation.b_read
    peer_id = conversation.b if user_id == conversation.a else conversation.a
    peer = db.get(User, peer_id)
    contact = db.get(Contact, conversation.pair_key)
    viewer = db.get(User, user_id)
    last_query = select(Message).where(Message.conversation_id == conversation.id, Message.seq > clear,
                                       Message.created_at > after_time)
    if not viewer.is_admin:
        last_query = last_query.where(Message.kind != "recalled")
    last = db.scalar(last_query.order_by(Message.seq.desc()).limit(1))
    unread = db.scalar(select(func.count()).select_from(Message).where(
        Message.conversation_id == conversation.id, Message.seq > max(read, clear), Message.sender_id != user_id,
        Message.created_at > after_time, Message.kind != "recalled"))
    result = {"id": conversation.id, "peer": user_json(peer), "unread": unread,
            "clear_seq": clear, "read_seq": read,
            "can_send": bool(contact and contact.state == "accepted" and peer.active),
            "last_message": message_json(db, last) if last else None}
    if viewer.is_admin and viewer.read_receipts_enabled:
        result["peer_read_seq"] = conversation.b_read if user_id == conversation.a else conversation.a_read
    return result


def all_conversations(db: Session, user_id: str, after_time: int = 0):
    rows = db.scalars(select(Conversation).where(or_(Conversation.a == user_id, Conversation.b == user_id)))
    result = [conversation_json(db, row, user_id, after_time) for row in rows
              if not (row.a_hidden if row.a == user_id else row.b_hidden)]
    return sorted(result, key=lambda c: (c["last_message"] or {}).get("created_at", 0), reverse=True)


def garbage_collect(db: Session):
    expired = now() - 86400
    # Files are removed by the maintenance process only after the DB transaction
    # commits; orphan blobs left by interrupted requests are covered there too.
    db.execute(delete(Attachment).where(Attachment.created_at < expired,
               ~select(Message.id).where(Message.attachment_id == Attachment.id).exists()))
