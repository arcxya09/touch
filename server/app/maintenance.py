from .config import settings
from .database import SessionLocal
from .models import Attachment, AdminSession, MobileSession, now
from .services import garbage_collect
from sqlalchemy import delete, select


def maintain():
    with SessionLocal() as db:
        garbage_collect(db)
        db.execute(delete(AdminSession).where(AdminSession.expires < now()))
        db.execute(delete(MobileSession).where(MobileSession.refresh_expires < now()))
        db.commit()
        ids = set(db.scalars(select(Attachment.id)))
    for path in (settings.data_dir / "files").iterdir():
        if path.is_file() and path.name not in ids and path.stat().st_mtime < now() - 86400:
            path.unlink(missing_ok=True)


if __name__ == "__main__":
    maintain()
