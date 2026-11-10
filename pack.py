"""Packs the phone kit: copies the editor's theme library (already without the owner's details), the newest launcher
and the debloat lists from the install kit (ערכה-לחבר\\מערכת) into the installer app's assets, so the app carries
exactly what the editor shows. build.ps1 runs this, then Gradle.

    python pack.py
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.join(os.path.dirname(HERE), "ערכה-לחבר", "מערכת")
ASSETS = os.path.join(HERE, "installer", "app", "src", "main", "assets")


def main():
    if not os.path.isdir(os.path.join(KIT, "library")):
        sys.exit("no theme library at " + KIT + " (open the editor once so it fills the kit)")
    for name in ("library", "debloat"):
        target = os.path.join(ASSETS, name)
        shutil.rmtree(target, ignore_errors=True)
        shutil.copytree(os.path.join(KIT, name), target)
    shutil.copy2(os.path.join(KIT, "launcher.apk"), os.path.join(ASSETS, "launcher.apk"))
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(ASSETS) for f in fs)
    slots = sorted(n for n in os.listdir(os.path.join(ASSETS, "library")) if os.path.isdir(os.path.join(ASSETS, "library", n)))
    print("packed", ", ".join(slots), "-", round(size / 1048576, 1), "MB")


if __name__ == "__main__":
    main()
