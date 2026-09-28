import hashlib
import io
import re
import uuid

from PIL import Image
from sqlalchemy import select

from app.database import SessionLocal
from app.models import MobileSession, User, now
from conftest import friends, login


def send(client, headers, cid, **overrides):
    body = {"client_id": str(uuid.uuid4()), "kind": "text", "text": "你好"} | overrides
    return client.post(f"/api/v1/conversations/{cid}/messages", headers=headers, json=body)


def test_retry_after_both_clear_cannot_resurrect_message(client):
    ah, bh, _, _, cid = friends(client)
    client_id = str(uuid.uuid4())
    assert send(client, ah, cid, client_id=client_id).status_code == 200
    for headers in (ah, bh):
        assert client.post(f"/api/v1/conversations/{cid}/clear", headers=headers).status_code == 200
    assert send(client, ah, cid, client_id=client_id).status_code == 409
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"] == []


def test_no_registration_or_anonymous_access(client):
    assert client.post("/api/v1/auth/register", json={}).status_code == 404
    assert client.get("/api/v1/conversations").status_code == 401
    assert client.get("/health").json()["status"] == "ok"


def test_account_case_and_exact_search(client):
    headers, _ = login(client, "ALICE")
    assert client.get("/api/v1/contacts/search?username=bo", headers=headers).status_code == 404
    assert client.get("/api/v1/contacts/search?username=bob", headers=headers).json()["username"] == "bob"


def test_new_login_revokes_old_and_refresh_rotates(client):
    old, first = login(client, "alice")
    new, second = login(client, "alice")
    assert client.get("/api/v1/auth/me", headers=old).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": first["refresh_token"]}).status_code == 401
    refreshed = client.post("/api/v1/auth/refresh", json={"refresh_token": second["refresh_token"]})
    assert refreshed.status_code == 200
    assert client.get("/api/v1/auth/me", headers=new).status_code == 401
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": second["refresh_token"]}).status_code == 401


def test_expired_access_can_refresh(client):
    headers, data = login(client, "alice")
    with SessionLocal() as db:
        session = db.scalar(select(MobileSession).where(MobileSession.user_id == data["user"]["id"]))
        session.access_expires = now() - 1
        db.commit()
    response = client.get("/api/v1/auth/me", headers=headers)
    assert response.headers["x-auth-reason"] == "expired"
    assert client.post("/api/v1/auth/refresh", json={"refresh_token": data["refresh_token"]}).status_code == 200


def test_initial_password_required(client):
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.username == "alice"))
        user.must_change_password = True
        db.commit()
    headers, data = login(client, "alice")
    assert data["user"]["must_change_password"]
    assert client.get("/api/v1/conversations", headers=headers).status_code == 403
    response = client.post("/api/v1/auth/password", headers=headers,
                           json={"current_password": "password123!", "new_password": "changed12345!"})
    assert response.status_code == 200
    assert client.get("/api/v1/conversations", headers=headers).status_code == 200


def test_friend_request_requires_recipient_acceptance(client):
    ah, ad = login(client, "alice")
    bh, bd = login(client, "bob")
    client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": bd["user"]["id"]})
    assert client.post("/api/v1/contacts/" + bd["user"]["id"], headers=ah, json={"action": "accept"}).status_code == 409
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    assert client.post("/api/v1/contacts/" + ad["user"]["id"], headers=bh, json={"action": "reject"}).status_code == 200


def test_messages_idempotency_pagination_and_unread(client):
    ah, bh, _, _, cid = friends(client)
    client_id = str(uuid.uuid4())
    first = send(client, ah, cid, client_id=client_id)
    assert first.status_code == 200
    assert send(client, ah, cid, client_id=client_id).json()["id"] == first.json()["id"]
    assert send(client, ah, cid, client_id=client_id, text="不同内容").status_code == 409
    second = send(client, ah, cid).json()
    page = client.get(f"/api/v1/conversations/{cid}/messages?limit=1", headers=bh).json()
    assert page["has_more"] and page["messages"][0]["seq"] == second["seq"]
    older = client.get(f"/api/v1/conversations/{cid}/messages?before={second['seq']}", headers=bh).json()
    assert len(older["messages"]) == 1
    assert client.get("/api/v1/conversations", headers=bh).json()[0]["unread"] == 2
    client.post(f"/api/v1/conversations/{cid}/read", headers=bh, json={"seq": second["seq"]})
    assert client.get("/api/v1/conversations", headers=bh).json()[0]["unread"] == 0


