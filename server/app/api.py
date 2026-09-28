import hashlib
import json
import os
import re
from pathlib import Path
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, File, HTTPException, Query, Request, UploadFile
from fastapi.responses import FileResponse
from PIL import Image, UnidentifiedImageError
from pydantic import BaseModel, Field
from sqlalchemy import delete, or_, select
from sqlalchemy.orm import Session

from .config import settings
from .database import get_db
from .models import Attachment, Contact, Conversation, Message, MobileSession, SendReceipt, SyncEvent, User, now, uid
from .security import current_user, digest, dummy_hash, limiter, password_hasher, ready_user, token, verify_password
from .services import (all_conversations, attachment_json, conversation_for, emit, message_json, pair,
                       user_json, visible_after)

router = APIRouter(prefix="/api/v1")


class Login(BaseModel):
    username: str = Field(min_length=1, max_length=32)
    password: str = Field(min_length=1, max_length=128)


class Refresh(BaseModel):
    refresh_token: str = Field(min_length=20, max_length=128)


class Password(BaseModel):
    current_password: str = Field(min_length=1, max_length=128)
    new_password: str = Field(min_length=10, max_length=128)


class VerifyPassword(BaseModel):
    password: str = Field(min_length=1, max_length=128)


class ContactRequest(BaseModel):
    user_id: UUID


class ContactAction(BaseModel):
    action: Literal["accept", "reject", "remove"]


class SendMessage(BaseModel):
    client_id: UUID
    kind: Literal["text", "image", "file"]
    text: str = Field(default="", max_length=10000)
    attachment_id: UUID | None = None


class ReadPosition(BaseModel):
    seq: int = Field(ge=0)


def rotate_session(user: User, session: MobileSession):
    access, refresh = token(), token()
    session.access_hash, session.refresh_hash = digest(access), digest(refresh)
    session.access_expires = now() + settings.access_seconds
    session.refresh_expires = now() + settings.refresh_seconds
    session.epoch = user.session_epoch
    return {"access_token": access, "refresh_token": refresh,
            "expires_in": settings.access_seconds, "user": user_json(user)}


@router.post("/auth/login")
def login(body: Login, request: Request, db: Session = Depends(get_db)):
    name = body.username.strip().lower()
    limiter.check("login-ip:" + request.client.host, 20, 300)
    limiter.check("login-user:" + name, 10, 300)
    user = db.scalar(select(User).where(User.username == name).with_for_update())
    valid = verify_password(user.password_hash if user else dummy_hash, body.password)
    if not user or not valid or not user.active:
        raise HTTPException(401, "账号或密码错误，或账号已停用")
    session = db.scalar(select(MobileSession).where(MobileSession.user_id == user.id))
    if not session:
        session = MobileSession(user_id=user.id)
        db.add(session)
    user.session_epoch += 1
    result = rotate_session(user, session)
    db.commit()
    return result


@router.post("/auth/refresh")
def refresh(body: Refresh, db: Session = Depends(get_db)):
    # Always lock user before session, matching login/reset lock order.
    old = db.scalar(select(MobileSession).where(MobileSession.refresh_hash == digest(body.refresh_token)))
    if not old:
        raise HTTPException(401, "登录已失效")
    user = db.scalar(select(User).where(User.id == old.user_id).with_for_update())
    old = db.scalar(select(MobileSession).where(MobileSession.id == old.id)
                    .execution_options(populate_existing=True))
    if (not old or not user or old.refresh_hash != digest(body.refresh_token) or old.refresh_expires <= now()
            or not user.active or old.epoch != user.session_epoch):
        raise HTTPException(401, "登录已失效")
    result = rotate_session(user, old)
    db.commit()
    return result


@router.get("/auth/me")
def me(user: User = Depends(current_user)):
    return user_json(user)


@router.post("/auth/verify-password")
def verify(body: VerifyPassword, user: User = Depends(current_user)):
    limiter.check("verify:" + user.id, 5, 60)
    if not verify_password(user.password_hash, body.password):
        raise HTTPException(400, "密码错误")
    return {"ok": True}


@router.post("/auth/password")
def change_password(body: Password, user: User = Depends(current_user), db: Session = Depends(get_db)):
    limiter.check("password:" + user.id, 5, 60)
    if not verify_password(user.password_hash, body.current_password):
        raise HTTPException(400, "当前密码错误")
    if body.current_password == body.new_password:
        raise HTTPException(400, "新密码不能与当前密码相同")
    user.password_hash = password_hasher.hash(body.new_password)
    user.must_change_password = False
    db.commit()
    return user_json(user)


