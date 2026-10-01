from concurrent.futures import ThreadPoolExecutor
import threading

import pytest

from app.database import engine
from app import passwords
from conftest import login
from test_api import admin_login


@pytest.mark.skipif(engine.dialect.name != "postgresql", reason="Row-lock ordering requires PostgreSQL")
def test_admin_reset_serializes_with_mobile_password_change(client, monkeypatch):
    csrf = admin_login(client)
    headers, user = login(client, "alice")
    verified, proceed = threading.Event(), threading.Event()
    original = passwords.verify_password

    def paused_verify(encoded, password):
        result = original(encoded, password)
        verified.set()
        assert proceed.wait(10)
        return result

    monkeypatch.setattr(passwords, "verify_password", paused_verify)
    with ThreadPoolExecutor(max_workers=2) as pool:
        change = pool.submit(client.post, "/api/v1/auth/password", headers=headers,
                             json={"current_password": "password123!", "new_password": "changed12345!"})
        assert verified.wait(10)
        reset = pool.submit(client.post, "/admin/users/" + user["user"]["id"],
                            data={"csrf": csrf, "action": "reset", "password": "reset123456!"})
        proceed.set()
        assert change.result(10).status_code == 200
        assert reset.result(10).status_code == 200
    assert client.post("/api/v1/auth/login", json={"username": "alice", "password": "changed12345!"}).status_code == 401
    assert client.post("/api/v1/auth/login", json={"username": "alice", "password": "reset123456!"}).status_code == 200