def test_clear_is_personal_and_sync_does_not_resurrect(client):
    ah, bh, _, _, cid = friends(client)
    send(client, ah, cid)
    client.post(f"/api/v1/conversations/{cid}/clear", headers=ah)
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=ah).json()["messages"] == []
    assert len(client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"]) == 1
    events = client.get("/api/v1/sync", headers=ah).json()["events"]
    assert not any(e["kind"] == "message" for e in events)
    client.post(f"/api/v1/conversations/{cid}/clear", headers=bh)
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"] == []
    message = send(client, ah, cid).json()
    assert message["seq"] == 2


def test_non_member_and_removed_contact_denied(client):
    ah, bh, _, bd, cid = friends(client)
    ch, _ = login(client, "charlie")
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=ch).status_code == 404
    assert send(client, ch, cid).status_code == 404
    client.post("/api/v1/contacts/" + bd["user"]["id"], headers=ah, json={"action": "remove"})
    assert send(client, ah, cid).status_code == 403
    assert send(client, bh, cid).status_code == 403


def test_attachment_authorization_and_integrity(client):
    ah, bh, _, _, cid = friends(client)
    ch, _ = login(client, "charlie")
    content = "你好，Touch".encode()
    response = client.post("/api/v1/files?kind=file", headers=ah, files={"file": ("../note.txt", content, "text/plain")})
    assert response.status_code == 200
    attachment = response.json()
    assert attachment["name"] == "note.txt" and attachment["sha256"] == hashlib.sha256(content).hexdigest()
    path = "/api/v1/files/" + attachment["id"]
    assert client.get(path, headers=bh).status_code == 404
    assert send(client, bh, cid, kind="file", text="", attachment_id=attachment["id"]).status_code == 400
    assert send(client, ah, cid, kind="file", text="", attachment_id=attachment["id"]).status_code == 200
    assert client.get(path, headers=bh).content == content
    assert client.get(path, headers=ch).status_code == 404
    client.post(f"/api/v1/conversations/{cid}/clear", headers=ah)
    assert client.get(path, headers=ah).status_code == 404
    assert client.get(path, headers=bh).status_code == 200


def test_image_content_verified(client):
    ah, _ = login(client, "alice")
    assert client.post("/api/v1/files?kind=image", headers=ah,
                       files={"file": ("evil.png", b"not an image", "image/png")}).status_code == 400
    data = io.BytesIO()
    Image.new("RGB", (2, 2)).save(data, "PNG")
    assert client.post("/api/v1/files?kind=image", headers=ah,
                       files={"file": ("valid.png", data.getvalue(), "image/png")}).status_code == 200


def test_sync_cursor_pages(client):
    ah, bh, _, _, cid = friends(client)
    for _ in range(3):
        send(client, ah, cid)
    cursor, sequences, messages = 0, [], []
    while True:
        page = client.get(f"/api/v1/sync?cursor={cursor}&limit=2", headers=bh).json()
        cursor = page["cursor"]
        sequences.extend(e["seq"] for e in page["events"])
        messages.extend(e for e in page["events"] if e["kind"] == "message")
        if not page["has_more"]:
            break
    assert sequences == sorted(set(sequences))
    assert len(messages) == 3
    assert client.get(f"/api/v1/sync?cursor={cursor}", headers=bh).json()["events"] == []


def test_websocket_authenticated_hint(client):
    ah, _, _, _, _ = friends(client)
    with client.websocket_connect("/ws", headers=ah) as websocket:
        assert websocket.receive_json()["type"] == "sync"


def admin_login(client):
    page = client.get("/admin/login")
    csrf = re.search(r'name="csrf" value="([^"]+)"', page.text).group(1)
    result = client.post("/admin/login", data={"username": "admin", "password": "password123!", "csrf": csrf})
    assert result.status_code == 200
    return re.search(r'name="csrf" value="([^"]+)"', result.text).group(1)


def test_admin_creation_reset_and_csrf(client):
    ah, data = login(client, "alice")
    csrf = admin_login(client)
    assert client.post("/admin/users", data={"username": "david", "display_name": "David",
                       "password": "temporary123", "csrf": "bad"}).status_code == 403
    response = client.post("/admin/users", data={"username": "david", "display_name": "David",
                           "password": "temporary123", "csrf": csrf})
    assert response.status_code == 200
    assert client.post("/admin/users/" + data["user"]["id"],
                       data={"action": "disable", "csrf": csrf}).status_code == 200
    assert client.get("/api/v1/auth/me", headers=ah).status_code == 401
    assert client.post("/api/v1/auth/login", json={"username": "alice", "password": "password123!"}).status_code == 401
