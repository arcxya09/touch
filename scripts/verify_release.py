"""Verify uploaded draft assets, or an explicitly allowed public prerelease."""
import argparse
import hashlib
import json
import subprocess
import time
from pathlib import Path


def find_draft(tag, allow_prerelease=False):
    # A new draft can take time to appear in the authenticated release list.
    # Retry absent metadata only; never relax asset or identity verification.
    for attempt in range(8):
        pages = json.loads(subprocess.check_output([
            "gh", "api", "repos/arcxya09/touch/releases?per_page=100", "--paginate", "--slurp",
            "-H", "Cache-Control: no-cache"], text=True, encoding="utf-8"))
        matches = [item for page in pages for item in page if item["tag_name"] == tag]
        if matches:
            assert len(matches) == 1, "Ambiguous release candidate"
            release = matches[0]
            assert release.get("draft") is True or (
                allow_prerelease and release.get("draft") is False and release.get("prerelease") is True
            ), "Never reuse an already published stable release; public prereleases require --allow-prerelease"
            return release
        if attempt < 7:
            time.sleep(min(2 ** (attempt + 1), 8))
    raise AssertionError("Release candidate did not become visible within the retry window")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--allow-prerelease", action="store_true",
                        help="Also permit a published prerelease; published stable releases are always rejected")
    args = parser.parse_args(argv)
    release = find_draft(args.tag, allow_prerelease=args.allow_prerelease)
    for name in ("touch.apk", "update.json", "SHA256SUMS.txt"):
        matches = [asset for asset in release["assets"] if asset["name"] == name and asset["state"] == "uploaded"]
        assert len(matches) == 1, f"Missing or duplicate asset: {name}"
        path = Path("dist") / name
        asset = matches[0]
        assert asset["size"] == path.stat().st_size, f"Size mismatch: {name}"
        expected = "sha256:" + hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
        assert asset.get("digest") == expected, f"Digest mismatch: {name}"
    print("All uploaded release assets verified.")


if __name__ == "__main__":
    main()
