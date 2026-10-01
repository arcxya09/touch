import os
import subprocess
import sys
from pathlib import Path

import pytest
from sqlalchemy import create_engine, select, text

from app.config import settings
from app.database import SessionLocal
from app.maintenance import maintain
from app.models import Attachment, Audit, MobileSession, User, now
from conftest import friends, login
from test_api import admin_login, send


def endpoint(user_id):
    return f"/admin/users/{user_id}/delete"


def test_delete_requires_admin_and_confirmation(client):
    headers, account = login(client, "alice")
    url = endpoint(account["user"]["id"])
    assert client.get(url, headers=headers, follow_redirects=False).status_code == 303
    assert client.post(url, headers=headers, data={"confirmation": "alice", "csrf": "bad"},
                       follow_redirects=False).status_code == 303
    csrf = admin_login(client)
    page = client.get(url)
    assert page.status_code == 200 and "输入完整账号名确认" in page.text
    assert client.get("/api/v1/auth/me", headers=headers).status_code == 200
    for token, name, origin, status in [("bad", "alice", "http://testserver", 403),
                                       (csrf, "alice", "https://other.example", 403),
                                       (csrf, "bob", "http://testserver", 400)]:
        result = client.post(url, headers={"Origin": origin}, data={"csrf": token, "confirmation": name})
        assert result.status_code == status
        assert client.get("/api/v1/auth/me", headers=headers).status_code == 200


def test_admin_account_cannot_be_deleted(client):
    csrf = admin_login(client)
    with SessionLocal() as db:
        admin_id = db.scalar(select(User.id).where(User.username == "admin"))
    assert endpoint(admin_id) not in client.get("/admin").text
    assert client.get(endpoint(admin_id)).status_code == 400
    assert client.post(endpoint(admin_id), data={"csrf": csrf, "confirmation": "admin"}).status_code == 400
    assert client.get("/admin").url.path == "/admin"


def test_delete_revokes_sessions_preserves_peer_history_and_isolates_reused_name(client):
    ah, bh, ad, _, cid = friends(client)
    ch, _ = login(client, "charlie")
    client.post("/api/v1/contacts/requests", headers=ch, json={"user_id": ad["user"]["id"]})
    sent = send(client, ah, cid, text="retain for peer").json()
    attached = client.post("/api/v1/files?kind=file", headers=ah,
                           files={"file": ("kept.txt", b"shared", "text/plain")}).json()
    orphan = client.post("/api/v1/files?kind=file", headers=ah,
                        files={"file": ("unused.txt", b"unused", "text/plain")}).json()
    assert send(client, ah, cid, kind="file", text="", attachment_id=attached["id"]).status_code == 200
    before = client.get("/api/v1/sync", headers=bh).json()["cursor"]
    csrf = admin_login(client)
    uid = ad["user"]["id"]
    url = endpoint(uid)
    response = client.post(url, headers={"Origin": "http://testserver"},
                           data={"csrf": csrf, "confirmation": "alice"})
    assert response.status_code == 200 and response.url.path == "/admin" and url not in response.text
    assert client.get("/api/v1/auth/me", headers=ah).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": ad["refresh_token"]}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"username": "alice", "password": "password123!"}).status_code == 401
    assert client.get("/api/v1/contacts/search?username=alice", headers=bh).status_code == 404
    assert client.get("/api/v1/contacts", headers=bh).json() == []
    assert client.get("/api/v1/contacts", headers=ch).json() == []
    assert client.post("/api/v1/contacts/requests", headers=bh, json={"user_id": uid}).status_code == 404
    assert send(client, bh, cid).status_code == 403
    assert client.get("/api/v1/files/" + attached["id"], headers=bh).content == b"shared"
    conversation = client.get("/api/v1/conversations", headers=bh).json()[0]
    assert conversation["peer"]["display_name"] == "已删除账号"
    assert conversation["peer"]["username"] == "deleted" and not conversation["can_send"]
    history = client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"]
    assert any(m["id"] == sent["id"] for m in history)
    events = client.get(f"/api/v1/sync?cursor={before}", headers=bh).json()["events"]
    assert any(event["kind"] == "contacts_changed" for event in events)
    for action in ("enable", "reset"):
        response = client.post(f"/admin/users/{uid}", data={"csrf": csrf, "action": action, "password": "newpassword123"})
        assert response.status_code == 400
    assert client.post(url, data={"csrf": csrf, "confirmation": "alice"}).status_code == 404
    with SessionLocal() as db:
        deleted = db.get(User, uid)
        assert deleted.deleted_at and not deleted.active and deleted.password_hash == ""
        assert deleted.username.startswith("~")
        assert db.scalar(select(MobileSession).where(MobileSession.user_id == uid)) is None
        assert db.get(Attachment, orphan["id"]) is None
        assert db.scalar(select(Audit).where(Audit.action == "delete_user", Audit.target_id == uid))
    assert client.post("/admin/users", data={"csrf": csrf, "username": "alice", "display_name": "New Alice",
                       "password": "password123!"}).status_code == 200
    new_headers, new = login(client, "alice")
    assert new["user"]["id"] != uid
    changed = client.post("/api/v1/auth/password", headers=new_headers,
                          json={"current_password": "password123!", "new_password": "changed12345!"})
    new_headers["Authorization"] = "Bearer " + changed.json()["session"]["access_token"]
    assert client.get("/api/v1/conversations", headers=new_headers).json() == []
    assert client.get("/api/v1/contacts", headers=new_headers).json() == []
    assert client.get("/api/v1/files/" + attached["id"], headers=new_headers).status_code == 404
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=new_headers).status_code == 404


