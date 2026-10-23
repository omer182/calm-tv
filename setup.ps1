# Installs Calm TV on an Android TV over the network and makes it the home screen.
#
#   .\setup.ps1 -Tv 192.168.1.50 [-Config config\my-tv.json] [-Apk calm-tv.apk] [-WakeOnLan]
#
# Before running: on the TV, Settings > Device Preferences > About > tap "Build" 7 times, then
# Developer options > turn on USB debugging (and Network debugging if listed). Accept the
# "Allow debugging?" prompt on the TV the first time.
#
# Without -Apk the latest release is downloaded from GitHub. Undo everything with .\restore.ps1.
param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Config = (Join-Path $PSScriptRoot 'config/my-tv.json'),
    [string]$Apk = '',
    [switch]$WakeOnLan
)
$ErrorActionPreference = 'Continue'
$pkg = 'io.github.omer182.calmtv'
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    foreach ($p in (Join-Path $HOME 'platform-tools/adb.exe'), (Join-Path $HOME 'platform-tools/adb')) { if (Test-Path $p) { $adb = $p } }
}
if (-not $adb) { throw 'adb not found: install Android platform-tools and put adb on PATH' }

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Tv { & $adb -s $Tv @args }

Step "Connecting to $Tv"
& $adb connect $Tv | Write-Host
$state = (& $adb -s $Tv get-state 2>&1) -join ''
if ($state -notmatch 'device') {
    throw "Not connected ($state). Is debugging on, and did you accept the prompt on the TV?"
}
"  $((Tv shell getprop ro.product.manufacturer).Trim()) $((Tv shell getprop ro.product.model).Trim()), Android $((Tv shell getprop ro.build.version.release).Trim())"

if (-not $Apk) {
    Step 'Downloading the latest release'
    $Apk = Join-Path ([IO.Path]::GetTempPath()) 'calm-tv.apk'
    Invoke-WebRequest 'https://github.com/omer182/calm-tv/releases/latest/download/calm-tv.apk' -OutFile $Apk -UseBasicParsing
}

Step 'Installing the app'
Tv install -r $Apk | Write-Host

if (Test-Path $Config) {
    Step "Copying settings from $Config"
    Tv push $Config "/sdcard/Android/data/$pkg/files/calm.json" | Write-Host
} else {
    Step "No settings file at $Config (copy config/calm.example.json to start); using defaults"
}

Step 'Making Calm TV the home screen'
Tv shell cmd package set-home-activity "$pkg/.HomeActivity" | Write-Host
# Android TV keeps opening its own launcher while it is enabled. Restore re-enables these.
foreach ($stock in 'com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher') {
    if ((Tv shell pm list packages $stock) -match "package:$stock$") {
        "  disabling $stock"; Tv shell pm disable-user --user 0 $stock | Out-Null
    }
}

Step 'Sleep-timer button, screen-off and screensaver'
# Lets the app switch its sleep-button service back on after updates.
Tv shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
# Clear then set: after an update Android only restarts the service when the setting changes.
Tv shell settings delete secure enabled_accessibility_services | Out-Null
Tv shell settings put secure enabled_accessibility_services "$pkg/$pkg.SleepService"
Tv shell settings put secure accessibility_enabled 1
Tv shell dpm set-active-admin "$pkg/.CalmAdmin" | Out-Null
Tv shell settings put secure screensaver_components "$pkg/.CalmDream"
Tv shell settings put secure screensaver_default_component "$pkg/.CalmDream"

if ($WakeOnLan) {
    Step 'Wake-on-LAN (lets Home Assistant wake the TV for alarms)'
    Tv shell settings put global network_wol 1
    Tv shell settings put global network_wow 1
    $mac = (Tv shell ip link show eth0 | Select-String 'link/ether ([0-9a-f:]+)').Matches.Groups[1].Value
    if ($mac) { "  Ethernet MAC: $mac (use it in the Home Assistant package)" }
    else { '  No Ethernet MAC found: Wake-on-LAN usually needs the TV on a network cable' }
}

Tv shell input keyevent KEYCODE_HOME
Step 'Done. Press Home on the remote to see Calm TV.'
