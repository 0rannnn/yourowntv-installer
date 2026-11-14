"""ערכה-לחבר.zip beside the kit folder, made again from the folder as it is now (publish.ps1, after every commit), so
the one-file copy is never older than the folder. Written to a temporary name first, then swapped in.

    python make_zip.py
"""
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KIT = "ערכה-לחבר"
SKIP = {"__pycache__"}


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    target = os.path.join(ROOT, KIT + ".zip")
    tmp = target + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for folder, dirs, files in os.walk(os.path.join(ROOT, KIT)):
            dirs[:] = [d for d in dirs if d not in SKIP]
            for name in files:
                path = os.path.join(folder, name)
                z.write(path, os.path.relpath(path, ROOT))
    os.replace(tmp, target)
    print("zip: %s %.1f MB" % (target, os.path.getsize(target) / 1e6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
