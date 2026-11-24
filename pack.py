"""Packs the phone kit: copies the editor's theme library (already without the owner's details), the newest launcher
and the debloat lists from the install kit (ערכה-לחבר\\מערכת) into the installer app's assets, so the app carries
exactly what the editor shows. build.ps1 runs this, then Gradle.

    python pack.py
"""
import json
import hashlib
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
KIT = os.path.join(os.path.dirname(HERE), "ערכה-לחבר", "מערכת")
ASSETS = os.path.join(HERE, "installer", "app", "src", "main", "assets")


def dedupe(library):
    """Each file once: the themes share fonts and wallpapers (the Hebrew font was in all five, 1.8 MB), so a later copy
    becomes NAME.same, a few bytes naming the first one ("library/apple/ui_font_he.ttf"); the installer follows it
    (Kit.read). About 3 MB less to download."""
    first = {}
    saved = 0
    for folder, _, files in sorted(os.walk(library)):
        for name in sorted(files):
            path = os.path.join(folder, name)
            digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
            rel = "library/" + os.path.relpath(path, library).replace(os.sep, "/")
            if digest not in first:
                first[digest] = rel
                continue
            saved += os.path.getsize(path)
            os.remove(path)
            open(path + ".same", "w", encoding="utf-8").write(first[digest])
    print("shared files: %.1f MB saved" % (saved / 1048576))


def main():
    if not os.path.isdir(os.path.join(KIT, "library")):
        sys.exit("no theme library at " + KIT + " (open the editor once so it fills the kit)")
    for name in ("library", "debloat"):
        target = os.path.join(ASSETS, name)
        shutil.rmtree(target, ignore_errors=True)
        shutil.copytree(os.path.join(KIT, name), target)
    shutil.copy2(os.path.join(KIT, "launcher.apk"), os.path.join(ASSETS, "launcher.apk"))
    dedupe(os.path.join(ASSETS, "library"))
    # Where the installer looks for a newer version of itself (Kit.SITES): the GitHub Pages site deploy.py uploads to,
    # from pages-site.json (git-ignored); site.txt is git-ignored too.
    site_file = os.path.join(HERE, "pages-site.json")
    sys.path.insert(0, HERE)
    from deploy import pages_sites
    sites = [site for site, _ in pages_sites(site_file)] if os.path.isfile(site_file) else []
    open(os.path.join(ASSETS, "site.txt"), "w", encoding="utf-8").write("\n".join(sites))
    size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(ASSETS) for f in fs)
    slots = sorted(n for n in os.listdir(os.path.join(ASSETS, "library")) if os.path.isdir(os.path.join(ASSETS, "library", n)))
    print("packed", ", ".join(slots), "-", round(size / 1048576, 1), "MB")


if __name__ == "__main__":
    main()
