import asyncio
import contextlib
import logging
import shutil

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.exception_handlers import http_exception_handler, request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.datastructures import MutableHeaders
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from . import admin, api
from .config import settings
from .database import SessionLocal
from .maintenance import maintain
from .security import authenticate
from .uploads import UploadGuard
from .operations import record_maintenance

log = logging.getLogger("touch")


async def maintenance_loop():
    while True:
        try:
            await asyncio.to_thread(maintain)
            record_maintenance()
        except Exception as error:
            record_maintenance(error)
            log.error("Maintenance failed (%s); inspect database/storage health", type(error).__name__)
        await asyncio.sleep(3600)


@contextlib.asynccontextmanager
async def lifespan(app):
    task = asyncio.create_task(maintenance_loop())
    yield
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task


app = FastAPI(title="Touch API", version=settings.version, lifespan=lifespan)
app.include_router(api.router)
app.include_router(admin.router)


def admin_error(request, status, message):
    path = request.url.path
    return_url = path if path in ("/admin/login", "/admin/password") or path.endswith("/profile") else "/admin"
    return admin.templates.TemplateResponse(request=request, name="error.html", status_code=status,
        context={"status": status, "message": message, "return_url": return_url})


@app.exception_handler(StarletteHTTPException)
async def friendly_http_error(request, error):
    if request.url.path.startswith("/admin") and error.status_code >= 400:
        return admin_error(request, error.status_code, error.detail)
    return await http_exception_handler(request, error)


@app.exception_handler(RequestValidationError)
async def friendly_form_error(request, error):
    if request.url.path.startswith("/admin"):
        return admin_error(request, 422, "请检查必填项、长度和账号格式后重试。")
    return await request_validation_exception_handler(request, error)


class ResponseHeaders:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        async def secured_send(message):
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                headers["X-Content-Type-Options"] = "nosniff"
                headers["Referrer-Policy"] = "no-referrer"
                headers["Cache-Control"] = "no-store"
                if scope.get("path", "").startswith("/admin"):
                    # Preserve same-origin form POST Origin without leaking referrers.
                    headers["Referrer-Policy"] = "same-origin"
                    headers["Content-Security-Policy"] = (
                        "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; "
                        "form-action 'self'; base-uri 'none'")
            await send(message)
        await self.app(scope, receive, secured_send)


app.add_middleware(UploadGuard)
app.add_middleware(ResponseHeaders)


@app.get("/health")
def health():
    try:
        with SessionLocal() as db:
            db.execute(text("SELECT 1"))
    except SQLAlchemyError:
        raise HTTPException(503, "database_unavailable") from None
    free = shutil.disk_usage(settings.data_dir).free
    if free < settings.minimum_free_bytes:
        raise HTTPException(503, "storage_low")
    return {"status": "ok", "version": settings.version, "build": settings.build}


def socket_status(raw: str):
    with SessionLocal() as db:
        user, session = authenticate(db, raw)
        if user.must_change_password:
            raise HTTPException(403, "password_change_required")
        return user.sync_seq


@app.websocket("/ws")
async def websocket(websocket: WebSocket):
    header = websocket.headers.get("authorization", "")
    if not header.startswith("Bearer "):
        await websocket.close(4401)
        return
    raw = header[7:]
    try:
        await asyncio.to_thread(socket_status, raw)
    except HTTPException:
        await websocket.close(4401)
        return
    await websocket.accept()
    previous = -1
    try:
        while True:
            cursor = await asyncio.to_thread(socket_status, raw)
            if cursor != previous:
                await websocket.send_json({"type": "sync", "cursor": cursor})
                previous = cursor
            try:
                await asyncio.wait_for(websocket.receive_text(), timeout=2)
            except asyncio.TimeoutError:
                pass
    except HTTPException:
        await websocket.close(4401)
    except (WebSocketDisconnect, RuntimeError):
        pass
