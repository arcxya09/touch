import io
from uuid import uuid4

import pytest
from PIL import Image
from sqlalchemy import select

from app.database import SessionLocal
from app.models import Attachment, Conversation, Message, MobileSession, User, now
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


@pytest.mark.parametrize("peer_state", ["legacy", "logged_out", "expired", "revoked"])
def test_recall_is_server_side_without_peer_session_or_activity(client, peer_state):
    ah, bh, _, peer, cid = friends(client)
    # 1.0.13/1.0.14 did not send a capability header. The sender may use the
    # original protocol, too; no preliminary /auth/me call is required.
    sender = {"Authorization": ah["Authorization"]}
    recipient = {"Authorization": bh["Authorization"]}
    original = send(client, sender, cid).json()
    if peer_state == "logged_out":
        assert client.post("/api/v1/auth/logout", headers=recipient).status_code == 200
    else:
        with SessionLocal() as db:
            row = db.scalar(select(MobileSession).where(MobileSession.user_id == peer["user"]["id"]))
            row.supports_recall = False
            if peer_state == "expired":
                row.refresh_expires = now() - 1
            elif peer_state == "revoked":
                db.get(User, row.user_id).session_epoch += 1
            db.commit()
    # The recipient does not make any request between becoming unavailable
    # and this mutation. Authorization depends only on the sending account.
    assert client.get("/api/v1/conversations", headers=sender).json()[0]["can_recall"]
    recall_path = f"/api/v1/conversations/{cid}/messages/{original['id']}/recall"
    assert client.post(recall_path, headers=sender).status_code == 200
    assert client.post(recall_path, headers=sender).status_code == 200
    with SessionLocal() as db:
        row = db.get(Message, original["id"])
        assert row.kind == "recalled" and row.text == "" and row.attachment_id is None
        conversation = db.get(Conversation, cid)
        assert conversation.a_clear == conversation.b_clear == 0
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=sender).json()["messages"] == []


@pytest.mark.parametrize("kind", ["text", "image", "file"])
def test_headerless_recipient_sync_removes_only_recalled_content_and_continues(client, kind):
    ah, bh, _, _, cid = friends(client)
    sender = {"Authorization": ah["Authorization"]}
    recipient = {"Authorization": bh["Authorization"]}
    before = send(client, sender, cid, text="keep earlier history").json()
    attachment = None
    if kind == "image":
        image = io.BytesIO()
        Image.new("RGB", (2, 2), "red").save(image, "PNG")
        file = ("private.png", image.getvalue(), "image/png")
    elif kind == "file":
        file = ("private.txt", b"private file", "text/plain")
    if kind != "text":
        attachment = client.post(f"/api/v1/files?kind={kind}", headers=sender, files={"file": file}).json()
    original = send(client, sender, cid, kind=kind, text="private text" if kind == "text" else "",
                    attachment_id=attachment["id"] if attachment else None).json()
    initial = client.get("/api/v1/sync", headers=recipient).json()
    cached = {event["payload"]["id"]: event["payload"] for event in initial["events"] if event["kind"] == "message"}
    assert set(cached) == {before["id"], original["id"]}
    assert client.post(f"/api/v1/conversations/{cid}/messages/{original['id']}/recall", headers=sender).status_code == 200
    after = send(client, sender, cid, text="keep later message").json()
    # Force pagination across recall and the subsequent message. The old
    # protocol must advance its cursor, never 409-loop or use prefix clears.
    cursor = initial["cursor"]
    observed = []
    while True:
        response = client.get(f"/api/v1/sync?cursor={cursor}&limit=1", headers=recipient)
        assert response.status_code == 200, response.text
        result = response.json()
        for event in result["events"]:
            observed.append(event)
            if event["kind"] == "message":
                message = event["payload"]
                if message["kind"] == "recalled":
                    assert message["id"] == original["id"]
                    assert message["text"] == "" and message["attachment"] is None and message["reply_to"] is None
                    cached.pop(message["id"], None)
                else:
                    cached[message["id"]] = message
        assert result["cursor"] > cursor
        cursor = result["cursor"]
        if not result["has_more"]:
            break
    assert [event["kind"] for event in observed] == ["message", "message"]
    assert set(cached) == {before["id"], after["id"]}
    history = client.get(f"/api/v1/conversations/{cid}/messages", headers=recipient).json()["messages"]
    assert [row["id"] for row in history] == [before["id"], after["id"]]
    # Replaying from zero also contains only wiped tombstones, never the
    # original text or attachment descriptor stored in a historical event.
    replay = client.get("/api/v1/sync", headers=recipient).json()["events"]
    placeholders = [event["payload"] for event in replay if event["payload"].get("id") == original["id"]]
    assert placeholders and all(row["kind"] == "recalled" and row["text"] == "" and row["attachment"] is None
                                for row in placeholders)
    if attachment:
        with SessionLocal() as db:
            assert db.get(Attachment, attachment["id"]) is None
        for headers in (sender, recipient):
            assert client.get(f"/api/v1/files/{attachment['id']}", headers=headers).status_code == 404
