"""Wait for a stable Android framework, preserving diagnostics before emulator teardown."""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import subprocess
import sys
import time


# sys.boot_completed stays set when SurfaceFlinger restarts Zygote/SystemServer.
# The API 37 gate hit that window before installing its test APKs.
PROBE = "\n".join(
    (
        'echo "boot=$(getprop sys.boot_completed)"',
        'echo "system_server=$(pidof system_server)"',
        'echo "surfaceflinger=$(pidof surfaceflinger)"',
        'echo "package=$(cmd package path android)"',
        'echo "user=$(am get-current-user)"',
    )
)


def framework_identity(output: str) -> tuple[str, str, str] | None:
    """Only successful service replies and live process identities count as ready."""
    values = dict(re.findall(r"^(boot|system_server|surfaceflinger|package|user)=(.*)$", output, re.M))
    if values.get("boot") != "1" or not values.get("package", "").startswith("package:/"):
        return None
    identity = tuple(values.get(key, "") for key in ("system_server", "surfaceflinger", "user"))
    if not all(value.isdecimal() for value in identity):
        return None
    return identity


def wait_for_framework(adb: list[str], destination: Path, timeout: float = 120, stable_for: float = 20) -> bool:
    deadline = time.monotonic() + timeout
    previous: tuple[str, str, str] | None = None
    stable_since: float | None = None
    with destination.open("w", encoding="utf-8") as log:
        while time.monotonic() < deadline:
            identity = None
            try:
                result = subprocess.run(
                    [*adb, "shell", PROBE], capture_output=True, text=True,
                    timeout=min(8, max(0.1, deadline - time.monotonic())), check=False,
                )
                output = result.stdout.replace("\r", "")
                log.write(f"{time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} exit={result.returncode}\n")
                log.write(output + result.stderr + "\n")
                if result.returncode == 0:
                    identity = framework_identity(output)
            except subprocess.TimeoutExpired:
                log.write("Framework probe timed out.\n")
            now = time.monotonic()
            if identity is None or identity != previous:
                stable_since = now if identity is not None else None
            previous = identity
            log.flush()
            if identity is not None and stable_since is not None and now - stable_since >= stable_for:
                print(f"Android framework ready: system_server={identity[0]}, surfaceflinger={identity[1]}, user={identity[2]} (stable for {stable_for:g}s)", flush=True)
                return True
            time.sleep(min(2, max(0, deadline - now)))
    print(f"::error::Android package/activity services did not remain ready for {stable_for:g}s within {timeout:g}s; see framework-readiness.log and logcat.", flush=True)
    return False


def collect_diagnostics(adb: list[str], destination: Path) -> None:
    commands = (
        ("device-state.txt", ["get-state"]),
        ("properties.txt", ["shell", "getprop"]),
        ("processes.txt", ["shell", "ps", "-A"]),
        ("services.txt", ["shell", "service", "list"]),
        ("data-space.txt", ["shell", "df", "-h", "/data"]),
        ("logcat-crash.txt", ["logcat", "-b", "crash", "-d", "-v", "threadtime"]),
        ("dropbox-system-server.txt", ["shell", "dumpsys", "dropbox", "--print", "system_server_crash"]),
        ("dropbox-native-crash.txt", ["shell", "dumpsys", "dropbox", "--print", "SYSTEM_TOMBSTONE"]),
    )
    for name, command in commands:
        with (destination / name).open("w", encoding="utf-8") as log:
            try:
                result = subprocess.run([*adb, *command], stdout=log, stderr=subprocess.STDOUT, timeout=8, check=False)
                log.write(f"\nDiagnostic command exit={result.returncode}\n")
            except (OSError, subprocess.TimeoutExpired) as error:
                log.write(f"\nDiagnostic command unavailable: {error}\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--diagnostics", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("A test command is required after --")
    args.diagnostics.mkdir(parents=True, exist_ok=True)
    serial = os.environ.get("ANDROID_SERIAL", "emulator-5554")
    adb = ["adb", "-s", serial]
    logger = None
    with (args.diagnostics / "logcat.txt").open("w", encoding="utf-8") as log:
        try:
            # Include the buffered boot records, then stream until tests and snapshots finish.
            logger = subprocess.Popen([*adb, "logcat", "-b", "all", "-v", "threadtime"], stdout=log, stderr=subprocess.STDOUT)
            if not wait_for_framework(adb, args.diagnostics / "framework-readiness.log"):
                return 1
            subprocess.run([*adb, "reverse", "tcp:8010", "tcp:8010"], timeout=10, check=True)
            return subprocess.run(command, check=False).returncode
        except (OSError, subprocess.SubprocessError) as error:
            print(f"::error::Device test setup failed: {error}", flush=True)
            return 1
        finally:
            collect_diagnostics(adb, args.diagnostics)
            if logger is not None:
                logger.terminate()
                try:
                    logger.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    logger.kill()
                    logger.wait()


if __name__ == "__main__":
    sys.exit(main())
