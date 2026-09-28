"""Verify GitHub's uploaded asset sizes and digests before publishing the draft."""
import hashlib
import json
import subprocess
import sys
from pathlib import Path

tag = sys.argv[1]
release = json.loads(subprocess.check_output(["gh", "api", f"repos/arcxya09/touch/releases/tags/{tag}"], text=True))
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
