"""Reject unauthenticated or unbounded uploads before multipart spooling."""
import asyncio
import re
import shutil
import tempfile
import threading

from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse, RedirectResponse

from .config import settings
from .database import SessionLocal
from .security import authenticate, limiter

MULTIPART_OVERHEAD = 64 * 1024


def require_storage(reserve=0):
    for directory in {str(settings.data_dir), tempfile.gettempdir()}:
        if shutil.disk_usage(directory).free < settings.minimum_free_bytes + reserve:
            raise HTTPException(503, "存储空间不足，请稍后重试", headers={"Retry-After": "60"})


def upload_identity(request, admin):
    with SessionLocal() as db:
        if admin:
            from .admin import admin_session, authenticated_admin
            _, user = admin_session(authenticated_admin(request, db))
        else:
            header = request.headers.get("authorization", "")
            scheme, _, raw = header.partition(" ")
            if scheme.lower() != "bearer" or not raw:
                raise HTTPException(401, "请登录")
            user, _ = authenticate(db, raw)
            if user.must_change_password:
                raise HTTPException(403, "请先修改初始密码")
        return user.id


class UploadGuard:
    def __init__(self, app):
        self.app = app
        self.slots = threading.BoundedSemaphore(settings.upload_concurrency)

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "").rstrip("/")
        admin = bool(re.fullmatch(r"/admin/users/[^/]+/profile", path))
        if scope["type"] != "http" or scope["method"] != "POST" or not (
                admin or path in ("/api/v1/files", "/api/v1/auth/avatar")):
            return await self.app(scope, receive, send)
        request = Request(scope)
        acquired = False
        try:
            owner = await asyncio.to_thread(upload_identity, request, admin)
            avatar = admin or path.endswith("/avatar")
            limit = (5 * 1024 * 1024 if avatar else settings.image_limit if request.query_params.get("kind") == "image"
                     else settings.file_limit) + MULTIPART_OVERHEAD
            length = request.headers.get("content-length")
            if length is not None:
                try:
                    size = int(length)
                except ValueError:
                    raise HTTPException(400, "上传大小无效") from None
                if size < 0:
                    raise HTTPException(400, "上传大小无效")
                if size > limit:
                    raise HTTPException(413, "文件超过大小限制")
            limiter.check(("avatar:" if avatar else "upload:") + owner, 10 if avatar else 30, 60)
            acquired = self.slots.acquire(blocking=False)
            if not acquired:
                raise HTTPException(429, "正在处理其他上传，请稍后重试", headers={"Retry-After": "5"})
            await asyncio.to_thread(require_storage, 2 * limit)
        except HTTPException as error:
            if acquired:
                self.slots.release()
            if error.status_code == 303:
                response = RedirectResponse(error.headers["Location"], error.status_code)
            elif admin:
                from .admin import templates
                response = templates.TemplateResponse(request=request, name="error.html", status_code=error.status_code,
                    context={"status": error.status_code, "message": error.detail, "return_url": path})
                response.headers.update(error.headers or {})
            else:
                response = JSONResponse({"detail": error.detail}, error.status_code, headers=error.headers)
            response.headers["Cache-Control"] = "no-store"
            return await response(scope, receive, send)
        except BaseException:
            if acquired:
                self.slots.release()
            raise
        received = 0

        async def bounded_receive():
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > limit:
                    raise HTTPException(413, "文件超过大小限制")
                await asyncio.to_thread(require_storage)
            return message

        try:
            await self.app(scope, bounded_receive, send)
        finally:
            self.slots.release()
