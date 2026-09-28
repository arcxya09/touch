import time
import uuid

from sqlalchemy import BigInteger, Boolean, ForeignKey, Index, Integer, String, Text, UniqueConstraint
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


def uid() -> str:
    return str(uuid.uuid4())


def now() -> int:
    return int(time.time())


class Base(DeclarativeBase):
    pass


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    username: Mapped[str] = mapped_column(String(32), unique=True)
    display_name: Mapped[str] = mapped_column(String(64))
    password_hash: Mapped[str] = mapped_column(Text)
    active: Mapped[bool] = mapped_column(Boolean, default=True)
    is_admin: Mapped[bool] = mapped_column(Boolean, default=False)
    must_change_password: Mapped[bool] = mapped_column(Boolean, default=True)
    session_epoch: Mapped[int] = mapped_column(Integer, default=0)
    sync_seq: Mapped[int] = mapped_column(BigInteger, default=0)
    created_at: Mapped[int] = mapped_column(BigInteger, default=now)


class MobileSession(Base):
    __tablename__ = "mobile_sessions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), unique=True)
    access_hash: Mapped[str] = mapped_column(String(64), unique=True)
    refresh_hash: Mapped[str] = mapped_column(String(64), unique=True)
    access_expires: Mapped[int] = mapped_column(BigInteger)
    refresh_expires: Mapped[int] = mapped_column(BigInteger)
    epoch: Mapped[int] = mapped_column(Integer)


class AdminSession(Base):
    __tablename__ = "admin_sessions"
    token_hash: Mapped[str] = mapped_column(String(64), primary_key=True)
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    csrf: Mapped[str] = mapped_column(String(64))
    expires: Mapped[int] = mapped_column(BigInteger)
    epoch: Mapped[int] = mapped_column(Integer)


class Contact(Base):
    __tablename__ = "contacts"
    pair_key: Mapped[str] = mapped_column(String(73), primary_key=True)
    a: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    b: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    requester: Mapped[str] = mapped_column(ForeignKey("users.id"))
    state: Mapped[str] = mapped_column(String(16), default="pending")
    updated_at: Mapped[int] = mapped_column(BigInteger, default=now)


class Conversation(Base):
    __tablename__ = "conversations"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    pair_key: Mapped[str] = mapped_column(String(73), unique=True)
    a: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    b: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    next_seq: Mapped[int] = mapped_column(BigInteger, default=0)
    a_read: Mapped[int] = mapped_column(BigInteger, default=0)
    b_read: Mapped[int] = mapped_column(BigInteger, default=0)
    a_clear: Mapped[int] = mapped_column(BigInteger, default=0)
    b_clear: Mapped[int] = mapped_column(BigInteger, default=0)


class Attachment(Base):
    __tablename__ = "attachments"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner_id: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    name: Mapped[str] = mapped_column(String(200))
    mime: Mapped[str] = mapped_column(String(160))
    kind: Mapped[str] = mapped_column(String(8))
    size: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String(64))
    created_at: Mapped[int] = mapped_column(BigInteger, default=now)


class Message(Base):
    __tablename__ = "messages"
    __table_args__ = (
        UniqueConstraint("sender_id", "client_id"),
        UniqueConstraint("conversation_id", "seq"),
        Index("ix_message_history", "conversation_id", "seq"),
    )
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    conversation_id: Mapped[str] = mapped_column(ForeignKey("conversations.id"))
    sender_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    client_id: Mapped[str] = mapped_column(String(36))
    seq: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(String(8))
    text: Mapped[str] = mapped_column(Text, default="")
    attachment_id: Mapped[str | None] = mapped_column(ForeignKey("attachments.id"), nullable=True)
    created_at: Mapped[int] = mapped_column(BigInteger, default=now)


class SendReceipt(Base):
    """Content-free tombstone: a cleared message must never be recreated by a retry."""
    __tablename__ = "send_receipts"
    sender_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    client_id: Mapped[str] = mapped_column(String(36), primary_key=True)


class SyncEvent(Base):
    __tablename__ = "sync_events"
    user_id: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    seq: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    kind: Mapped[str] = mapped_column(String(32))
    payload: Mapped[str] = mapped_column(Text)
    created_at: Mapped[int] = mapped_column(BigInteger, default=now)


class Audit(Base):
    __tablename__ = "audits"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    actor_id: Mapped[str] = mapped_column(ForeignKey("users.id"))
    action: Mapped[str] = mapped_column(String(32))
    target_id: Mapped[str] = mapped_column(String(36))
    created_at: Mapped[int] = mapped_column(BigInteger, default=now)
