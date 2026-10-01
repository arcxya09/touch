"""Disposable loopback API for emulator CI. Never uses deployment configuration."""
import os
from io import BytesIO
from pathlib import Path
import sys

root = Path(os.environ["TOUCH_TEST_DATA_DIR"]).resolve()
if not root.name.startswith("touch-device-") or (root / "fixture.db").exists():
    raise RuntimeError("Use a fresh touch-device-* temporary directory")
root.mkdir(parents=True, exist_ok=True)
port = int(os.getenv("TOUCH_TEST_PORT", "8010"))
os.environ.update(DATABASE_URL="sqlite:///" + str(root / "fixture.db"), DATA_DIR=str(root),
                  PUBLIC_BASE_URL=f"http://127.0.0.1:{port}", SECURE_COOKIES="false")
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import uvicorn  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402
from app.database import engine, SessionLocal  # noqa: E402
from app.main import app  # noqa: E402
from app.models import Base, Contact, Conversation, Message, User, uid  # noqa: E402
from app.security import password_hasher  # noqa: E402
from app.services import emit, pair  # noqa: E402

Base.metadata.create_all(engine)
with SessionLocal() as db:
    first = User(username="verify_local_ci", display_name="验收设备A", must_change_password=False,
                 password_hash=password_hasher.hash("test-only-local-123"))
    second = User(username="verify_local_peer", display_name="验收设备B", must_change_password=False,
                  password_hash=password_hasher.hash("test-only-local-123"))
    admin = User(username="verify_local_admin", display_name="本地管理员", must_change_password=False, is_admin=True,
                 password_hash=password_hasher.hash("test-only-local-123"))
    db.add_all([first, second, admin])
    db.flush()
    a, b = sorted([first.id, second.id])
    key = pair(a, b)
    db.add(Contact(pair_key=key, a=a, b=b, requester=a, state="accepted"))
    conversation = Conversation(pair_key=key, a=a, b=b, next_seq=1)
    db.add(conversation)
    db.flush()
    message = Message(conversation_id=conversation.id, sender_id=second.id, client_id=uid(), seq=1,
                      kind="text", text="本地设备验收消息")
    db.add(message)
    db.flush()
    emit(db, [a, b], "message", {"message_id": message.id})
    conversation_id = conversation.id
    db.commit()


def two_page_pdf():
    """Small synthetic PDF with explicit object offsets; no external asset or PDF dependency."""
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>",
    ]
    for page, content_id in ((1, 4), (2, 6)):
        objects.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 7 0 R >> >> /Contents "
                       + str(content_id).encode() + b" 0 R >>")
        stream = f"BT /F1 18 Tf 72 760 Td (Touch fixture page {page}) Tj ET\n".encode()
        objects.append(b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"endstream")
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    result = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for number, content in enumerate(objects, 1):
        offsets.append(len(result))
        result.extend(f"{number} 0 obj\n".encode() + content + b"\nendobj\n")
    xref = len(result)
    result.extend(f"xref\n0 {len(offsets)}\n0000000000 65535 f \n".encode())
    for offset in offsets[1:]:
        result.extend(f"{offset:010d} 00000 n \n".encode())
    result.extend(f"trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return bytes(result)


image = BytesIO()
Image.new("RGB", (320, 240), (56, 99, 74)).save(image, format="PNG")
# Requests enter the real application routes and UploadGuard but never open an external socket.
with TestClient(app, base_url=f"http://127.0.0.1:{port}") as client:
    capabilities = {"X-Touch-Capabilities": "recall-v1"}
    response = client.post("/api/v1/auth/login", headers=capabilities,
                           json={"username": "verify_local_peer", "password": "test-only-local-123"})
    response.raise_for_status()
    headers = {**capabilities, "Authorization": "Bearer " + response.json()["access_token"]}
    assets = [
        ("验收文本.txt", "text/plain; charset=utf-8", "file", "Touch UTF-8 文件传输验证".encode()),
        ("验收图片.png", "image/png", "image", image.getvalue()),
        ("验收文档.pdf", "application/pdf", "file", two_page_pdf()),
    ]
    for name, mime, kind, content in assets:
        response = client.post("/api/v1/files", params={"kind": kind}, headers=headers,
                               files={"file": (name, content, mime)})
        response.raise_for_status()
        response = client.post(f"/api/v1/conversations/{conversation_id}/messages", headers=headers,
                               json={"client_id": uid(), "kind": kind, "attachment_id": response.json()["id"]})
        response.raise_for_status()
uvicorn.run("app.main:app", host="127.0.0.1", port=port, access_log=False)
