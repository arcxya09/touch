"""Password changes share one locked, session-revoking transaction."""
from fastapi import HTTPException
from sqlalchemy import delete

from .models import AdminSession, MobileSession
from .security import password_hasher, verify_password


def replace_password(db, user, current, new):
    if not verify_password(user.password_hash, current):
        raise HTTPException(400, "当前密码不正确。")
    if current == new:
        raise HTTPException(400, "新密码不能与当前密码相同。")
    user.password_hash = password_hasher.hash(new)
    user.must_change_password = False
    user.session_epoch += 1
    db.execute(delete(MobileSession).where(MobileSession.user_id == user.id))
    db.execute(delete(AdminSession).where(AdminSession.user_id == user.id))
    db.flush()