@router.post("/auth/logout")
def logout(user: User = Depends(current_user), db: Session = Depends(get_db)):
    db.execute(delete(MobileSession).where(MobileSession.user_id == user.id))
    db.commit()
    return {"ok": True}


@router.get("/contacts/search")
def search(username: str = Query(min_length=1, max_length=32), user: User = Depends(ready_user),
           db: Session = Depends(get_db)):
    limiter.check("search:" + user.id, 15, 60)
    result = db.scalar(select(User).where(User.username == username.strip().lower(), User.active.is_(True),
                                         User.id != user.id))
    if not result:
        raise HTTPException(404, "未找到该账号")
    return user_json(result)


@router.get("/contacts")
def contacts(user: User = Depends(ready_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(Contact).where(or_(Contact.a == user.id, Contact.b == user.id),
                                             Contact.state.in_(["accepted", "pending"])))
    result = []
    for row in rows:
        peer = db.get(User, row.b if row.a == user.id else row.a)
        conversation = db.scalar(select(Conversation).where(Conversation.pair_key == row.pair_key))
        result.append({"id": row.pair_key, "peer": user_json(peer), "state": row.state,
                       "incoming": row.requester != user.id,
                       "conversation_id": conversation.id if conversation else None})
    return result


@router.post("/contacts/requests")
def request_contact(body: ContactRequest, user: User = Depends(ready_user), db: Session = Depends(get_db)):
    limiter.check("requests:" + user.id, 10, 60)
    peer = db.get(User, str(body.user_id))
    if not peer or not peer.active or peer.id == user.id:
        raise HTTPException(404, "账号不存在")
    # Lock both users before updating contact state; also serializes reciprocal requests.
    db.scalars(select(User).where(User.id.in_([user.id, peer.id])).order_by(User.id).with_for_update()
               .execution_options(populate_existing=True)).all()
    if not user.active:
        raise HTTPException(401, "登录已失效")
    if not peer.active:
        raise HTTPException(404, "账号不存在")
    key = pair(user.id, peer.id)
    contact = db.get(Contact, key)
    if contact and (contact.state == "accepted" or (contact.state == "pending" and not user.is_admin)):
        return {"ok": True, "state": contact.state}
    if not contact:
        a, b = sorted([user.id, peer.id])
        contact = Contact(pair_key=key, a=a, b=b, requester=user.id)
        db.add(contact)
    contact.state = "accepted" if user.is_admin else "pending"
    contact.requester, contact.updated_at = user.id, now()
    if contact.state == "accepted" and not db.scalar(select(Conversation).where(Conversation.pair_key == key)):
        db.add(Conversation(pair_key=key, a=contact.a, b=contact.b))
    emit(db, [user.id, peer.id], "contacts_changed", {})
    db.commit()
    return {"ok": True, "state": contact.state}


@router.post("/contacts/{peer_id}")
def change_contact(peer_id: UUID, body: ContactAction, user: User = Depends(ready_user),
                   db: Session = Depends(get_db)):
    key = pair(user.id, str(peer_id))
    db.scalars(select(User).where(User.id.in_([user.id, str(peer_id)])).order_by(User.id).with_for_update()
               .execution_options(populate_existing=True)).all()
    if not user.active:
        raise HTTPException(401, "登录已失效")
    contact = db.get(Contact, key)
    if not contact:
        raise HTTPException(404, "联系人不存在")
    if body.action in ("accept", "reject"):
        if contact.state != "pending" or contact.requester == user.id:
            raise HTTPException(409, "该申请不能执行此操作")
        contact.state = "accepted" if body.action == "accept" else "rejected"
        if body.action == "accept" and not db.scalar(select(Conversation).where(Conversation.pair_key == key)):
            db.add(Conversation(pair_key=key, a=contact.a, b=contact.b))
    else:
        contact.state = "removed"
    contact.updated_at = now()
    emit(db, [contact.a, contact.b], "contacts_changed", {})
    db.commit()
    return {"ok": True}


@router.get("/conversations")
def conversations(user: User = Depends(ready_user), db: Session = Depends(get_db)):
    return all_conversations(db, user.id)


@router.get("/conversations/{conversation_id}/messages")
def history(conversation_id: UUID, before: int | None = Query(default=None, ge=1),
            limit: int = Query(default=50, ge=1, le=100), user: User = Depends(ready_user),
            db: Session = Depends(get_db)):
    conversation = conversation_for(db, str(conversation_id), user.id)
    query = select(Message).where(Message.conversation_id == conversation.id,
                                   Message.seq > visible_after(conversation, user.id))
    if before is not None:
        query = query.where(Message.seq < before)
    messages = db.scalars(query.order_by(Message.seq.desc()).limit(limit + 1)).all()
    return {"messages": [message_json(db, m) for m in reversed(messages[:limit])],
            "has_more": len(messages) > limit}


@router.post("/conversations/{conversation_id}/messages")
def send(conversation_id: UUID, body: SendMessage, user: User = Depends(ready_user),
         db: Session = Depends(get_db)):
    limiter.check("send:" + user.id, 120, 60)
    # User locks precede conversation locks in every write to avoid deadlocks.
    conversation = conversation_for(db, str(conversation_id), user.id)
    db.scalars(select(User).where(User.id.in_([conversation.a, conversation.b]))
               .order_by(User.id).with_for_update().execution_options(populate_existing=True)).all()
    if not user.active:
        raise HTTPException(401, "登录已失效")
    conversation = conversation_for(db, conversation.id, user.id, lock=True)
    previous = db.scalar(select(Message).where(Message.sender_id == user.id, Message.client_id == str(body.client_id)))
    if previous:
        if (previous.conversation_id != conversation.id or previous.kind != body.kind or previous.text != body.text
                or previous.attachment_id != (str(body.attachment_id) if body.attachment_id else None)):
            raise HTTPException(409, "请求标识已被其他内容使用")
        return message_json(db, previous)
    if db.get(SendReceipt, (user.id, str(body.client_id))):
        raise HTTPException(409, "该发送请求已完成，消息已清理，请移除本地待发送项")
    contact = db.get(Contact, conversation.pair_key)
    peer = db.get(User, conversation.b if user.id == conversation.a else conversation.a)
    if not contact or contact.state != "accepted" or not peer.active:
        raise HTTPException(403, "双方尚未建立有效联系人关系")
    if body.kind == "text":
        if not body.text.strip() or body.attachment_id:
            raise HTTPException(400, "文字消息不能为空或附带文件")
    else:
        attachment = db.get(Attachment, str(body.attachment_id)) if body.attachment_id else None
        if not attachment or attachment.owner_id != user.id or attachment.kind != body.kind:
            raise HTTPException(400, "附件不可用")
        if db.scalar(select(Message.id).where(Message.attachment_id == attachment.id)):
            raise HTTPException(409, "附件已关联消息")
    conversation.next_seq += 1
    message = Message(conversation_id=conversation.id, sender_id=user.id, client_id=str(body.client_id),
                      seq=conversation.next_seq, kind=body.kind, text=body.text,
                      attachment_id=str(body.attachment_id) if body.attachment_id else None)
    db.add(message)
    db.add(SendReceipt(sender_id=user.id, client_id=str(body.client_id)))
    db.flush()
    emit(db, [conversation.a, conversation.b], "message", {"message_id": message.id})
    result = message_json(db, message)
    db.commit()
    return result


@router.post("/conversations/{conversation_id}/read")
def read(conversation_id: UUID, body: ReadPosition, user: User = Depends(ready_user), db: Session = Depends(get_db)):
    db.scalar(select(User).where(User.id == user.id).with_for_update())
    conversation = conversation_for(db, str(conversation_id), user.id, lock=True)
    field = "a_read" if user.id == conversation.a else "b_read"
    new = max(getattr(conversation, field), min(body.seq, conversation.next_seq))
    if new != getattr(conversation, field):
        setattr(conversation, field, new)
        emit(db, [user.id], "read", {"conversation_id": conversation.id, "seq": new})
    db.commit()
    return {"ok": True}


@router.post("/conversations/{conversation_id}/clear")
def clear(conversation_id: UUID, user: User = Depends(ready_user), db: Session = Depends(get_db)):
    db.scalar(select(User).where(User.id == user.id).with_for_update())
    conversation = conversation_for(db, str(conversation_id), user.id, lock=True)
    field = "a_clear" if user.id == conversation.a else "b_clear"
    setattr(conversation, field, conversation.next_seq)
    clear_to = min(conversation.a_clear, conversation.b_clear)
    db.execute(delete(Message).where(Message.conversation_id == conversation.id, Message.seq <= clear_to))
    emit(db, [user.id], "clear", {"conversation_id": conversation.id, "seq": conversation.next_seq})
    db.commit()
    return {"ok": True, "clear_seq": conversation.next_seq}


@router.get("/sync")
def sync(cursor: int = Query(default=0, ge=0), limit: int = Query(default=100, ge=1, le=500),
         user: User = Depends(ready_user), db: Session = Depends(get_db)):
    rows = db.scalars(select(SyncEvent).where(SyncEvent.user_id == user.id, SyncEvent.seq > cursor)
                      .order_by(SyncEvent.seq).limit(limit + 1)).all()
    events = []
    for row in rows[:limit]:
        payload, kind = json.loads(row.payload), row.kind
        if kind == "message":
            message = db.get(Message, payload["message_id"])
            if message:
                conversation = conversation_for(db, message.conversation_id, user.id)
                payload = message_json(db, message) if message.seq > visible_after(conversation, user.id) else None
            else:
                payload = None
            if payload is None:
                kind, payload = "noop", {}
        events.append({"seq": row.seq, "kind": kind, "payload": payload})
    return {"events": events, "cursor": events[-1]["seq"] if events else cursor, "has_more": len(rows) > limit}


@router.post("/files")
def upload(kind: Literal["image", "file"] = Query(), file: UploadFile = File(),
           user: User = Depends(ready_user), db: Session = Depends(get_db)):
    limiter.check("upload:" + user.id, 30, 60)
    attachment_id = uid()
    path = settings.data_dir / "files" / attachment_id
    size, checksum = 0, hashlib.sha256()
    limit = settings.image_limit if kind == "image" else settings.file_limit
    try:
        with path.open("xb") as destination:
            while chunk := file.file.read(1024 * 1024):
                size += len(chunk)
                if size > limit:
                    raise HTTPException(413, "文件超过大小限制")
                checksum.update(chunk)
                destination.write(chunk)
        if size == 0:
            raise HTTPException(400, "不支持空文件")
        mime = file.content_type or "application/octet-stream"
        if kind == "image":
            try:
                with Image.open(path) as img:
                    if img.width * img.height > 40_000_000:
                        raise HTTPException(400, "图片分辨率过大")
                    mime = Image.MIME.get(img.format, "application/octet-stream")
                    img.verify()
            except (UnidentifiedImageError, OSError, Image.DecompressionBombError):
                raise HTTPException(400, "图片损坏或格式不支持") from None
        name = Path((file.filename or "文件").replace("\\", "/")).name
        name = re.sub(r"[\x00-\x1f\x7f]", "", name)[:200] or "文件"
        item = Attachment(id=attachment_id, owner_id=user.id, name=name, mime=mime[:160], kind=kind,
                          size=size, sha256=checksum.hexdigest())
        db.scalar(select(User).where(User.id == user.id).with_for_update()
                  .execution_options(populate_existing=True))
        if not user.active:
            raise HTTPException(401, "登录已失效")
        db.add(item)
        db.commit()
        return attachment_json(item)
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    finally:
        file.file.close()


@router.get("/files/{attachment_id}")
def download(attachment_id: UUID, user: User = Depends(ready_user), db: Session = Depends(get_db)):
    item = db.get(Attachment, str(attachment_id))
    if not item:
        raise HTTPException(404, "附件不存在")
    messages = db.scalars(select(Message).where(Message.attachment_id == item.id)).all()
    permitted = not messages and item.owner_id == user.id
    for message in messages:
        conversation = db.get(Conversation, message.conversation_id)
        if user.id in (conversation.a, conversation.b) and message.seq > visible_after(conversation, user.id):
            permitted = True
    if not permitted:
        raise HTTPException(404, "附件不存在")
    path = settings.data_dir / "files" / item.id
    if not os.path.isfile(path):
        raise HTTPException(404, "附件已不可用")
    return FileResponse(path, media_type=item.mime, filename=item.name,
                        headers={"Cache-Control": "private, no-store", "X-Content-Type-Options": "nosniff"})
