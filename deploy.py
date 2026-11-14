"""Puts the TV installer online on GitHub Pages (https://USER.github.io), so a TV downloads it with the Downloader
app and no stick or computer: phone-kit/web (index.html, tv.apk) plus version.json, which the installers on TVs
compare themselves with (Kit.updateIfNewer), and /i, a page that sends Downloader straight to tv.apk.

USER and REPO are read from pages-site.json beside this file ({"user": "...", "repo": "..."}; git-ignored, so no
account name is committed); the site is USER.github.io/REPO (or USER.github.io for a repo of that name).
The pages live in phone-kit/pages-repo (git-ignored), a clone of that repository. Every upload is
one fresh commit pushed over the last (force), so the repository never grows past one copy of the installer. Git's
own credential manager signs the push in; nothing secret is read or printed here.

Netlify (tv-i.netlify.app) was the host until its free credits ran out on 2026-11-14; it keeps the last version
it had and is no longer updated.

    python deploy.py
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")
REPO = os.path.join(HERE, "pages-repo")
SITE_FILE = os.path.join(HERE, "pages-site.json")

# /i: Downloader opens the page and follows it to the installer (GitHub Pages has no server-side redirects).
REDIRECT = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="robots" content="noindex">
<meta http-equiv="refresh" content="0; url=../tv.apk"><title>tv.apk</title></head>
<body><script>location.replace('../tv.apk')</script><a href="../tv.apk">tv.apk</a></body></html>
"""


def pages_site(path):
    """(the site's address, the repository's address) from pages-site.json."""
    cfg = json.load(open(path, encoding="utf-8"))
    user = cfg["user"].strip()
    repo = (cfg.get("repo") or user.lower() + ".github.io").strip()
    site = "https://%s.github.io" % user.lower() + ("" if repo.lower() == user.lower() + ".github.io" else "/" + repo)
    return site, "https://github.com/%s/%s.git" % (user, repo)


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], check=True, capture_output=True, text=True).stdout


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if not os.path.isfile(SITE_FILE):
        print('no host: save {"user": "<GitHub username>"} in ' + SITE_FILE)
        return 2
    site, remote = pages_site(SITE_FILE)
    apk = os.path.join(WEB, "tv.apk")
    digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    try:
        online = json.loads(urllib.request.urlopen(site + "/version.json?t=%d" % time.time(), timeout=15)
                            .read().decode("utf-8"))
        if online.get("sha256") == digest:
            print("unchanged: this installer is already online")
            return 0
    except Exception:
        pass
    version = {"sha256": digest, "size": os.path.getsize(apk), "built": time.strftime("%Y-%m-%d %H:%M")}
    json.dump(version, open(os.path.join(WEB, "version.json"), "w", encoding="utf-8"))

    if not os.path.isdir(os.path.join(REPO, ".git")):
        os.makedirs(REPO, exist_ok=True)
        subprocess.run(["git", "init", "-q", "-b", "main", REPO], check=True)
        git("remote", "add", "origin", remote)
    git("remote", "set-url", "origin", remote)   # pages-site.json may name another repository than last time
    for name in os.listdir(REPO):
        if name != ".git":
            path = os.path.join(REPO, name)
            shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
    for name in ("index.html", "tv.apk", "version.json"):
        shutil.copy2(os.path.join(WEB, name), os.path.join(REPO, name))
    os.makedirs(os.path.join(REPO, "i"))
    open(os.path.join(REPO, "i", "index.html"), "w", encoding="utf-8").write(REDIRECT)
    open(os.path.join(REPO, ".nojekyll"), "w").close()

    git("checkout", "-q", "--orphan", "next")
    git("add", "-A")
    git("-c", "user.name=TV installer", "-c", "user.email=tv-installer@users.noreply.github.com",
        "commit", "-q", "-m", "Installer " + version["built"])
    subprocess.run(["git", "-C", REPO, "branch", "-D", "main"], capture_output=True)
    git("branch", "-m", "main")
    try:
        git("push", "-q", "-f", "origin", "main")
    except subprocess.CalledProcessError as e:
        print("github refused: " + (e.stderr or "").strip()[:300])
        return 1
    print("deployed:", site, "(GitHub Pages; live within a minute or two)")
    print("installer:", site + "/i")
    return 0


if __name__ == "__main__":
    sys.exit(main())
