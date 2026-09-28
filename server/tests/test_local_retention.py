from uuid import uuid4

from sqlalchemy import select

from app.database import SessionLocal
from app.models import Message, Conversation
from conftest import friends


def test_retention_filters_every_read_without_deleting_server_or_peer_history(client):
    ah, bh, _, _, cid = friends(client)
    attachment = client.post('/api/v1/files?kind=file', headers=bh,
                             files={'file': ('private.txt', b'expired-secret', 'text/plain')}).json()
    old = client.post(f'/api/v1/conversations/{cid}/messages', headers=bh,
                      json={'client_id': str(uuid4()), 'kind': 'file', 'attachment_id': attachment['id']}).json()
    new = client.post(f'/api/v1/conversations/{cid}/messages', headers=bh,
                      json={'client_id': str(uuid4()), 'kind': 'text', 'text': 'retained-message'}).json()
    with SessionLocal() as db:
        db.get(Message, old['id']).created_at = 100
        db.get(Message, new['id']).created_at = 101
        db.commit()
    history = client.get(f'/api/v1/conversations/{cid}/messages?after_time=100&limit=1', headers=ah).json()
    assert [m['id'] for m in history['messages']] == [new['id']]
    assert history['has_more'] is False
    assert client.get(f'/api/v1/conversations/{cid}/messages?after_time=100&before=2', headers=ah).json()['messages'] == []
    conversations = client.get('/api/v1/conversations?after_time=100', headers=ah).json()
    assert conversations[0]['unread'] == 1
    assert conversations[0]['last_message']['id'] == new['id']
    empty = client.get('/api/v1/conversations?after_time=101', headers=ah).json()[0]
    assert empty['unread'] == 0 and empty['last_message'] is None
    cursor = 0
    kinds = []
    while True:
        page = client.get(f'/api/v1/sync?cursor={cursor}&limit=1&after_time=100', headers=ah).json()
        kinds.extend(e['kind'] for e in page['events'])
        assert 'expired-secret' not in str(page)
        assert old['id'] not in str(page)
        assert attachment['name'] not in str(page)
        cursor = page['cursor']
        if not page['has_more']:
            break
    assert 'noop' in kinds and kinds.count('message') == 1
    assert client.get(f"/api/v1/files/{attachment['id']}?after_time=100", headers=ah).status_code == 404
    assert client.get(f"/api/v1/files/{attachment['id']}", headers=bh).content == b'expired-secret'
    assert len(client.get(f'/api/v1/conversations/{cid}/messages', headers=ah).json()['messages']) == 2
    assert len(client.get(f'/api/v1/conversations/{cid}/messages', headers=bh).json()['messages']) == 2
    with SessionLocal() as db:
        assert len(db.scalars(select(Message)).all()) == 2
        conversation = db.get(Conversation, cid)
        assert conversation.a_clear == conversation.b_clear == 0


def test_retention_filter_does_not_bypass_attachment_or_conversation_permissions(client):
    from conftest import login
    ah, _, _, _, cid = friends(client)
    ch, _ = login(client, 'charlie')
    attachment = client.post('/api/v1/files?kind=file', headers=ah,
                             files={'file': ('secret.txt', b'secret', 'text/plain')}).json()
    assert client.get(f'/api/v1/conversations/{cid}/messages?after_time=1', headers=ch).status_code == 404
    assert client.get(f"/api/v1/files/{attachment['id']}?after_time=1", headers=ch).status_code == 404
    for path in ['/api/v1/sync', '/api/v1/conversations', f'/api/v1/conversations/{cid}/messages',
                 f"/api/v1/files/{attachment['id']}"]:
        assert client.get(path + '?after_time=-1', headers=ah).status_code == 422
