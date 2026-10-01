"""Exercise Alembic against populated SQLite and an isolated PostgreSQL schema."""
import os
from pathlib import Path
import subprocess
import sys
from uuid import uuid4

from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url


def test_previous_release_migrates_without_losing_history_or_sessions(tmp_path):
    supplied = os.getenv("TEST_DATABASE_URL", "")
    admin_engine = None
    schema = "touch_migration_" + uuid4().hex
    if supplied.startswith("postgresql"):
        url = make_url(supplied)
        assert url.database == "touch_test", "Migration tests require the disposable touch_test database"
        admin_engine = create_engine(url)
        with admin_engine.begin() as db:
            db.execute(text(f"CREATE SCHEMA {schema}"))
        target = url.update_query_dict({"options": "-csearch_path=" + schema}).render_as_string(hide_password=False)
    else:
        target = "sqlite:///" + str(tmp_path / "populated.db")
    database = create_engine(target)
    env = os.environ | {"DATABASE_URL": target, "DATA_DIR": str(tmp_path / "data")}
    root = Path(__file__).resolve().parents[1]

    def upgrade(version):
        subprocess.run([sys.executable, "-m", "alembic", "upgrade", version], cwd=root, env=env,
                       capture_output=True, check=True)

    try:
        upgrade("f620ba741901")
        with database.begin() as db:
            db.execute(text("INSERT INTO users (id,username,display_name,password_hash,active,is_admin,"
                            "must_change_password,session_epoch,sync_seq,created_at) "
                            "VALUES ('u','preserved','Name','hash',true,false,false,3,7,123)"))
            db.execute(text("INSERT INTO mobile_sessions (id,user_id,access_hash,refresh_hash,access_expires,refresh_expires,epoch) "
                            "VALUES ('s','u','access','refresh',9999999999,9999999999,3)"))
            db.execute(text("INSERT INTO conversations (id,pair_key,a,b,next_seq,a_read,b_read,a_clear,b_clear) "
                            "VALUES ('c','u:u','u','u',1,0,0,0,0)"))
            db.execute(text("INSERT INTO messages (id,conversation_id,sender_id,client_id,seq,kind,text,created_at) "
                            "VALUES ('m','c','u','client',1,'text','preserved private text',123)"))
            db.execute(text("INSERT INTO send_receipts VALUES ('u','client')"))
        upgrade("head")
        with database.connect() as db:
            row = db.execute(text("SELECT * FROM mobile_sessions WHERE id='s'")).mappings().one()
            assert not row["supports_recall"] and row["access_hash"] == "access" and row["epoch"] == 3
            assert db.scalar(text("SELECT text FROM messages WHERE id='m'")) == "preserved private text"
            assert db.scalar(text("SELECT count(*) FROM send_receipts")) == 1
        upgrade("head")
    finally:
        database.dispose()
        if admin_engine:
            with admin_engine.begin() as db:
                db.execute(text(f"DROP SCHEMA {schema} CASCADE"))
            admin_engine.dispose()
