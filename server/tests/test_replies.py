from uuid import uuid4
from io import BytesIO

import pytest
from PIL import Image

from sqlalchemy import select

from app.database import SessionLocal
from app.models import Conversation, Message
from conftest import friends, login


def send(client, headers, cid, **extra):
    return client.post(f"/api/v1/conversations/{cid}/messages", headers=headers,
                       json={"client_id": str(uuid4()), "kind": "text", "text": "reply", **extra})


@pytest.mark.parametrize("original_kind,reply_kind", [(a, b) for a in ("text", "image", "file")
                                                     for b in ("text", "image", "file")])
def test_all_reply_types(client, original_kind, reply_kind):
    ah, bh, _, _, cid = friends(client)

    def payload(headers, kind):
        if kind == "text":
            return {"kind": kind, "text": "private original"}
        image = BytesIO()
        Image.new("RGB", (2, 2)).save(image, format="PNG")
        attachment = client.post(f"/api/v1/files?kind={kind}", headers=headers,
                                 files={"file": ("test.png" if kind == "image" else "test.txt",
                                                  image.getvalue() if kind == "image" else b"private file")}).json()
        return {"kind": kind, "text": "", "attachment_id": attachment["id"]}

    original = send(client, ah, cid, **payload(ah, original_kind)).json()
    reply = send(client, bh, cid, reply_to_id=original["id"], **payload(bh, reply_kind))
    assert reply.status_code == 200
    assert reply.json()["reply_to"]["id"] == original["id"]
    assert set(reply.json()["reply_to"]) == {"id", "seq", "created_at"}


def test_reply_idempotency_and_no_snapshot(client):
    ah, bh, _, _, cid = friends(client)
    original = send(client, ah, cid, text="original secret").json()
    request = {"client_id": str(uuid4()), "reply_to_id": original["id"]}
    reply = send(client, bh, cid, **request)
    assert reply.status_code == 200
    assert send(client, bh, cid, **request).json()["id"] == reply.json()["id"]
    assert send(client, bh, cid, client_id=request["client_id"]).status_code == 409
    assert reply.json()["reply_to"] == {"id": original["id"], "seq": original["seq"],
                                        "created_at": original["created_at"]}
    assert "original secret" not in str(reply.json())
    chained = send(client, ah, cid, reply_to_id=reply.json()["id"]).json()
    assert "reply_to" not in chained["reply_to"]


def test_reply_visibility_is_per_viewer_and_cannot_resurrect(client):
    ah, bh, _, _, cid = friends(client)
    original = send(client, ah, cid, text="destroyed content").json()
    assert client.post(f"/api/v1/conversations/{cid}/clear", headers=ah).status_code == 200
    assert send(client, ah, cid, reply_to_id=original["id"]).status_code == 409
    reply = send(client, bh, cid, reply_to_id=original["id"]).json()
    for suffix in ("", "/context"):
        path = f"/api/v1/conversations/{cid}/messages/{original['id']}{suffix}"
        assert client.get(path, headers=ah).status_code == 404
        assert client.get(path, headers=bh).status_code == 200
        assert client.get(path, headers=bh, params={"after_time": original["created_at"]}).status_code == 404
    assert "destroyed content" not in str(client.get("/api/v1/sync", headers=ah).json())
    assert client.post(f"/api/v1/conversations/{cid}/clear", headers=bh).status_code == 200
    with SessionLocal() as db:
        assert db.get(Message, original["id"]) is None
        assert db.get(Message, reply["id"]) is not None
    assert client.get(f"/api/v1/conversations/{cid}/messages", headers=ah).json()["messages"][0]["id"] == reply["id"]


def test_reply_permissions_and_expired_target(client):
    ah, bh, ad, _, cid = friends(client)
    ch, cd = login(client, "charlie")
    client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": cd["user"]["id"]})
    client.post("/api/v1/contacts/" + ad["user"]["id"], headers=ch, json={"action": "accept"})
    other = client.get("/api/v1/conversations", headers=ch).json()[0]["id"]
    original = send(client, ah, cid).json()
    assert send(client, ah, other, reply_to_id=original["id"]).status_code == 409
    assert send(client, ch, cid, reply_to_id=original["id"]).status_code == 404
    assert send(client, bh, cid, reply_to_id=original["id"], after_time=original["created_at"]).status_code == 409
    assert client.get(f"/api/v1/conversations/{cid}/messages/{original['id']}", headers=ch).status_code == 404


def test_target_context_is_bounded_and_filtered_with_large_history(client):
    ah, _, ad, _, cid = friends(client)
    with SessionLocal() as db:
        for start in range(0, 50000, 1000):
            db.add_all([Message(id=str(uuid4()), conversation_id=cid, sender_id=ad["user"]["id"],
                                client_id=str(uuid4()), seq=i+1, kind="text", text="bulk", created_at=i+1)
                        for i in range(start, start+1000)])
        db.get(Conversation, cid).next_seq = 50000
        db.commit()
        target = db.scalar(select(Message).where(Message.conversation_id == cid, Message.seq == 10000)).id
    result = client.get(f"/api/v1/conversations/{cid}/messages/{target}/context", headers=ah).json()
    assert len(result["messages"]) == 50
    assert [m["seq"] for m in result["messages"]] == list(range(9976, 10026))
    filtered = client.get(f"/api/v1/conversations/{cid}/messages/{target}/context?after_time=9998", headers=ah).json()
    assert all(m["created_at"] > 9998 for m in filtered["messages"])
    latest = client.get(f"/api/v1/conversations/{cid}/messages", headers=ah).json()
    assert len(latest["messages"]) == 50 and latest["messages"][-1]["seq"] == 50000
