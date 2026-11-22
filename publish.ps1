# Rebuilds and publishes the TV installer after every commit (run by the git hooks, in the background; safe by hand):
# the launcher, the installer with it inside, online for the Downloader app and the installers' self-update
# (deploy.py writes version.json), and onto a plugged-in USB stick. One run at a time; the log is publish.log.
$ErrorActionPreference = 'Continue'
# The Python steps print Hebrew in UTF-8: read them as UTF-8, so publish.log stays readable.
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:PYTHONIOENCODING = 'utf-8'
$here = $PSScriptRoot
if (-not (Test-Path T:\)) { subst T: (Split-Path $here -Parent) }
$log = 'T:\phone-kit\publish.log'
$lock = 'T:\phone-kit\publish.lock'
# A commit made while a publish runs leaves this behind: the running publish then goes round once more.
$again = 'T:\phone-kit\publish.again'
function Say($text) {
    # The running build may hold the log for a moment: a few tries, and never a failure over a log line.
    for ($i = 0; $i -lt 5; $i++) {
        try { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $text" | Out-File $log -Append -Encoding utf8 -ErrorAction Stop; return } catch { Start-Sleep -Milliseconds 300 }
    }
}
if ((Test-Path $lock) -and ((Get-Date) - (Get-Item $lock).LastWriteTime).TotalMinutes -lt 20) {
    # Another run is going: it will pick up this commit too, unless it already started building; then run again after.
    Say 'busy: another publish is running; it will run once more for this commit'
    Set-Content -Path $again -Value (Get-Date)
    exit 0
}
Set-Content -Path $lock -Value (Get-Date)
try {
  do {
    # A minute's wait first: commits made close together (often several, in the three repositories) go up as one
    # upload, which saves the Netlify account's deploy credits.
    Start-Sleep -Seconds 60
    Remove-Item $again -Force -ErrorAction SilentlyContinue
    Say '=== publish'
    $env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\temurin17\jdk-17.0.20.1+1"
    $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
    $env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
    Push-Location T:\cleanyourtv\launcher
    # Up to 3 tries: OneDrive sometimes holds a build file for a moment (R8's classes.dex "used by another process").
    $launcherOk = $false
    for ($try = 1; $try -le 3 -and -not $launcherOk; $try++) {
        if ($try -gt 1) { Say "launcher build retry $try"; Start-Sleep -Seconds 10 }
        & .\gradlew.bat assembleRelease -q --console=plain *>&1 | Out-File $log -Append -Encoding utf8
        $launcherOk = $LASTEXITCODE -eq 0
    }
    Pop-Location
    if (-not $launcherOk) { throw 'the launcher did not build' }
    Copy-Item T:\cleanyourtv\launcher\app\build\outputs\apk\release\app-release.apk 'T:\ערכה-לחבר\מערכת\launcher.apk' -Force
    Say 'launcher built'
    & T:\phone-kit\build.ps1 *>&1 | Out-File $log -Append -Encoding utf8
    if (-not (Test-Path T:\phone-kit\out\FormovieKit.apk)) { throw 'the installer did not build' }
    Copy-Item T:\phone-kit\out\FormovieKit.apk T:\phone-kit\web\tv.apk -Force
    & T:\cleanyourtv\tools\venv\Scripts\python.exe -I T:\phone-kit\deploy.py *>&1 | Out-File $log -Append -Encoding utf8
    if ($LASTEXITCODE -eq 0) { Say 'online' } else { Say 'FAILED: the upload was refused (see the line above); the link keeps the last version' }
    # The install kit on this computer: the newest editor and wizard, and its one-file copy (ערכה-לחבר.zip).
    Copy-Item T:\wallpaper-editor\editor.html, T:\wallpaper-editor\editor.py, T:\wallpaper-editor\wizard.html, T:\wallpaper-editor\wizard.py 'T:\ערכה-לחבר\מערכת\editor\' -Force
    & T:\cleanyourtv\tools\venv\Scripts\python.exe -I T:\phone-kit\make_zip.py *>&1 | Out-File $log -Append -Encoding utf8
    Say 'install kit updated'
    # The TVs at home that have the launcher (wallpaper-editor\tvs.json): the new launcher and setup app, settings kept.
    & T:\cleanyourtv\tools\venv\Scripts\python.exe -I T:\phone-kit\update_tvs.py *>&1 | Out-File $log -Append -Encoding utf8
    Say 'TVs at home checked'
    # The source of all three projects to their private GitHub copies (the remote named github in each).
    foreach ($repo in 'T:\cleanyourtv', 'T:\wallpaper-editor', 'T:\phone-kit') {
        if (git -C $repo remote | Select-String -Quiet '^github$') {
            $env:GIT_TERMINAL_PROMPT = '0'
            # The launcher keeps its own branch name there: the original project's GitHub jobs only run on main.
            $branch = if ($repo -eq 'T:\cleanyourtv') { 'oran-projector' } else { 'main' }
            $out = git -C $repo push -q github ('HEAD:' + $branch) 2>&1
            if ($LASTEXITCODE -eq 0) { Say ('GitHub: ' + (Split-Path $repo -Leaf) + ' pushed') } else { Say ('GitHub: ' + (Split-Path $repo -Leaf) + ' FAILED: ' + $out) }
        }
    }
    $stick = Get-PSDrive -PSProvider FileSystem | Where-Object { $_.Name -notin @('C', 'T') -and (Test-Path ($_.Root + 'TV-Install.apk')) } | Select-Object -First 1
    if ($stick) {
        Copy-Item T:\phone-kit\out\FormovieKit.apk ($stick.Root + 'TV-Install.apk') -Force
        $kitOnStick = Join-Path $stick.Root 'ערכה-לחבר\מערכת'
        if (Test-Path $kitOnStick) {
            Copy-Item 'T:\ערכה-לחבר\מערכת\launcher.apk' (Join-Path $kitOnStick 'launcher.apk') -Force
            Copy-Item 'T:\ערכה-לחבר\מערכת\editor\*' (Join-Path $kitOnStick 'editor') -Recurse -Force
        }
        Copy-Item 'T:\ערכה-לחבר.zip' ($stick.Root + 'ערכה-לחבר.zip') -Force
        Say ('USB stick ' + $stick.Name + ': updated')
    }
    Say 'done'
  } while (Test-Path $again)
} catch {
    Say ('FAILED: ' + $_)
} finally {
    Remove-Item $lock -Force -ErrorAction SilentlyContinue
}
