#!/bin/bash
# One full round on a TV: open the installer (it starts by itself), follow it to the end, then the setup screens with
# the remote's keys (Continue, Continue, Finish), and check name, place and photos in every theme.
IP=${1:?ip}; N=${2:-1}
export MSYS_NO_PATHCONV=1
A="/t/ערכה-לחבר/מערכת/tools/adb/adb.exe"; S=$IP:5555; PY=/t/cleanyourtv/tools/venv/Scripts/python.exe
key() { "$A" -s $S shell input keyevent "$@"; }
focus() { "$A" -s $S shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'; }
t0=$(date +%s)
"$A" -s $S shell "am force-stop com.gathin.kit; am start -n com.gathin.kit/.MainActivity" >/dev/null
sleep 2
# two photos, as the phone page sends them (kept by the installer, put on the TV at Finish)
if [ "$N" = 2 ]; then for f in "$TEMP/test-photo.jpg" "$TEMP/test-photo2.jpg"; do curl -s -m 60 -X POST -H 'Content-Type: image/jpeg' --data-binary @"$(cygpath -w "$f")" http://$IP:8800/api/photo >/dev/null; done; fi
/t/phone-kit/watch.sh $IP 420 || { echo "ROUND $N: install did not finish"; exit 1; }
t1=$(date +%s)
st=$(curl -s -m 5 http://$IP:8800/api/state)
echo "$st" | grep -q '"done":true' || { echo "ROUND $N: install stopped with an error"; exit 1; }
sleep 3
# name screen: down to Continue
key DPAD_DOWN; key DPAD_DOWN; sleep 0.5; key DPAD_CENTER; sleep 2
# place screen: Search (to the right of Continue), pick the first place, then Continue (it takes the focus)
key DPAD_RIGHT; sleep 0.5; key DPAD_CENTER; sleep 7; key DPAD_CENTER; sleep 1.5; key DPAD_CENTER; sleep 2
# photos screen: Finish has the focus
key DPAD_CENTER
ok=""
for i in $(seq 1 60); do
  sleep 2
  f=$(focus)
  case "$f" in *gathin.tvhome*|*launcherx*) ok=1; break;; esac
done
t2=$(date +%s)
"$A" -s $S shell 'f=/sdcard/Android/data/com.gathin.tvhome/files; n=0; for t in $f $f/themes/*; do p=$(ls -1 $t/wallpapers 2>/dev/null | grep -c photo); a=$(grep -c "\"location_label\": \"[^\"]" $t/config.json); g=$(grep -c "\"greeting_name\": \"[^\"]" $t/config.json); echo "  $(basename $t): photos=$p place=$a name=$g"; done; cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1; settings get secure enabled_accessibility_services; settings get secure screensaver_components'
echo "$(curl -s -m 5 http://$IP:8800/api/state)" | $PY -I -c "import sys,json;sys.stdout.reconfigure(encoding='utf-8');d=json.loads(sys.stdin.buffer.read().decode('utf-8'));[print('  log',l['t'],l['text'][:80]) for l in d['log'][-3:]]" 2>/dev/null
echo "ROUND $N: install $((t1-t0))s, setup $((t2-t1))s, home in front: ${ok:-NO} ($(focus | grep -o 'u0 [^ /]*'))"
