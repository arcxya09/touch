import os
import tempfile
from pathlib import Path

import pytest

test_root = Path(tempfile.mkdtemp(prefix="touch-tests-"))
os.environ["DATABASE_URL"] = os.getenv("TEST_DATABASE_URL", "sqlite:///" + str(test_root / "test.db"))
os.environ["DATA_DIR"] = str(test_root)
os.environ["SECURE_COOKIES"] = "false"
os.environ["PUBLIC_BASE_URL"] = "http://testserver"

from fastapi.testclient import TestClient  # noqa: E402
from app.database import engine, SessionLocal  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Base, User  # noqa: E402
from app.security import limiter, password_hasher  # noqa: E402


@pytest.fixture
def client():
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    limiter.entries.clear()
    with SessionLocal() as db:
        for name in ("alice", "bob", "charlie", "admin"):
            db.add(User(username=name, display_name=name.title(), password_hash=password_hasher.hash("password123!"),
                        must_change_password=False, is_admin=name == "admin"))
        db.commit()
    with TestClient(app) as instance:
        yield instance


def login(client, name):
    response = client.post("/api/v1/auth/login", json={"username": name, "password": "password123!"})
    assert response.status_code == 200, response.text
    data = response.json()
    return {"Authorization": "Bearer " + data["access_token"]}, data


def friends(client, a="alice", b="bob"):
    ah, ad = login(client, a)
    bh, bd = login(client, b)
    assert client.post("/api/v1/contacts/requests", headers=ah, json={"user_id": bd["user"]["id"]}).status_code == 200
    assert client.post("/api/v1/contacts/" + ad["user"]["id"], headers=bh, json={"action": "accept"}).status_code == 200
    conversation = client.get("/api/v1/conversations", headers=ah).json()[0]["id"]
    return ah, bh, ad, bd, conversation
