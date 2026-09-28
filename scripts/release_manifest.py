"""Generate the update contract from the signed APK, never from a hand-edited manifest."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def build_manifest(apk: Path, aapt: str, tag: str, notes: Path) -> dict:
    metadata = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True, encoding="utf-8")
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", metadata)
    sdk = re.search(r"sdkVersion:'(\d+)'", metadata)
    if not package or not sdk:
        raise ValueError("Could not read APK metadata")
    name, code, version = package.groups()
    if name != "com.arcxya09.touch" or tag != "v" + version or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Release tag/package/version does not match the signed APK")
    return {"schemaVersion": 1, "versionCode": int(code), "versionName": version, "packageName": name,
            "minSdk": int(sdk.group(1)), "releaseTag": tag, "apkAssetName": "touch.apk", "apkSize": apk.stat().st_size,
            "sha256": hashlib.file_digest(apk.open("rb"), "sha256").hexdigest(), "changelog": notes.read_text(encoding="utf-8").strip()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--notes", type=Path, default=Path("RELEASE_NOTES.md"))
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    manifest = build_manifest(args.apk, args.aapt, args.tag, args.notes)
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (args.output / "SHA256SUMS.txt").write_text(manifest["sha256"] + "  touch.apk\n", encoding="utf-8")
    print(f"Manifest verified: {manifest['versionName']} ({manifest['versionCode']})")


if __name__ == "__main__":
    main()