def test_delete_clears_own_history_and_reclaims_unreferenced_files(client):
    ah, bh, ad, _, cid = friends(client)
    attachment = client.post("/api/v1/files?kind=file", headers=ah,
                             files={"file": ("old.txt", b"old", "text/plain")}).json()
    assert send(client, ah, cid, kind="file", text="", attachment_id=attachment["id"]).status_code == 200
    client.post(f"/api/v1/conversations/{cid}/clear", headers=bh)
    csrf = admin_login(client)
    assert client.post(endpoint(ad["user"]["id"]), data={"csrf": csrf, "confirmation": "alice"}).status_code == 200
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"] == []
    assert client.get("/api/v1/files/" + attachment["id"], headers=bh).status_code == 404
    path = settings.data_dir / "files" / attachment["id"]
    os.utime(path, (now() - 90000, now() - 90000))
    maintain()
    assert not path.exists()


@pytest.mark.parametrize("active", [True, False])
def test_empty_active_or_disabled_account_can_be_deleted(client, active):
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.username == "alice"))
        user.active = active
        user_id = user.id
        db.commit()
    csrf = admin_login(client)
    assert client.post(endpoint(user_id), data={"csrf": csrf, "confirmation": "alice"}).status_code == 200


def test_account_deletion_migration_preserves_existing_user(tmp_path):
    database_url = "sqlite:///" + str(tmp_path / "migration.db")
    env = os.environ | {"DATABASE_URL": database_url, "DATA_DIR": str(tmp_path / "files")}
    server = Path(__file__).resolve().parents[1]
    def upgrade(revision):
        subprocess.run([sys.executable, "-m", "alembic", "upgrade", revision], cwd=server, env=env,
                       capture_output=True, check=True)
    upgrade("b8210c9a9e30")
    engine = create_engine(database_url)
    with engine.begin() as db:
        db.execute(text("INSERT INTO users (id, username, display_name, password_hash, active, is_admin, "
                        "must_change_password, session_epoch, sync_seq, created_at) "
                        "VALUES ('preserved', 'existing', 'Existing', 'unchanged', 1, 0, 0, 5, 12, 123)"))
    upgrade("head")
    with engine.connect() as db:
        user = db.execute(text("SELECT * FROM users WHERE id='preserved'")).mappings().one()
        assert user["deleted_at"] is None and user["password_hash"] == "unchanged"
        assert user["session_epoch"] == 5 and user["sync_seq"] == 12
    engine.dispose()
