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
import re
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


def pack_version():
    """The pack's version, the number both apps are built with (their build.gradle counts the same way): every commit
    of phone-kit and wallpaper-editor plus the launcher's own since the original project. None without git."""
    work = os.path.dirname(HERE)
    try:
        def count(repo, rng):
            return int(subprocess.run(["git", "-C", os.path.join(work, repo), "rev-list", "--count", rng],
                                      check=True, capture_output=True, text=True).stdout.strip())
        return count("cleanyourtv", "origin/main..HEAD") + count("phone-kit", "HEAD") + count("wallpaper-editor", "HEAD")
    except Exception:
        return None


def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], check=True, capture_output=True, text=True).stdout


def pages_sites(path):
    """Every site to upload to: pages-site.json's "repo" (the one the installers on TVs already know), then its
    "also" list, e.g. ["0rannnn.github.io"] for the short address USER.github.io. Each is (site, remote)."""
    cfg = json.load(open(path, encoding="utf-8"))
    user = cfg["user"].strip()
    out = []
    for repo in [cfg.get("repo")] + list(cfg.get("also", [])):
        one = dict(cfg, repo=repo) if repo else dict(cfg)
        tmp = os.path.join(HERE, ".site-tmp.json")
        json.dump(one, open(tmp, "w", encoding="utf-8"))
        try:
            out.append(pages_site(tmp))
        finally:
            os.remove(tmp)
    return out


def exists(remote):
    """Whether the repository is there yet (an "also" one may not have been created)."""
    r = subprocess.run(["git", "ls-remote", remote], capture_output=True, text=True,
                       env=dict(os.environ, GIT_TERMINAL_PROMPT="0"))
    return r.returncode == 0


def upload(site, remote, repo_dir, digest, version, link):
    """One site: index.html (showing link), tv.apk, version.json and /i, as one fresh commit pushed over the last."""
    try:
        online = json.loads(urllib.request.urlopen(site + "/version.json?t=%d" % time.time(), timeout=15)
                            .read().decode("utf-8"))
        if online.get("sha256") == digest and online.get("link") == link:
            print("unchanged:", site)
            return True
    except Exception:
        pass
    if not os.path.isdir(os.path.join(repo_dir, ".git")):
        os.makedirs(repo_dir, exist_ok=True)
        subprocess.run(["git", "init", "-q", "-b", "main", repo_dir], check=True)
        subprocess.run(["git", "-C", repo_dir, "remote", "add", "origin", remote], check=True)
    run = lambda *a: subprocess.run(["git", "-C", repo_dir, *a], check=True, capture_output=True, text=True).stdout
    run("remote", "set-url", "origin", remote)
    for name in os.listdir(repo_dir):
        if name != ".git":
            path = os.path.join(repo_dir, name)
            shutil.rmtree(path) if os.path.isdir(path) else os.remove(path)
    page = open(os.path.join(WEB, "index.html"), encoding="utf-8").read()
    page = re.sub(r'(<div class="link">)[^<]*(</div>)', lambda m: m.group(1) + link + m.group(2), page)
    open(os.path.join(repo_dir, "index.html"), "w", encoding="utf-8", newline="\n").write(page)
    shutil.copy2(os.path.join(WEB, "tv.apk"), os.path.join(repo_dir, "tv.apk"))
    json.dump(dict(version, link=link), open(os.path.join(repo_dir, "version.json"), "w", encoding="utf-8"))
    os.makedirs(os.path.join(repo_dir, "i"))
    open(os.path.join(repo_dir, "i", "index.html"), "w", encoding="utf-8").write(REDIRECT)
    open(os.path.join(repo_dir, ".nojekyll"), "w").close()
    run("checkout", "-q", "--orphan", "next")
    run("add", "-A")
    run("-c", "user.name=TV installer", "-c", "user.email=tv-installer@users.noreply.github.com",
        "commit", "-q", "-m", "Installer " + version["built"])
    subprocess.run(["git", "-C", repo_dir, "branch", "-D", "main"], capture_output=True)
    run("branch", "-m", "main")
    try:
        run("push", "-q", "-f", "origin", "main")
    except subprocess.CalledProcessError as e:
        print("github refused " + site + ": " + (e.stderr or "").strip()[:300])
        return False
    print("deployed:", site, "(GitHub Pages; live within a minute or two)")
    return True


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if not os.path.isfile(SITE_FILE):
        print('no host: save {"user": "<GitHub username>"} in ' + SITE_FILE)
        return 2
    apk = os.path.join(WEB, "tv.apk")
    digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()
    version = {"sha256": digest, "size": os.path.getsize(apk), "built": time.strftime("%Y-%m-%d %H:%M")}
    number = pack_version()
    if number is not None:
        version["version"] = number   # the installers show it, and say when the TV already has the newest
    json.dump(version, open(os.path.join(WEB, "version.json"), "w", encoding="utf-8"))
    sites = pages_sites(SITE_FILE)
    live = [(site, remote) for i, (site, remote) in enumerate(sites) if i == 0 or exists(remote)]
    for site, remote in sites:
        if (site, remote) not in live:
            print("not created yet, skipped:", remote)
    # The page shows the shortest address that works (USER.github.io once that repository exists).
    link = min((site.split("://", 1)[1] for site, _ in live), key=len)
    ok = True
    for i, (site, remote) in enumerate(live):
        folder = REPO if i == 0 else REPO + "-" + re.sub(r"[^a-z0-9]+", "-", remote.rsplit("/", 1)[1].lower())
        ok = upload(site, remote, folder, digest, version, link) and ok
    print("installer:", "https://" + link)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
