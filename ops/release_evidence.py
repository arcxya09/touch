"""Validate release-specific human/device evidence before public distribution."""
import argparse
import hashlib
import json
import re
from pathlib import Path
from urllib.parse import urlsplit

REQUIRED_CHECKS = ("historicalPublishedUpgrade", "signedPackageRejection", "domesticOemNotifications",
                   "domesticOemBackgroundReturn", "domesticOemDoze", "offlineRecovery")


def validate(evidence, manifest, commit):
    if evidence.get("schemaVersion") != 1:
        raise ValueError("Evidence schemaVersion must be 1")
    for field, actual in (("releaseTag", manifest["releaseTag"]), ("commit", commit), ("apkSha256", manifest["sha256"])):
        if evidence.get(field) != actual:
            raise ValueError("Evidence identity mismatch: " + field)
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A complete commit SHA is required")
    if evidence.get("productionAccountsUsed") is not False:
        raise ValueError("Use isolated test accounts only")
    checks = evidence.get("checks", {})
    for name in REQUIRED_CHECKS:
        check = checks.get(name, {})
        url = urlsplit(str(check.get("evidenceUrl", "")))
        if check.get("passed") is not True or url.scheme != "https" or not url.hostname or url.username or url.password:
            raise ValueError("Missing passed check and reviewable evidence URL: " + name)
    previous = checks["historicalPublishedUpgrade"].get("fromVersionCode")
    if type(previous) is not int or not 0 < previous < manifest["versionCode"]:
        raise ValueError("Historical upgrade must name an older published versionCode")
    if checks["historicalPublishedUpgrade"].get("sourceKind") != "published-apk":
        raise ValueError("A source fixture cannot stand in for a historically published APK")
    for name in ("domesticOemNotifications", "domesticOemBackgroundReturn", "domesticOemDoze"):
        row = checks[name]
        if not all(isinstance(row.get(field), str) and row[field].strip() for field in ("manufacturer", "model", "osVersion")):
            raise ValueError("Domestic device check requires manufacturer, model and OS: " + name)
        if row.get("emulator") is not False:
            raise ValueError("Domestic device acceptance must use a physical device")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    evidence = json.loads(args.evidence.read_text(encoding="utf-8-sig"))
    manifest = json.loads(args.manifest.read_text(encoding="utf-8-sig"))
    with args.apk.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != manifest["sha256"]:
            raise ValueError("Evidence target APK differs from the signed release manifest")
    validate(evidence, manifest, args.commit)
    print("Release evidence identity and required acceptance records verified")


if __name__ == "__main__":
    main()
