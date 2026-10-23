# Takes the README screenshots from a real TV running Calm TV with a demo config.
#   .\tools\screenshots.ps1 -Tv 192.168.1.50 [-Out docs\screenshots]
# Wipes the app's data first (dock order, alarm) so the demo dock applies; rerun setup with
# your own config afterwards.
param([Parameter(Mandatory)][string]$Tv, [string]$Out = (Join-Path $PSScriptRoot '../docs/screenshots'),
      [string]$Config = (Join-Path $PSScriptRoot '../config/calm.example.json'))
$ErrorActionPreference = 'Continue'
$pkg = 'io.github.omer182.calmtv'
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $HOME 'platform-tools/adb.exe' }
function Tv { & $adb -s $Tv @args }
function Key($k, $n = 1) { for ($i = 0; $i -lt $n; $i++) { Tv shell input keyevent $k; Start-Sleep -Milliseconds 350 } }
function Shot($name) {
    Tv shell screencap -p /sdcard/shot.png
    Tv pull /sdcard/shot.png (Join-Path $Out "$name.png") | Out-Null
    "  $name"
}
New-Item -ItemType Directory -Force $Out | Out-Null

Tv shell pm clear $pkg | Out-Null
Tv push $Config "/sdcard/Android/data/$pkg/files/calm.json" | Out-Null
Tv shell settings put secure enabled_accessibility_services "$pkg/$pkg.SleepService"
Tv shell settings put secure accessibility_enabled 1
Tv shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS
# A soft tune instead of Spotify, so the ringing screen stays up for the photo.
Tv shell am broadcast -n "$pkg/.AlarmReceiver" -a "$pkg.SET_ALARM" --ei hour 7 --ei minute 30 --es repeat sun_thu --ei sound 0 --ez enabled true | Out-Null

Tv shell am force-stop $pkg
Key KEYCODE_HOME; Start-Sleep 2; Shot '01-welcome'
# Demo dock: row 1 Netflix YouTube Plex Spotify, row 2 Home Alarm AllApps Settings, row 3 Twitch.
Start-Sleep 8; Shot '02-home'
Key KEYCODE_DPAD_DOWN; Start-Sleep 1.5; Shot '03-all-cards'
Key KEYCODE_DPAD_CENTER; Start-Sleep 4; Shot '04-home-control'
Key KEYCODE_BACK; Start-Sleep 1.5; Key KEYCODE_DPAD_RIGHT; Key KEYCODE_DPAD_CENTER; Start-Sleep 2; Shot '05-alarm-setup'
Key KEYCODE_BACK; Start-Sleep 1.5; Key KEYCODE_DPAD_RIGHT; Key KEYCODE_DPAD_CENTER; Start-Sleep 2; Shot '06-all-apps'
Key KEYCODE_BACK; Start-Sleep 1.5; Key KEYCODE_DPAD_UP; Start-Sleep 1.5
Tv shell am broadcast -a "$pkg.SLEEP_TEST" | Out-Null; Start-Sleep 1; Shot '07-sleep-timer'
Tv shell am broadcast -a "$pkg.SLEEP_TEST" --ei seconds -1 | Out-Null
Tv shell am broadcast -n "$pkg/.AlarmReceiver" -a "$pkg.RING_NOW" | Out-Null; Start-Sleep 3; Shot '08-alarm-ringing'
Key KEYCODE_DPAD_CENTER; Start-Sleep 2
Tv shell am start -n com.android.systemui/.Somnambulator | Out-Null; Start-Sleep 5; Shot '09-screensaver'
Key KEYCODE_BACK; Key KEYCODE_HOME
"Saved to $Out"
