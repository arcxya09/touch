import asyncio
import io
from dataclasses import replace
from types import SimpleNamespace
from uuid import uuid4

import pytest
from PIL import Image
from sqlalchemy import select
from starlette.datastructures import UploadFile

from app import uploads
from app.database import SessionLocal
from app.main import app
from app.models import User
from app.security import current_user
from conftest import friends, login
from test_api import admin_login, send


def test_anonymous_admin_upload_does_not_parse_or_spool(client, monkeypatch):
    writes = []
    original = UploadFile.write

    async def observe(self, data):
        writes.append(len(data))
        return await original(self, data)

    monkeypatch.setattr(UploadFile, "write", observe)
    response = client.post("/admin/users/unknown/profile", files={"avatar": ("blob", b"x" * 2_000_000)},
                           data={"display_name": "name", "csrf": "bad"}, follow_redirects=False)
    assert response.status_code == 303 and writes == []
    response = client.post("/api/v1/files?kind=file", files={"file": ("blob", b"x" * 2_000_000)})
    assert response.status_code == 401 and writes == []


def test_upload_uses_same_case_insensitive_bearer_scheme_as_api(client):
    headers, _ = login(client, "alice")
    headers["Authorization"] = headers["Authorization"].replace("Bearer ", "bearer ", 1)
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 200
    response = client.post("/api/v1/files?kind=file", headers=headers, files={"file": ("small", b"abc")})
    assert response.status_code == 200


def test_upload_low_storage_and_declared_size_reject_before_spooling(client, monkeypatch):
    headers, _ = login(client, "alice")
    monkeypatch.setattr(UploadFile, "write", lambda *args: pytest.fail("rejected upload was parsed"))
    large = client.post("/api/v1/auth/avatar", headers={**headers, "Content-Length": str(7 * 1024 * 1024)},
                        content=b"small")
    assert large.status_code == 413
    monkeypatch.setattr(uploads.shutil, "disk_usage", lambda _: SimpleNamespace(free=1))
    full = client.post("/api/v1/files?kind=file", headers=headers, files={"file": ("small", b"abc")})
    assert full.status_code == 503 and full.headers["Retry-After"] == "60"


def test_streaming_upload_limit_without_content_length_and_slot_recovery(client, monkeypatch):
    headers, _ = login(client, "alice")
    monkeypatch.setattr(uploads, "settings", replace(uploads.settings, file_limit=100))
    boundary = "touch-boundary"

    def chunks():
        yield (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="a"\r\n\r\n').encode()
        yield b"x" * (uploads.MULTIPART_OVERHEAD + 101)
        yield f"\r\n--{boundary}--\r\n".encode()

    response = client.post("/api/v1/files?kind=file", headers={**headers,
                           "Content-Type": f"multipart/form-data; boundary={boundary}"}, content=chunks())
    assert response.status_code == 413
    assert client.post("/api/v1/files?kind=file", headers=headers, files={"file": ("small", b"abc")}).status_code == 200


def test_upload_concurrency_rejects_before_body_and_recovers(client):
    headers, _ = login(client, "alice")
    guard = app.middleware_stack
    while not isinstance(guard, uploads.UploadGuard):
        guard = guard.app
    acquired = [guard.slots.acquire(blocking=False) for _ in range(uploads.settings.upload_concurrency)]
    assert all(acquired)
    try:
        response = client.post("/api/v1/files?kind=file", headers=headers, files={"file": ("small", b"abc")})
        assert response.status_code == 429 and response.headers["Retry-After"] == "5"
    finally:
        for _ in acquired:
            guard.slots.release()
    assert client.post("/api/v1/files?kind=file", headers=headers, files={"file": ("small", b"abc")}).status_code == 200


def test_password_change_rotates_mobile_credentials_and_revokes_admin_cookie(client):
    headers, old = login(client, "admin")
    admin_login(client)
    response = client.post("/api/v1/auth/password", headers=headers,
                           json={"current_password": "password123!", "new_password": "changed12345!"})
    assert response.status_code == 200
    value = response.json()
    assert value["id"] == old["user"]["id"] == value["session"]["user"]["id"]
    assert client.get("/admin", follow_redirects=False).status_code == 303
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": old["refresh_token"]}).status_code == 401
    headers["Authorization"] = "Bearer " + value["session"]["access_token"]
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 200


def test_password_change_revalidates_session_after_account_lock(client):
    headers, login_data = login(client, "alice")
    with SessionLocal() as db:
        stale_user = db.get(User, login_data["user"]["id"])
        db.expunge(stale_user)
        fresh = db.get(User, stale_user.id)
        fresh.session_epoch += 1
        db.commit()
    app.dependency_overrides[current_user] = lambda: stale_user
    try:
        response = client.post("/api/v1/auth/password", headers=headers,
                               json={"current_password": "password123!", "new_password": "changed12345!"})
        assert response.status_code == 401
    finally:
        app.dependency_overrides.clear()


def test_recall_requires_both_clients_and_downgrade_gets_update_prompt(client):
    ah, bh, _, _, cid = friends(client)
    assert client.get("/api/v1/auth/me", headers=ah).json()["capabilities"] == ["recall-v1"]
    original = send(client, ah, cid).json()
    path = f"/api/v1/conversations/{cid}/messages/{original['id']}/recall"
    legacy = {"Authorization": bh["Authorization"]}
    client.get("/api/v1/auth/me", headers=legacy)
    assert not client.get("/api/v1/conversations", headers=ah).json()[0]["can_recall"]
    assert client.post(path, headers=ah).status_code == 409
    client.get("/api/v1/auth/me", headers=bh)
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["can_recall"]
    assert client.post(path, headers=ah).status_code == 200
    response = client.get("/api/v1/sync", headers=legacy)
    assert response.status_code == 409 and "请更新 Touch" in response.json()["detail"]
    assert original["text"] not in response.text
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=legacy).json()["messages"] == []


