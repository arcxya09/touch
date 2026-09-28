"""Verify GitHub's uploaded asset sizes and digests before publishing the draft."""
import hashlib
import json
import subprocess
import sys
import time
from pathlib import Path

def find_draft(tag):
    # A new draft can take time to appear in the authenticated release list.
    # Retry absent metadata only; never relax asset or identity verification.
    for attempt in range(8):
        pages = json.loads(subprocess.check_output([
            "gh", "api", "repos/arcxya09/touch/releases?per_page=100", "--paginate", "--slurp",
            "-H", "Cache-Control: no-cache"], text=True, encoding="utf-8"))
        matches = [item for page in pages for item in page if item["tag_name"] == tag]
        if matches:
            assert len(matches) == 1, "Ambiguous release draft"
            assert matches[0]["draft"], "Never replace an already published release"
            return matches[0]
        if attempt < 7:
            time.sleep(min(2 ** (attempt + 1), 8))
    raise AssertionError("Release draft did not become visible within the retry window")


def main():
    release = find_draft(sys.argv[1])
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
