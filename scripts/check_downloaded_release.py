"""Check a downloaded draft independently of the machine that built it."""
import argparse
import json
from pathlib import Path

from release_manifest import build_manifest

parser = argparse.ArgumentParser()
parser.add_argument("--tag", required=True)
parser.add_argument("--aapt", required=True)
args = parser.parse_args()
directory = Path("dist")
manifest = json.loads((directory / "update.json").read_text(encoding="utf-8"))
actual = build_manifest(directory / "touch.apk", args.aapt, args.tag, Path("CHANGELOG.md"))
for field in ("schemaVersion", "versionCode", "versionName", "packageName", "minSdk", "releaseTag",
              "apkAssetName", "apkSize", "sha256"):
    assert manifest[field] == actual[field], f"APK/manifest mismatch: {field}"
assert (directory / "SHA256SUMS.txt").read_text().strip() == actual["sha256"] + "  touch.apk"
assert actual["versionCode"] > 0 and actual["minSdk"] == 29
print("APK identity, manifest and checksums verified.")
