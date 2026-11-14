#!/bin/bash
# Follows the installer on a TV through its own web page until it finishes, stops with an error, or LIMIT seconds pass.
IP=${1:?ip}; LIMIT=${2:-600}
PY=/t/cleanyourtv/tools/venv/Scripts/python.exe
start=$(date +%s); last=""
while [ $(( $(date +%s) - start )) -lt $LIMIT ]; do
  st=$(curl -s -m 5 http://$IP:8800/api/state)
  line=$(printf '%s' "$st" | $PY -I -c "import sys,json;sys.stdout.reconfigure(encoding='utf-8');d=json.loads(sys.stdin.buffer.read().decode('utf-8'));print(d['progress'],'RUN' if d['running'] else 'IDLE','DONE' if d['done'] else '','ERR:'+d['error'] if d['error'] else '',d['step'])" 2>/dev/null)
  [ -z "$line" ] && line="(no answer)"
  if [ "$line" != "$last" ]; then echo "$(( $(date +%s) - start ))s  $line"; last="$line"; fi
  case "$line" in *DONE*|*ERR:*) exit 0;; esac
  sleep 3
done
echo "LIMIT reached"; exit 1
