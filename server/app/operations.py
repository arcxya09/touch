"""Small, content-free operational state; detailed diagnostics require admin."""
import json
import threading
import time

from .config import settings

_lock = threading.Lock()
_maintenance = {"last_success": None, "last_failure": None, "error_kind": None}


def record_maintenance(error=None):
    with _lock:
        _maintenance["last_failure" if error else "last_success"] = int(time.time())
        _maintenance["error_kind"] = type(error).__name__ if error else None


def operational_status():
    with _lock:
        maintenance = dict(_maintenance)
    backup = {"state": "missing", "last_success": None}
    try:
        timestamp = int((settings.data_dir / "backup-success.txt").read_text(encoding="utf-8").strip())
        age = int(time.time()) - timestamp
        if timestamp > 0 and age >= -300:
            backup = {"state": "ok" if age <= settings.backup_max_age_seconds else "stale", "last_success": timestamp}
    except (OSError, ValueError):
        pass
    return {"maintenance": maintenance, "backup": backup}


if __name__ == "__main__":
    print(json.dumps(operational_status()))
