import uuid

from conftest import friends, login
from test_api import send


def test_delete_is_personal_persistent_and_new_messages_restore_only_new_history(client):
    ah, bh, _, _, cid = friends(client)
    request_id = str(uuid.uuid4())
    old = send(client, ah, cid, client_id=request_id).json()
    url = f"/api/v1/conversations/{cid}"
    outsider, _ = login(client, "charlie")
    assert client.delete(url, headers=outsider).status_code == 404
    assert client.delete(url).status_code == 401
    assert client.delete(url, headers=ah).json()["clear_seq"] == old["seq"]
    assert client.delete(url, headers=ah).status_code == 200
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    assert client.get(url + "/messages", headers=ah).json()["messages"] == []
    assert len(client.get(url + "/messages", headers=bh).json()["messages"]) == 1
    assert len(client.get("/api/v1/contacts", headers=ah).json()) == 1
    assert send(client, ah, cid, client_id=request_id).status_code == 409
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    events = client.get("/api/v1/sync", headers=ah).json()["events"]
    assert not any(e["kind"] == "message" for e in events)
    assert any(e["kind"] == "clear" and e["payload"].get("deleted") for e in events)
    ah, _ = login(client, "alice")
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    # Merely opening from contacts must not restore a deleted list entry.
    client.get(url + "/messages", headers=ah)
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    new = send(client, bh, cid, text="new").json()
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["unread"] == 1
    assert [m["id"] for m in client.get(url + "/messages", headers=ah).json()["messages"]] == [new["id"]]
    assert len(client.get(url + "/messages", headers=bh).json()["messages"]) == 2


def test_empty_and_both_deleted_conversations_stay_hidden_until_new_send(client):
    ah, bh, _, _, cid = friends(client)
    url = f"/api/v1/conversations/{cid}"
    for h in (ah, bh):
        assert client.delete(url, headers=h).status_code == 200
        assert client.get("/api/v1/conversations", headers=h).json() == []
    assert send(client, ah, cid).status_code == 200
    for h in (ah, bh):
        assert len(client.get("/api/v1/conversations", headers=h).json()) == 1