def test_existing_sessions_gain_recall_after_server_upgrade_without_relogin(client):
    from app.models import MobileSession

    ah, bh, _, _, cid = friends(client)
    original = send(client, ah, cid).json()
    # A migrated server defaults existing sessions to unknown capability.
    with SessionLocal() as db:
        sessions = db.scalars(select(MobileSession)).all()
        before = {row.id: (row.access_hash, row.refresh_hash, row.epoch) for row in sessions}
        for row in sessions:
            row.supports_recall = False
        db.commit()
    assert not client.get("/api/v1/conversations", headers=ah).json()[0]["can_recall"]
    assert client.get("/api/v1/auth/me", headers=ah).status_code == 200
    assert not client.get("/api/v1/conversations", headers=ah).json()[0]["can_recall"]
    assert client.get("/api/v1/auth/me", headers=bh).status_code == 200
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["can_recall"]
    with SessionLocal() as db:
        after = {row.id: (row.access_hash, row.refresh_hash, row.epoch)
                 for row in db.scalars(select(MobileSession)).all()}
    assert after == before
    path = f"/api/v1/conversations/{cid}/messages/{original['id']}"
    assert client.post(path + "/recall", headers=ah).status_code == 200
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"] == []


def test_legacy_admin_history_and_context_never_receive_fake_attachment(client):
    ah, bh, _, _, cid = friends(client, "alice", "admin")
    original = send(client, ah, cid).json()
    assert client.post(f"/api/v1/conversations/{cid}/messages/{original['id']}/recall", headers=ah).status_code == 200
    new = send(client, ah, cid).json()
    legacy = {"Authorization": bh["Authorization"]}
    for suffix in ("", f"/{new['id']}/context"):
        response = client.get(f"/api/v1/conversations/{cid}/messages{suffix}", headers=legacy)
        assert response.status_code == 409 and "请更新 Touch" in response.json()["detail"]


def test_recall_revalidates_original_token_after_account_lock(client):
    headers, _, account, _, conversation = friends(client)
    original = send(client, headers, conversation).json()
    with SessionLocal() as db:
        stale_user = db.get(User, account["user"]["id"])
        db.expunge(stale_user)
    # Models a request authenticated before waiting on a concurrent login's user lock.
    fresh_headers, _ = login(client, "alice")
    app.dependency_overrides[current_user] = lambda: stale_user
    try:
        response = client.post(f"/api/v1/conversations/{conversation}/messages/{original['id']}/recall", headers=headers)
        assert response.status_code == 401
    finally:
        app.dependency_overrides.clear()
    result = client.get(f"/api/v1/conversations/{conversation}/messages", headers=fresh_headers)
    assert result.json()["messages"][0]["text"] == original["text"]


@pytest.mark.parametrize("operation", ["profile", "avatar", "remove_avatar", "preferences", "logout",
    "request_contact", "remove_contact", "send", "read", "clear", "delete_conversation", "upload"])
