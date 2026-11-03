#!/usr/bin/env python3
"""Validate normalized tracked text and reject unresolved merge artifacts."""
import argparse
import subprocess
import sys
from pathlib import Path

TEXT_SUFFIXES = {".yml", ".yaml", ".py", ".md", ".kts", ".kt", ".java", ".xml", ".sh", ".bat"}
MARKERS = (b"<<<<<<< ", b"=======", b">>>>>>> ")

def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--conflicts-only", action="store_true")
    args = parser.parse_args()
    if git("ls-files", "-u").strip():
        print("Unmerged index paths remain", file=sys.stderr)
        return 1
    changed = git("diff", "--cached", "--diff-filter=U", "--name-only").strip()
    if changed:
        print("Unmerged staged paths remain", file=sys.stderr)
        return 1
    diff = git("diff", "--cached", "--unified=0")
    for line in diff.splitlines():
        if line.startswith(b"+") and not line.startswith(b"+++") and any(m in line for m in MARKERS):
            print("Conflict marker found in staged changes", file=sys.stderr)
            return 1
    if not args.conflicts_only:
        paths = git("ls-files", "-z").decode().split("\0")
        bad = [p for p in paths if p and Path(p).suffix.lower() in TEXT_SUFFIXES and b"\r" in Path(p).read_bytes()]
        if bad:
            print("CR byte found in tracked text files:\n" + "\n".join(bad), file=sys.stderr)
            return 1
    print("Sync tree validation passed")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
