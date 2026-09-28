import re

import pytest


def csrf_token(page):
    return re.search(r'name="csrf" value="([^"]+)"', page.text).group(1)


def test_same_origin_admin_forms(client):
    page = client.get("/admin/login")
    # Browsers send Origin: null for HTML POSTs with no-referrer, even same-origin.
    assert page.headers["Referrer-Policy"] == "same-origin"
    assert page.headers["Cache-Control"] == "no-store"
    response = client.post("/admin/login", headers={"Origin": "http://testserver"},
                           data={"username": "admin", "password": "password123!", "csrf": csrf_token(page)})
    assert response.status_code == 200
    assert response.url.path == "/admin"
    assert response.headers["Referrer-Policy"] == "same-origin"
    logout = client.post("/admin/logout", headers={"Origin": "http://testserver"},
                         data={"csrf": csrf_token(response)})
    assert logout.url.path == "/admin/login"
    assert "touch_admin" not in client.cookies


@pytest.mark.parametrize("origin", ["null", "https://other.example"])
def test_admin_login_rejects_untrusted_origin(client, origin):
    page = client.get("/admin/login")
    response = client.post("/admin/login", headers={"Origin": origin},
                           data={"username": "admin", "password": "password123!", "csrf": csrf_token(page)})
    assert response.status_code == 403
    assert "touch_admin" not in client.cookies


@pytest.mark.parametrize("token", ["missing", "bad", "错误令牌"])
def test_admin_login_rejects_invalid_token(client, token):
    if token != "missing":
        client.get("/admin/login")
    response = client.post("/admin/login", headers={"Origin": "http://testserver"},
                           data={"username": "admin", "password": "password123!", "csrf": token})
    assert response.status_code == 403
    assert "touch_admin" not in client.cookies
