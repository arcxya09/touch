"""Run only against an isolated disposable database and local HTTP server."""
import asyncio
import contextlib
import json
import os
import time
import uuid

import httpx
from sqlalchemy.engine import make_url
from websockets.asyncio.client import connect

from app.database import SessionLocal
from app.models import Base, Contact, Conversation, MobileSession, User
from app.database import engine
from app.security import digest, password_hasher


async def main():
    assert make_url(os.environ["DATABASE_URL"]).database == "touch_test", "Disposable touch_test database only"
    Base.metadata.drop_all(engine)
    Base.metadata.create_all(engine)
    credentials, conversations = [], []
    hashed = password_hasher.hash(str(uuid.uuid4()))
    with SessionLocal() as db:
        people = []
        for index in range(100):
            user = User(username=f"load{index:03}", display_name=f"Load {index}", password_hash=hashed,
                        must_change_password=False)
            db.add(user)
            db.flush()
            raw = str(uuid.uuid4())
            db.add(MobileSession(user_id=user.id, access_hash=digest(raw), refresh_hash=digest(str(uuid.uuid4())),
                                 access_expires=int(time.time()) + 600, refresh_expires=int(time.time()) + 600, epoch=0))
            credentials.append(raw)
            people.append(user.id)
        for index in range(0, 100, 2):
            a, b = sorted(people[index:index + 2])
            key = a + ":" + b
            db.add(Contact(pair_key=key, a=a, b=b, requester=a, state="accepted"))
            conversation = Conversation(pair_key=key, a=a, b=b)
            db.add(conversation)
            db.flush()
            conversations.append(conversation.id)
        db.commit()
    started = time.monotonic()
    async with contextlib.AsyncExitStack() as stack, httpx.AsyncClient(timeout=30) as client:
        sockets = []
        for raw in credentials:
            ws = await stack.enter_async_context(connect("ws://127.0.0.1:8010/ws",
                     additional_headers={"Authorization": "Bearer " + raw}, open_timeout=30))
            assert json.loads(await ws.recv())["cursor"] == 0
            sockets.append(ws)
        for index, conversation in enumerate(conversations):
            headers = {"Authorization": "Bearer " + credentials[index * 2]}
            body = {"client_id": str(uuid.uuid4()), "kind": "text", "text": f"Load sync {index}"}
            url = f"http://127.0.0.1:8010/api/v1/conversations/{conversation}/messages"
            sent = await client.post(url, headers=headers, json=body)
            assert sent.status_code == 200, sent.text
            retry = await client.post(url, headers=headers, json=body)
            assert retry.json()["id"] == sent.json()["id"]
        async def verify(index, ws):
            hint = json.loads(await asyncio.wait_for(ws.recv(), 30))
            assert hint["cursor"] == 1
            response = await client.get("http://127.0.0.1:8010/api/v1/sync", headers={"Authorization": "Bearer " + credentials[index]})
            events = response.json()["events"]
            assert len(events) == 1 and events[0]["kind"] == "message"
        # Keep all 100 sockets open while reading every user's committed event.
        for index, ws in enumerate(sockets):
            await verify(index, ws)
    print(json.dumps({"connections": 100, "messages": 50, "idempotent_retries": 50,
                      "verified_event_streams": 100, "elapsed_seconds": round(time.monotonic() - started, 2)}))


if __name__ == "__main__":
    asyncio.run(main())
