"""Puts the TV installer online: phone-kit/web (index.html, tv.apk, _headers) onto a Netlify site, so a TV downloads it
with the Downloader app and no stick or computer. The token is read from netlify-token.txt beside the project folders
(never printed, never committed); the site's id is kept in web-site.json (git-ignored) so later runs update it.

    python deploy.py            first run: makes a site with a short random name, then uploads
"""
import io
import json
import os
import secrets
import sys
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(HERE, "web")
SITE_FILE = os.path.join(HERE, "web-site.json")
TOKEN_FILE = os.path.join(os.path.dirname(HERE), "netlify-token.txt")
API = "https://api.netlify.com/api/v1"


def call(method, path, token, data=None, content_type="application/json"):
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("User-Agent", "tv-installer-deploy")
    if data is not None:
        req.add_header("Content-Type", content_type)
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read().decode("utf-8") or "{}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if not os.path.isfile(TOKEN_FILE):
        print("no token: save a Netlify personal access token in " + TOKEN_FILE)
        return 2
    token = open(TOKEN_FILE, encoding="utf-8").read().strip()
    site = json.load(open(SITE_FILE, encoding="utf-8")) if os.path.isfile(SITE_FILE) else None
    try:
        if not site:
            for _ in range(5):
                name = "tvhome-" + secrets.token_hex(2)
                try:
                    made = call("POST", "/sites", token, json.dumps({"name": name}).encode())
                    break
                except urllib.error.HTTPError as e:
                    if e.code != 422:   # 422: the name is taken, try another
                        raise
            site = {"id": made["id"], "url": made.get("ssl_url") or made.get("url")}
            json.dump(site, open(SITE_FILE, "w", encoding="utf-8"), indent=2)
        # version.json: what the installers on TVs compare themselves with (Kit.updateIfNewer).
        import hashlib, time
        apk = os.path.join(WEB, "tv.apk")
        digest = hashlib.sha256(open(apk, "rb").read()).hexdigest()
        # Every deploy costs the Netlify account credits: an installer that is already online is not sent again.
        try:
            online = json.loads(urllib.request.urlopen(site["url"].rstrip("/") + "/version.json?t=%d" % time.time(),
                                                       timeout=15).read().decode("utf-8"))
            if online.get("sha256") == digest:
                print("unchanged: this installer is already online")
                return 0
        except Exception:
            pass
        json.dump({"sha256": digest, "size": os.path.getsize(apk), "built": time.strftime("%Y-%m-%d %H:%M")},
                  open(os.path.join(WEB, "version.json"), "w", encoding="utf-8"))
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            for name in os.listdir(WEB):
                z.write(os.path.join(WEB, name), name)
        deploy = call("POST", "/sites/%s/deploys" % site["id"], token, buf.getvalue(), "application/zip")
    except urllib.error.HTTPError as e:
        print("netlify refused: %s %s" % (e.code, e.read().decode("utf-8", "replace")[:200]))
        return 1
    print("deployed:", site["url"], "state:", deploy.get("state"))
    print("installer:", site["url"].rstrip("/") + "/tv.apk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
