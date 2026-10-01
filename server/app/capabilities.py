from sqlalchemy import select
from fastapi import HTTPException

from .models import MobileSession, User, now

RECALL = "recall-v1"


def supports_recall(request):
    return RECALL in {value.strip() for value in request.headers.get("X-Touch-Capabilities", "").split(",")}


def can_recall(db, conversation, declared):
    if not declared:
        return False
    supported = db.scalars(select(MobileSession.user_id).join(User, User.id == MobileSession.user_id).where(
        MobileSession.user_id.in_([conversation.a, conversation.b]), MobileSession.supports_recall.is_(True),
        MobileSession.epoch == User.session_epoch, MobileSession.refresh_expires > now(), User.active.is_(True))).all()
    return len(supported) == 2


def message_for_client(message, declared):
    # Never reinterpret a destructive state as ordinary content for an old app.
    if message and message["kind"] == "recalled" and not declared:
        raise HTTPException(409, "此会话包含新版消息状态，请更新 Touch 后继续同步")
    return message
