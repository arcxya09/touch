import io
from uuid import uuid4

from PIL import Image

from conftest import friends, login
from test_admin_forms import csrf_token


def png():
    output = io.BytesIO()
    Image.new("RGB", (800, 600), "red").save(output, "PNG")
    return output.getvalue()


def test_app_profile_avatar_authentication_validation_and_removal(client):
    ah, bh, ad, _, _ = friends(client)
    url = "/api/v1/auth/profile"
    assert client.patch(url, json={"display_name": "A"}).status_code == 401
    assert client.patch(url, headers=ah, json={"display_name": "   "}).status_code == 422
    assert client.patch(url, headers=ah, json={"display_name": "A", "is_admin": True}).status_code == 422
    result = client.patch(url, headers=ah, json={"display_name": " 新昵称 ", "bio": "个人简介"})
    assert result.status_code == 200
    assert result.json()["display_name"] == "新昵称"
    assert not result.json()["is_admin"]
    assert client.get("/api/v1/contacts", headers=bh).json()[0]["peer"]["bio"] == "个人简介"
    upload = client.post("/api/v1/auth/avatar", headers=ah, files={"file": ("a.png", png(), "image/png")})
    assert upload.status_code == 200
    version = upload.json()["avatar_version"]
    path = f"/api/v1/profiles/{ad['user']['id']}/avatar/{version}"
    assert client.get(path).status_code == 401
    avatar = client.get(path, headers=bh)
    assert avatar.status_code == 200
    assert "no-store" in avatar.headers["cache-control"]
    image = Image.open(io.BytesIO(avatar.content))
    assert image.format == "JPEG" and image.size == (512, 512)
    assert not image.getexif()
    assert client.get(path.replace(version, str(uuid4())), headers=bh).status_code == 404
    assert client.post("/api/v1/auth/avatar", headers=ah, files={"file": ("bad.png", b"<script/>")}).status_code == 400
    assert client.post("/api/v1/auth/avatar", headers=ah, files={"file": ("big", b"x" * (5*1024*1024+1))}).status_code == 413
    assert client.delete("/api/v1/auth/avatar", headers=ah).json()["avatar_version"] is None
    assert client.get(path, headers=bh).status_code == 404


def test_admin_web_profile_csrf_and_avatar(client):
    ah, ad = login(client, "alice")
    user_id = ad["user"]["id"]
    page = client.get("/admin/login")
    dashboard = client.post("/admin/login", data={"username": "admin", "password": "password123!",
                                                  "csrf": csrf_token(page)})
    assert "个人资料" in dashboard.text and "编辑资料" in dashboard.text
    own = client.get("/admin/profile")
    assert "@admin" in own.text
    path = f"/admin/users/{user_id}/profile"
    form = client.get(path)
    data = {"display_name": "<script>alert(1)</script>", "bio": '"简介"', "csrf": "bad"}
    assert client.post(path, data=data).status_code == 403
    data["csrf"] = csrf_token(form)
    result = client.post(path, data=data, files={"avatar": ("avatar.png", png(), "image/png")})
    assert result.status_code == 200
    assert "&lt;script&gt;" in result.text and "<script>alert" not in result.text
    user = client.get("/api/v1/auth/me", headers=ah).json()
    assert user["bio"] == '"简介"' and user["avatar_version"]
    assert not user["is_admin"]
    avatar_path = f"/admin/users/{user_id}/avatar"
    assert client.get(avatar_path).status_code == 200
    assert client.post(path, data={**data, "remove_avatar": "true"}).status_code == 200
    assert client.get(avatar_path).status_code == 404
    client.cookies.clear()
    assert client.get(avatar_path, follow_redirects=False).status_code == 303


def test_only_admin_with_enabled_setting_can_read_peer_position(client):
    ah, ad = login(client, "admin")
    bh, bd = login(client, "bob")
    assert client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": bd["user"]["id"]}).status_code == 200
    cid = client.get("/api/v1/conversations", headers=ah).json()[0]["id"]
    message = client.post(f"/api/v1/conversations/{cid}/messages", headers=ah,
                          json={"client_id": str(uuid4()), "kind": "text", "text": "receipt"})
    assert message.status_code == 200, message.text
    seq = message.json()["seq"]
    assert "peer_read_seq" not in client.get("/api/v1/conversations", headers=ah).json()[0]
    prefs = "/api/v1/auth/preferences"
    assert client.patch(prefs, headers=bh, json={"read_receipts_enabled": True}).status_code == 403
    assert client.patch(prefs, headers=ah, json={"read_receipts_enabled": True}).json()["read_receipts_enabled"]
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["peer_read_seq"] == 0
    cursor = client.get("/api/v1/sync", headers=ah).json()["cursor"]
    assert client.post(f"/api/v1/conversations/{cid}/read", headers=bh, json={"seq": seq}).status_code == 200
    assert client.get("/api/v1/conversations", headers=ah).json()[0]["peer_read_seq"] == seq
    events = client.get(f"/api/v1/sync?cursor={cursor}", headers=ah).json()["events"]
    assert any(e["kind"] == "read" for e in events)
    assert all("seq" not in e["payload"] for e in events if e["kind"] == "read")
    assert "peer_read_seq" not in client.get("/api/v1/conversations", headers=bh).json()[0]
    assert "read_receipts_enabled" not in client.get("/api/v1/contacts", headers=bh).json()[0]["peer"]
    assert not client.patch(prefs, headers=ah, json={"read_receipts_enabled": False}).json()["read_receipts_enabled"]
    assert "peer_read_seq" not in client.get("/api/v1/conversations", headers=ah).json()[0]
