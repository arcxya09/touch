import importlib.util
import hashlib
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


@pytest.mark.parametrize("allow_prerelease", [False, True])
def test_published_stable_release_is_never_reused(monkeypatch, allow_prerelease):
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw:
                        json.dumps([[{"tag_name": "v1.0.1", "draft": False, "prerelease": False}]]))
    with pytest.raises(AssertionError, match="already published"):
        release_check.find_draft("v1.0.1", allow_prerelease=allow_prerelease)


def test_public_prerelease_requires_explicit_opt_in(monkeypatch):
    candidate = {"tag_name": "v2.0.0", "draft": False, "prerelease": True}
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw: json.dumps([[candidate]]))
    with pytest.raises(AssertionError, match="require --allow-prerelease"):
        release_check.find_draft("v2.0.0")
    assert release_check.find_draft("v2.0.0", allow_prerelease=True) == candidate


def test_prerelease_opt_in_still_rejects_ambiguous_identity(monkeypatch):
    candidate = {"tag_name": "v2.0.0", "draft": False, "prerelease": True}
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw:
                        json.dumps([[candidate], [candidate]]))
    with pytest.raises(AssertionError, match="Ambiguous"):
        release_check.find_draft("v2.0.0", allow_prerelease=True)


@pytest.mark.parametrize("corruption,error", [(None, None), ("digest", "Digest mismatch"),
                                             ("size", "Size mismatch"), ("missing", "Missing or duplicate asset")])
def test_prerelease_cli_preserves_all_asset_checks(monkeypatch, tmp_path, corruption, error):
    directory = tmp_path / "dist"
    directory.mkdir()
    assets = []
    for name in ("touch.apk", "update.json", "SHA256SUMS.txt"):
        content = ("fixture:" + name).encode()
        (directory / name).write_bytes(content)
        assets.append({"name": name, "state": "uploaded", "size": len(content),
                       "digest": "sha256:" + hashlib.sha256(content).hexdigest()})
    if corruption == "digest":
        assets[1]["digest"] = "sha256:" + "0" * 64
    elif corruption == "size":
        assets[0]["size"] += 1
    elif corruption == "missing":
        assets.pop()
    candidate = {"tag_name": "v2.0.0", "draft": False, "prerelease": True, "assets": assets}
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(release_check.subprocess, "check_output", lambda *a, **kw: json.dumps([[candidate]]))
    if error:
        with pytest.raises(AssertionError, match=error):
            release_check.main(["v2.0.0", "--allow-prerelease"])
    else:
        release_check.main(["v2.0.0", "--allow-prerelease"])
