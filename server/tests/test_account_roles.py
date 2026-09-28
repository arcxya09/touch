import pytest
from sqlalchemy import select

from app.database import SessionLocal
from app.models import Audit, User
from conftest import login
from test_admin_forms import csrf_token
from test_api import admin_login, send


@pytest.mark.parametrize("role", [None, "user", "admin"])
def test_create_account_role(client, role):
    csrf = admin_login(client)
    data = {"csrf": csrf, "username": "new_account", "display_name": "New", "password": "temporary123"}
    if role:
        data["role"] = role
    assert client.post("/admin/users", data=data).status_code == 200
    with SessionLocal() as db:
        user = db.scalar(select(User).where(User.username == "new_account"))
        assert user.is_admin == (role == "admin") and user.must_change_password
        action = "create_admin" if user.is_admin else "create_user"
        assert db.scalar(select(Audit).where(Audit.target_id == user.id, Audit.action == action))


def test_user_cannot_grant_admin_and_unknown_role_rejected(client):
    headers, _ = login(client, "alice")
    data = {"csrf": "fake", "username": "new_account", "display_name": "New", "password": "temporary123", "role": "admin"}
    assert client.post("/admin/users", headers=headers, data=data, follow_redirects=False).status_code == 303
    page = client.get("/admin/login")
    assert client.post("/admin/login", data={"username": "alice", "password": "password123!",
                       "csrf": csrf_token(page)}).status_code == 401
    data["csrf"] = admin_login(client)
    data["role"] = "superuser"
    assert client.post("/admin/users", data=data).status_code == 422


def test_new_admin_must_change_password_before_management(client):
    csrf = admin_login(client)
    client.post("/admin/users", data={"csrf": csrf, "username": "operator", "display_name": "Operator",
                "password": "temporary123", "role": "admin"})
    client.post("/admin/logout", data={"csrf": csrf})
    page = client.get("/admin/login")
    result = client.post("/admin/login", data={"username": "operator", "password": "temporary123", "csrf": csrf_token(page)})
    assert result.url.path == "/admin/password"
    assert client.get("/admin").url.path == "/admin/password"
    csrf = csrf_token(result)
    result = client.post("/admin/users", data={"csrf": csrf, "username": "blocked", "display_name": "Blocked",
                         "password": "temporary123", "role": "admin"})
    assert result.url.path == "/admin/password"
    old_cookie = client.cookies.get("touch_admin")
    result = client.post("/admin/password", data={"csrf": csrf, "current_password": "temporary123",
                         "new_password": "changed12345!", "confirmation": "mismatch12345!"})
    assert result.status_code == 400
    result = client.post("/admin/password", data={"csrf": csrf, "current_password": "temporary123",
                         "new_password": "changed12345!", "confirmation": "changed12345!"})
    assert result.status_code == 200 and result.url.path == "/admin"
    assert client.cookies.get("touch_admin") != old_cookie
    assert client.post("/api/v1/auth/login", json={"username": "operator", "password": "temporary123"}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"username": "operator", "password": "changed12345!"}).status_code == 200


@pytest.mark.parametrize("pending_first", [False, True])
def test_admin_adds_friend_without_confirmation(client, pending_first):
    ah, ad = login(client, "admin")
    bh, bd = login(client, "bob")
    assert client.get("/api/v1/contacts/search?username=admin", headers=bh).status_code == 200
    if pending_first:
        result = client.post("/api/v1/contacts/requests", headers=bh, json={"user_id": ad["user"]["id"], "is_admin": True})
        assert result.json()["state"] == "pending"
        assert client.get("/api/v1/conversations", headers=bh).json() == []
    result = client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": bd["user"]["id"]})
    assert result.json()["state"] == "accepted"
    cid = client.get("/api/v1/conversations", headers=ah).json()[0]["id"]
    assert client.get("/api/v1/conversations", headers=bh).json()[0]["id"] == cid
    assert send(client, ah, cid).status_code == 200
    assert send(client, bh, cid).status_code == 200
    assert client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": bd["user"]["id"]}).json()["state"] == "accepted"
    assert len(client.get("/api/v1/conversations", headers=ah).json()) == 1


def test_normal_friend_request_still_needs_confirmation(client):
    ah, _ = login(client, "alice")
    bh, bd = login(client, "bob")
    response = client.post("/api/v1/contacts/requests", headers=ah,
                           json={"user_id": bd["user"]["id"], "role": "admin", "is_admin": True})
    assert response.json()["state"] == "pending"
    assert client.get("/api/v1/conversations", headers=ah).json() == []
    assert client.get("/api/v1/contacts", headers=bh).json()[0]["state"] == "pending"
