"""After every commit (publish.ps1): the TVs at home that already have our launcher get the new one, and the setup
app too where it is installed. The TVs are the ones in wallpaper-editor/tvs.json; a TV that is off or not answering
is skipped (the next commit, or the setup app's own update, catches it up). A TV already on this build is left
alone. adb install -r keeps every setting, theme and photo on the TV.

    python update_tvs.py
"""
import hashlib
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ADB = os.path.join(ROOT, "ערכה-לחבר", "מערכת", "tools", "adb", "adb.exe")
TVS = os.path.join(ROOT, "wallpaper-editor", "tvs.json")
# The shell watcher for holding a volume key (the launcher's VolumeHold; the same line as the setup app's
# Kit.VOLUME_WATCHER): restarted on every run, so a held key repeats quickly even after a reboot or a new launcher.
WATCHER = ("p=/data/local/tmp/tvhome-volhold.pid; [ -f $p ] && kill -9 -$(cat $p) 2>/dev/null; "
           "setsid nohup sh -c 'echo $$ > /data/local/tmp/tvhome-volhold.pid; while true; do getevent -ql 2>/dev/null"
           " | nc 127.0.0.1 8897 >/dev/null 2>&1; sleep 3; done' >/dev/null 2>&1 </dev/null & sleep 1")
APPS = [("com.gathin.tvhome", os.path.join(ROOT, "ערכה-לחבר", "מערכת", "launcher.apk"), "מסך הבית"),
        ("com.gathin.kit", os.path.join(HERE, "out", "FormovieKit.apk"), "אפליקציית ההתקנה")]


def adb(*args, timeout=30):
    r = subprocess.run([ADB, *args], capture_output=True, text=True, timeout=timeout,
                       env=dict(os.environ, MSYS_NO_PATHCONV="1"))
    return (r.stdout + r.stderr).strip()


def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    hosts = []
    for tv in json.load(open(TVS, encoding="utf-8")):
        host = tv.get("host", "")
        if host and host not in hosts:
            hosts.append(host)
    # skip-tvs.txt (git-ignored), one host per line: TVs left as they are, e.g. one kept on an older version to test
    # that it brings itself up to date.
    skip_file = os.path.join(HERE, "skip-tvs.txt")
    skip = set(open(skip_file, encoding="utf-8").read().split()) if os.path.isfile(skip_file) else set()
    for host in hosts:
        if host in skip:
            print(host + ": ברשימת הדילוג (skip-tvs.txt), לא נגעתי")
            continue
        try:
            if "connected" not in adb("connect", host, timeout=8) or "device" not in adb("-s", host, "get-state", timeout=8):
                print(host + ": לא עונה, דילוג")
                continue
            for package, apk, label in APPS:
                if not os.path.isfile(apk):
                    continue
                path = adb("-s", host, "shell", "pm path " + package).replace("package:", "").strip().splitlines()
                if not path:
                    continue   # not on this TV: the setup app installs it there, not this
                if sha(apk) in adb("-s", host, "shell", "sha256sum " + path[0]):
                    print("%s: %s - כבר בגרסה האחרונה" % (host, label))
                    continue
                result = adb("-s", host, "install", "-r", "-g", apk, timeout=240)
                print("%s: %s %s" % (host, label, "עודכן" if "Success" in result else "נכשל: " + result[-200:]))
            if adb("-s", host, "shell", "pm path com.gathin.tvhome").startswith("package:"):
                adb("-s", host, "shell", WATCHER, timeout=20)
                print("%s: החזקת ווליום מהירה פעילה" % host)
        except subprocess.TimeoutExpired:
            print(host + ": לא ענה בזמן, דילוג")
    return 0


if __name__ == "__main__":
    sys.exit(main())
