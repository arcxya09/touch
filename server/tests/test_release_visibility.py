import importlib.util
import json
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("release_check", Path(__file__).resolve().parents[2] / "scripts/verify_release.py")
release_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_check)


def test_new_draft_eventually_appears(monkeypatch):
    draft = {"tag_name": "v1.0.1", "draft": True}
    replies = iter(["[[]]", "[[]]", json.dumps([[draft]])])
    waits = []
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw: next(replies))
    monkeypatch.setattr(release_check.time, "sleep", waits.append)
    assert release_check.find_draft("v1.0.1") == draft
    assert waits == [2, 4]


def test_missing_draft_retry_is_bounded(monkeypatch):
    waits = []
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw: "[[]]")
    monkeypatch.setattr(release_check.time, "sleep", waits.append)
    with pytest.raises(AssertionError, match="did not become visible"):
        release_check.find_draft("v1.0.1")
    assert len(waits) == 7 and sum(waits) <= 60


def test_published_release_is_never_reused(monkeypatch):
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw:
                        json.dumps([[{"tag_name": "v1.0.1", "draft": False}]]))
    with pytest.raises(AssertionError, match="already published"):
        release_check.find_draft("v1.0.1")
