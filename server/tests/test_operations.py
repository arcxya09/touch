from dataclasses import replace
import time

from app import operations


def test_backup_status_distinguishes_missing_recent_and_stale(tmp_path, monkeypatch):
    monkeypatch.setattr(operations, "settings", replace(operations.settings, data_dir=tmp_path))
    assert operations.operational_status()["backup"]["state"] == "missing"
    marker = tmp_path / "backup-success.txt"
    marker.write_text(str(int(time.time())), encoding="utf-8")
    assert operations.operational_status()["backup"]["state"] == "ok"
    marker.write_text(str(int(time.time()) - 37 * 3600), encoding="utf-8")
    assert operations.operational_status()["backup"]["state"] == "stale"
    marker.write_text("invalid", encoding="utf-8")
    assert operations.operational_status()["backup"]["state"] == "missing"


def test_maintenance_status_does_not_include_exception_content():
    operations.record_maintenance(ValueError("private text must never be returned"))
    status = operations.operational_status()["maintenance"]
    assert status["error_kind"] == "ValueError" and status["last_failure"]
    assert "private text" not in str(status)
    operations.record_maintenance()
    assert operations.operational_status()["maintenance"]["error_kind"] is None
