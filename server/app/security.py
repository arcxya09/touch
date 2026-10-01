import hashlib
import secrets
import threading
import time
from collections import defaultdict, deque

from argon2 import PasswordHasher
from argon2.exceptions import VerificationError
from fastapi import Depends, HTTPException
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from sqlalchemy import select
from sqlalchemy.orm import Session

from .database import get_db
from .models import MobileSession, User, now

class BoundedPasswordHasher:
    """Bound Argon2 memory during bursts on the small single-process server."""
    def __init__(self):
        self.hasher = PasswordHasher(memory_cost=19456, time_cost=2, parallelism=1)
        self.slots = threading.BoundedSemaphore(2)

    def hash(self, password):
        with self.slots:
            return self.hasher.hash(password)

    def verify(self, encoded, password):
        with self.slots:
            return self.hasher.verify(encoded, password)


password_hasher = BoundedPasswordHasher()
dummy_hash = password_hasher.hash(secrets.token_urlsafe(24))
bearer = HTTPBearer(auto_error=False)


def digest(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def verify_password(encoded: str, password: str) -> bool:
    try:
        return password_hasher.verify(encoded, password)
    except (VerificationError, ValueError):
        return False


def token() -> str:
    return secrets.token_urlsafe(32)


class RateLimiter:
    """Bounded, process-local limiter; production intentionally runs one worker."""
    def __init__(self):
        self.entries = defaultdict(deque)
        self.lock = threading.Lock()

    def check(self, key: str, limit: int, seconds: int = 60):
        clock = time.monotonic()
        with self.lock:
            if len(self.entries) > 10000:
                stale = [k for k, v in self.entries.items() if not v or clock - v[-1] > 3600]
                for k in stale:
                    del self.entries[k]
                if len(self.entries) > 10000:
                    raise HTTPException(429, "服务繁忙，请稍后重试")
            values = self.entries[key]
            while values and values[0] <= clock - seconds:
                values.popleft()
            if len(values) >= limit:
                raise HTTPException(429, "操作过于频繁，请稍后重试", headers={"Retry-After": str(seconds)})
            values.append(clock)


limiter = RateLimiter()


def authenticate(db: Session, raw: str, allow_expired=False) -> tuple[User, MobileSession]:
    session = db.scalar(select(MobileSession).where(MobileSession.access_hash == digest(raw))
                        .execution_options(populate_existing=True))
    user = db.get(User, session.user_id, populate_existing=True) if session else None
    if (not session or not user or not user.active or session.epoch != user.session_epoch
            or session.refresh_expires <= now()):
        raise HTTPException(401, "登录已失效，请重新登录", headers={"X-Auth-Reason": "revoked"})
    if not allow_expired and session.access_expires <= now():
        raise HTTPException(401, "登录凭证已过期", headers={"X-Auth-Reason": "expired"})
    return user, session


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db: Session = Depends(get_db)):
    if not credentials:
        raise HTTPException(401, "请登录")
    return authenticate(db, credentials.credentials)[0]


def ready_user(user: User = Depends(current_user)):
    if user.must_change_password:
        raise HTTPException(403, "请先修改初始密码", headers={"X-Auth-Reason": "password_change_required"})
    return user
