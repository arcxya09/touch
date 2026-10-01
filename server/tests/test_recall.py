from uuid import uuid4

import pytest

from app.database import SessionLocal
from app.models import Message, now
from conftest import friends, login


def send(client, headers, cid, **extra):
    return client.post(f"/api/v1/conversations/{cid}/messages", headers=headers,
                       json={"client_id": str(uuid4()), "kind": "text", "text": "secret", **extra})


def test_recall_permissions_without_time_limit(client):
    ah, bh, _, _, cid = friends(client)
    ch, _ = login(client, "charlie")
    message = send(client, ah, cid).json()
    path = f"/api/v1/conversations/{cid}/messages/{message['id']}/recall"
    assert client.post(path).status_code == 401
    assert client.post(path, headers=ch).status_code == 404
    assert client.post(path, headers=bh).status_code == 403
    with SessionLocal() as db:
        db.get(Message, message["id"]).created_at = now() - 121
        db.commit()
    assert client.post(path, headers=ah).status_code == 200
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=bh).json()["messages"] == []


def test_recall_sync_history_replies_and_retry(client):
    ah, bh, _, _, cid = friends(client)
    original = send(client, ah, cid).json()
    reply = send(client, bh, cid, text="reply", reply_to_id=original["id"]).json()
    cursor = client.get("/api/v1/sync", headers=bh).json()["cursor"]
    path = f"/api/v1/conversations/{cid}/messages/{original['id']}"
    assert client.post(path + "/recall", headers=ah).status_code == 200
    assert client.post(path + "/recall", headers=ah).status_code == 200
    for headers in (ah, bh):
        assert client.get(path, headers=headers).status_code == 404
        assert client.get(path + "/context", headers=headers).status_code == 404
        rows = client.get(f"/api/v1/conversations/{cid}/messages", headers=headers).json()["messages"]
        assert len(rows) == 1 and rows[0]["id"] == reply["id"]
    events = client.get(f"/api/v1/sync?cursor={cursor}", headers=bh).json()["events"]
    assert any(e["payload"].get("kind") == "recalled" for e in events)
    assert "secret" not in str(client.get("/api/v1/sync", headers=bh).json())
    assert send(client, ah, cid, client_id=original["client_id"]).status_code == 409
    assert send(client, bh, cid, reply_to_id=original["id"]).status_code == 409


def test_recalled_attachment_is_unavailable_to_both_parties(client):
    ah, bh, _, _, cid = friends(client)
    file = client.post("/api/v1/files?kind=file", headers=ah,
                       files={"file": ("secret.txt", b"attachment secret")}).json()
    original = send(client, ah, cid, kind="file", text="", attachment_id=file["id"]).json()
    assert client.post(f"/api/v1/conversations/{cid}/messages/{original['id']}/recall", headers=ah).status_code == 200
    for headers in (ah, bh):
        assert client.get(f"/api/v1/files/{file['id']}", headers=headers).status_code == 404
    assert client.get("/api/v1/conversations", headers=bh).json()[0]["unread"] == 0


@pytest.mark.parametrize("offset,expected", [(120, 200), (121, 200), (365 * 86400, 200)])
def test_old_messages_can_be_recalled(client, monkeypatch, offset, expected):
    ah, _, _, _, cid = friends(client)
    original = send(client, ah, cid).json()
    monkeypatch.setattr("app.api.now", lambda: original["created_at"] + offset)
    assert client.post(f"/api/v1/conversations/{cid}/messages/{original['id']}/recall", headers=ah).status_code == expected


def test_only_admin_sees_recall_placeholder(client):
    ah, adminh, _, _, cid = friends(client, "alice", "admin")
    original = send(client, ah, cid).json()
    assert client.post(f"/api/v1/conversations/{cid}/messages/{original['id']}/recall", headers=ah).status_code == 200
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=ah).json()["messages"] == []
    rows = client.get(f"/api/v1/conversations/{cid}/messages", headers=adminh).json()["messages"]
    assert len(rows) == 1 and rows[0]["kind"] == "recalled"
    assert rows[0]["text"] == "" and rows[0]["attachment"] is None
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["last_message"] is None
    assert client.get("/api/v1/conversations", headers=adminh).json()[0]["last_message"]["kind"] == "recalled"
    reply = send(client, adminh, cid, text="after").json()
    path = f"/api/v1/conversations/{cid}/messages/{reply['id']}/context"
    assert all(m["kind"] != "recalled" for m in client.get(path, headers=ah).json()["messages"])
    assert any(m["kind"] == "recalled" for m in client.get(path, headers=adminh).json()["messages"])
