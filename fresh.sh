#!/bin/bash
# A round from zero, as on a TV that never had our launcher: launcher and installer removed, Google's home and setup
# app on (as a new Google TV has them), the installer installed and opened, the TV's "Allow USB debugging?" approved
# with the remote's keys, then the install followed to 100 and the setup screens done.
IP=${1:?ip}; N=${2:-1}
export MSYS_NO_PATHCONV=1
A="/t/ערכה-לחבר/מערכת/tools/adb/adb.exe"; S=$IP:5555; PY=/t/cleanyourtv/tools/venv/Scripts/python.exe
key() { "$A" -s $S shell input keyevent "$@" 2>/dev/null; }
focus() { "$A" -s $S shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus" 2>/dev/null | tr -d '\r'; }
"$A" -s $S shell 'pm uninstall com.gathin.tvhome; pm uninstall com.gathin.kit; pm enable com.google.android.apps.tv.launcherx; pm enable com.google.android.tungsten.setupwraith; input keyevent HOME' >/dev/null 2>&1
sleep 3
echo "  before: home=$("$A" -s $S shell 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1' | tr -d '\r') ours=$("$A" -s $S shell 'pm list packages com.gathin' | tr -d '\r' | tr '\n' ' ')"
"$A" -s $S install "$(cygpath -w /t/phone-kit/out/FormovieKit.apk)" >/dev/null
t0=$(date +%s)
"$A" -s $S shell "am start -n com.gathin.kit/.MainActivity" >/dev/null
# the TV's one-time "Allow USB debugging?": tick Always allow, then Allow (focus starts on the box; Allow is left of Cancel)
for i in $(seq 1 40); do sleep 1; case "$(focus)" in *UsbDebuggingActivity*) sleep 1; key DPAD_CENTER; sleep 0.5; key DPAD_DOWN; sleep 0.5; key DPAD_LEFT; sleep 0.5; key DPAD_CENTER; echo "  allowed after $(( $(date +%s) - t0 ))s"; break;; esac; done
/t/phone-kit/watch.sh $IP 300 | tail -2
t1=$(date +%s)
curl -s -m 5 http://$IP:8800/api/state | grep -q '"done":true' || { echo "ROUND $N (fresh): stopped"; exit 1; }
sleep 1.5
# name screen (empty): Down, Down to Continue
key DPAD_DOWN; key DPAD_DOWN; sleep 0.5; key DPAD_CENTER; sleep 1.5
# place screen (empty, the city box has the focus): Down to Search, Left to Continue
key DPAD_DOWN; sleep 0.4; key DPAD_LEFT; sleep 0.4; key DPAD_CENTER; sleep 1.5
# photos screen: Finish
key DPAD_CENTER
ok=""
for i in $(seq 1 40); do sleep 1; case "$(focus)" in *gathin.tvhome*) ok=1; break;; esac; done
t2=$(date +%s)
"$A" -s $S shell 'f=/sdcard/Android/data/com.gathin.tvhome/files; for t in $f $f/themes/*; do echo "  $(basename $t): walls=$(ls -1 $t/wallpapers 2>/dev/null | wc -l) name=$(grep -c "\"greeting_name\": \"[^\"]" $t/config.json)"; done | tr "\n" " "; echo; echo "  home=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1) helper=$(settings get secure enabled_accessibility_services)"' 2>/dev/null
curl -s -m 5 http://$IP:8800/api/state | $PY -I -c "import sys,json;sys.stdout.reconfigure(encoding='utf-8');d=json.loads(sys.stdin.buffer.read().decode('utf-8'));L=d['log'];w=[l for l in L if l['kind'] in ('warn','error') and 'היצרן' not in l['text']];print('  on TV: start',L[0]['t'],'done',[l['t'] for l in L if 'ההתקנה הסתיימה' in l['text']][0],'recoveries',len(w),'direct',any('ישירות' in l['text'] for l in L))"
echo "ROUND $N (fresh): open-to-100 $((t1-t0))s, setup+finish $((t2-t1))s, our home in front: ${ok:-NO}"
