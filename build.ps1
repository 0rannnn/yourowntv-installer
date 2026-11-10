# Builds the phone kit's projector app: packs the editor's current themes and the newest launcher (pack.py), builds the
# app, and copies it to phone-kit\out\FormovieKit.apk (and into the install kit, for a projector reached from a PC).
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$env:JAVA_HOME = "$env:LOCALAPPDATA\Programs\temurin17\jdk-17.0.20.1+1"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
# Gradle cannot take the Hebrew in the project's path: build from the T: drive that points at MAKREN420.
if (-not (Test-Path T:\)) { subst T: (Split-Path $here -Parent) }
$py = 'T:\ערכה-לחבר\מערכת\tools\python\python.exe'
& $py -I T:\phone-kit\pack.py
if ($LASTEXITCODE) { throw 'pack failed' }
Set-Location T:\phone-kit\installer
$ErrorActionPreference = 'Continue'
.\gradlew.bat --no-daemon -q assembleRelease 2>&1 | Select-String -Pattern 'error|\.java:|FAIL|What went wrong' | Select-Object -First 25
if ($LASTEXITCODE) { throw 'build failed' }
New-Item -ItemType Directory -Force T:\phone-kit\out | Out-Null
Copy-Item T:\phone-kit\installer\app\build\outputs\apk\release\app-release.apk T:\phone-kit\out\FormovieKit.apk -Force
"built: T:\phone-kit\out\FormovieKit.apk  {0:N1} MB" -f ((Get-Item T:\phone-kit\out\FormovieKit.apk).Length / 1MB)
