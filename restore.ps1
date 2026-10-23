# Puts the TV back the way it was before Calm TV: removes the app, re-enables the stock home
# screen and Google's screensaver, and (with -Packages) re-enables apps a debloat list disabled.
#
#   .\restore.ps1 -Tv 192.168.1.50 [-Packages debloat\xiaomi-mitv.txt]
param(
    [Parameter(Mandatory)][string]$Tv,
    [string]$Packages = ''
)
$ErrorActionPreference = 'Continue'
$pkg = 'io.github.omer182.calmtv'
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $HOME 'platform-tools/adb.exe' }
function Tv { & $adb -s $Tv @args }

& $adb connect $Tv | Out-Null
foreach ($stock in 'com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher') {
    Tv shell pm enable $stock 2>&1 | Out-Null
}
Tv shell settings put secure screensaver_components com.google.android.backdrop/.Backdrop
Tv shell settings delete secure enabled_accessibility_services
Tv shell dpm remove-active-admin "$pkg/.CalmAdmin" 2>&1 | Out-Null
Tv uninstall $pkg
if ($Packages -and (Test-Path $Packages)) {
    Get-Content $Packages | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
        $p = ($_ -split '\s+')[0]; "$p -> " + (Tv shell pm enable $p)
    }
}
Tv shell input keyevent KEYCODE_HOME
'Restored. The TV is back on its own home screen.'
