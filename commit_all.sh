#!/bin/sh
# "commit": every project with changes (the launcher, the editor, the setup app) is committed with the same message;
# the commits' hooks then run publish.ps1 once (it waits a minute to gather them), which updates everything
# everywhere: the download link and the setup app's self-update (GitHub Pages), the install kit and its zip, the USB
# stick, the TVs at home, and the source on GitHub. Its log is phone-kit/publish.log.
#
#     sh commit_all.sh "what changed"
msg="$1"
[ -n "$msg" ] || { echo 'usage: sh commit_all.sh "what changed"'; exit 2; }
trailer="

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
root=$(cd "$(dirname "$0")/.." && pwd)
# The T: drive (subst) can disappear; the real folder works the same (publish.ps1 maps T: again by itself).
[ -d "$root/cleanyourtv" ] || root="/c/Users/oranh/OneDrive/Desktop/מודים כי אני גאון/MAKREN420"
committed=0
for repo in cleanyourtv wallpaper-editor phone-kit; do
    dir="$root/$repo"
    if [ -n "$(git -C "$dir" status --porcelain)" ]; then
        git -C "$dir" add -A
        git -C "$dir" -c user.name="Oran Hazani" -c user.email="oranhazani@gmail.com" commit -q -m "$msg$trailer" \
            && echo "$repo: $(git -C "$dir" log --oneline -1)" && committed=1
    else
        echo "$repo: nothing to commit"
    fi
done
if [ "$committed" = 0 ]; then
    # Nothing new, but "commit" still means: make sure everything everywhere has the newest build.
    cmd.exe //c start "" //min powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden \
        -File "$(cygpath -w "$root/phone-kit/publish.ps1")" >/dev/null 2>&1
    echo "no changes: publishing the current build everywhere anyway"
fi
echo "publishing in the background; follow phone-kit/publish.log"