def test_revoked_inflight_write_cannot_modify_new_session_state(client, monkeypatch, operation):
    from app.config import settings
    files_before = set((settings.data_dir / "files").iterdir())
    headers, _, account, peer, conversation = friends(client)
    message = send(client, headers, conversation).json()
    with SessionLocal() as db:
        stale_user = db.get(User, account["user"]["id"])
        db.expunge(stale_user)
        from sqlalchemy import select
        third = db.scalar(select(User).where(User.username == "charlie")).id
    fresh_headers, _ = login(client, "alice")
    app.dependency_overrides[current_user] = lambda: stale_user
    # Simulate the upload preflight having completed before the concurrent rotation.
    monkeypatch.setattr(uploads, "upload_identity", lambda *args: stale_user.id)
    image = io.BytesIO()
    Image.new("RGB", (2, 2), "red").save(image, "PNG")
    cases = {
        "profile": ("PATCH", "/auth/profile", {"json": {"display_name": "Changed by old session"}}),
        "avatar": ("POST", "/auth/avatar", {"files": {"file": ("avatar.png", image.getvalue(), "image/png")}}),
        "remove_avatar": ("DELETE", "/auth/avatar", {}),
        "preferences": ("PATCH", "/auth/preferences", {"json": {"read_receipts_enabled": True}}),
        "logout": ("POST", "/auth/logout", {}),
        "request_contact": ("POST", "/contacts/requests", {"json": {"user_id": third}}),
        "remove_contact": ("POST", "/contacts/" + peer["user"]["id"], {"json": {"action": "remove"}}),
        "send": ("POST", f"/conversations/{conversation}/messages",
                 {"json": {"client_id": str(uuid4()), "kind": "text", "text": "Old token must not send"}}),
        "read": ("POST", f"/conversations/{conversation}/read", {"json": {"seq": message["seq"]}}),
        "clear": ("POST", f"/conversations/{conversation}/clear", {}),
        "delete_conversation": ("DELETE", f"/conversations/{conversation}", {}),
        "upload": ("POST", "/files?kind=file", {"files": {"file": ("small", b"abc")}}),
    }
    method, path, kwargs = cases[operation]
    try:
        response = client.request(method, "/api/v1" + path, headers=headers, **kwargs)
        assert response.status_code == 401
    finally:
        app.dependency_overrides.clear()
    user = client.get("/api/v1/auth/me", headers=fresh_headers)
    assert user.status_code == 200 and user.json()["display_name"] == "Alice"
    assert user.json()["avatar_version"] is None
    assert len(client.get("/api/v1/contacts", headers=fresh_headers).json()) == 1
    state = client.get("/api/v1/conversations", headers=fresh_headers).json()[0]
    assert state["clear_seq"] == 0 and state["read_seq"] == 0
    history = client.get(f"/api/v1/conversations/{conversation}/messages", headers=fresh_headers).json()["messages"]
    assert [row["id"] for row in history] == [message["id"]]
    assert set((settings.data_dir / "files").iterdir()) == files_before


def test_health_version_and_admin_feedback_are_safe(client):
    from app.config import settings
    health = client.get("/health").json()
    assert health["version"] == settings.version and "build" in health
    csrf = admin_login(client)
    response = client.post("/admin/users", data={"csrf": csrf, "username": "alice", "display_name": "Retained",
                                                "password": "never-echo-this-password"})
    assert response.status_code == 409 and 'value="Retained"' in response.text
    assert "never-echo-this-password" not in response.text
    response = client.post("/admin/users", data={"csrf": csrf, "username": "new-user-invalid", "display_name": "X",
                                                "password": "never-echo-this-password"})
    assert response.status_code == 422 and "text/html" in response.headers["content-type"]
    assert "never-echo-this-password" not in response.text
    response = client.get("/admin?notice=%3Cscript%3Eunsafe%3C/script%3E")
    assert "<script>unsafe" not in response.text


def test_upload_disconnect_releases_guard_capacity(monkeypatch):
    from starlette.requests import ClientDisconnect
    monkeypatch.setattr(uploads, "upload_identity", lambda *args: "test-owner")
    monkeypatch.setattr(uploads, "require_storage", lambda *args: None)

    async def disconnected_app(scope, receive, send):
        raise ClientDisconnect()

    guard = uploads.UploadGuard(disconnected_app)
    scope = {"type": "http", "method": "POST", "path": "/api/v1/files", "headers": [], "query_string": b"kind=file"}
    with pytest.raises(ClientDisconnect):
        asyncio.run(guard(scope, None, None))
    assert guard.slots.acquire(blocking=False)
    assert guard.slots.acquire(blocking=False)
    guard.slots.release()
    guard.slots.release()
