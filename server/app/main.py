import asyncio
import contextlib
import logging
import shutil

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse
from sqlalchemy import text

from . import admin, api
from .config import settings
from .database import SessionLocal
from .maintenance import maintain
from .security import authenticate

log = logging.getLogger("touch")


async def maintenance_loop():
    while True:
        try:
            await asyncio.to_thread(maintain)
        except Exception:
            log.error("Maintenance failed; inspect database/storage health")
        await asyncio.sleep(3600)


@contextlib.asynccontextmanager
async def lifespan(app):
    task = asyncio.create_task(maintenance_loop())
    yield
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task


app = FastAPI(title="Touch API", version="1.0.0", lifespan=lifespan)
app.include_router(api.router)
app.include_router(admin.router)


@app.middleware("http")
async def response_headers(request, call_next):
    # Authenticate uploads before the multipart parser spools them to disk.
    if request.method == "POST" and request.url.path == "/api/v1/files":
        header = request.headers.get("authorization", "")
        if not header.startswith("Bearer "):
            return JSONResponse({"detail": "请登录"}, 401)
        try:
            await asyncio.to_thread(socket_status, header[7:])
        except HTTPException as error:
            return JSONResponse({"detail": error.detail}, error.status_code, headers=error.headers)
    response = await call_next(request)
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Referrer-Policy"] = "no-referrer"
    response.headers["Cache-Control"] = "no-store"
    if request.url.path.startswith("/admin"):
        # HTML form POSTs under no-referrer send Origin: null, failing CSRF checks.
        # Preserve same-origin form submissions without leaking referrers to other sites.
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["Content-Security-Policy"] = (
            "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'; form-action 'self'; base-uri 'none'")
    return response


@app.get("/health")
def health():
    with SessionLocal() as db:
        db.execute(text("SELECT 1"))
    free = shutil.disk_usage(settings.data_dir).free
    if free < 256 * 1024 * 1024:
        raise HTTPException(503, "storage_low")
    return {"status": "ok", "version": "1.0.0"}


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
