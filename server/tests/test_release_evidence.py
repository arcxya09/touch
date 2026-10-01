import copy
import importlib.util
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location("release_evidence", Path(__file__).resolve().parents[2] / "ops/release_evidence.py")
release_evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release_evidence)


def complete():
    manifest = {"releaseTag": "v2.0.0", "sha256": "a" * 64, "versionCode": 20}
    evidence = {"schemaVersion": 1, "releaseTag": "v2.0.0", "commit": "b" * 40,
                "apkSha256": "a" * 64, "productionAccountsUsed": False, "checks": {}}
    for check in release_evidence.REQUIRED_CHECKS:
        evidence["checks"][check] = {"passed": True, "evidenceUrl": "https://example.test/record",
            "sourceKind": "published-apk", "fromVersionCode": 19, "manufacturer": "Xiaomi",
            "model": "physical-test-device", "osVersion": "Android 13", "emulator": False}
    return evidence, manifest


def test_release_evidence_requires_matching_artifact_identity():
    evidence, manifest = complete()
    release_evidence.validate(evidence, manifest, "b" * 40)
    for field in ("releaseTag", "commit", "apkSha256"):
        other = copy.deepcopy(evidence)
        other[field] = "mismatch"
        with pytest.raises(ValueError, match="identity mismatch"):
            release_evidence.validate(other, manifest, "b" * 40)


@pytest.mark.parametrize("name", release_evidence.REQUIRED_CHECKS)
def test_release_evidence_fails_closed_on_unexecuted_checks(name):
    evidence, manifest = complete()
    evidence["checks"][name]["passed"] = False
    with pytest.raises(ValueError, match="Missing passed check"):
        release_evidence.validate(evidence, manifest, "b" * 40)


def test_source_fixture_or_emulator_cannot_replace_real_acceptance():
    evidence, manifest = complete()
    evidence["checks"]["historicalPublishedUpgrade"]["sourceKind"] = "signed-source-fixture-upgrade"
    with pytest.raises(ValueError, match="source fixture"):
        release_evidence.validate(evidence, manifest, "b" * 40)
    evidence, manifest = complete()
    evidence["checks"]["domesticOemDoze"]["emulator"] = True
    with pytest.raises(ValueError, match="physical device"):
        release_evidence.validate(evidence, manifest, "b" * 40)


@pytest.mark.parametrize("url", ["https://", "http://example.test/proof", "https://user:secret@example.test/proof"])
def test_release_evidence_requires_reviewable_https_url(url):
    evidence, manifest = complete()
    evidence["checks"]["offlineRecovery"]["evidenceUrl"] = url
    with pytest.raises(ValueError, match="reviewable evidence URL"):
        release_evidence.validate(evidence, manifest, "b" * 40)


def test_release_evidence_rejects_boolean_historical_version():
    evidence, manifest = complete()
    evidence["checks"]["historicalPublishedUpgrade"]["fromVersionCode"] = True
    with pytest.raises(ValueError, match="older published versionCode"):
        release_evidence.validate(evidence, manifest, "b" * 40)
