"""Fail the CI gate when instrumentation was silently skipped or did not run."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

reports = list(Path(sys.argv[1]).rglob("TEST-*.xml"))
if not reports:
    raise SystemExit("No instrumentation XML results found")
tests = 0
for path in reports:
    suite = ET.parse(path).getroot()
    tests += int(suite.get("tests", "0"))
    if any(int(suite.get(key, "0")) for key in ("failures", "errors", "skipped")):
        raise SystemExit("Instrumentation gate has failures or skipped tests: " + str(path))
if tests < 8:
    raise SystemExit("Instrumentation gate ran fewer than the expected core tests")
print(f"Instrumentation gate verified {tests} passing tests without skips")
