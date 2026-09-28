"""Verify GitHub's uploaded asset sizes and digests before publishing the draft."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

tag = sys.argv[1]
# The tag endpoint may return 404 for a draft; list authenticated releases instead.
pages = json.loads(subprocess.check_output(["gh", "api", "repos/arcxya09/touch/releases?per_page=100",
                                           "--paginate", "--slurp"], text=True))
matches = [item for page in pages for item in page if item["tag_name"] == tag]
assert len(matches) == 1, "Missing or ambiguous release draft"
release = matches[0]
assert release["draft"], "Never replace an already published release"
for name in ("touch.apk", "update.json", "SHA256SUMS.txt"):
    matches = [asset for asset in release["assets"] if asset["name"] == name and asset["state"] == "uploaded"]
    assert len(matches) == 1, f"Missing or duplicate asset: {name}"
    path = Path("dist") / name
    asset = matches[0]
    assert asset["size"] == path.stat().st_size, f"Size mismatch: {name}"
    expected = "sha256:" + hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
    assert asset.get("digest") == expected, f"Digest mismatch: {name}"
print("All uploaded release assets verified.")
