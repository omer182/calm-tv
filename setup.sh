#!/usr/bin/env bash
# Installs Calm TV on an Android TV over the network and makes it the home screen.
#
#   ./setup.sh <tv-ip> [--config config/my-tv.json] [--apk calm-tv.apk] [--wake-on-lan]
#
# Before running: on the TV, Settings > Device Preferences > About > tap "Build" 7 times, then
# Developer options > turn on USB debugging (and Network debugging if listed). Accept the
# "Allow debugging?" prompt on the TV the first time.
#
# Without --apk the latest release is downloaded from GitHub. Same steps as setup.ps1.
set -euo pipefail
cd "$(dirname "$0")"

PKG=io.github.omer182.calmtv
TV=${1:?usage: ./setup.sh <tv-ip> [--config file] [--apk file] [--wake-on-lan]}; shift
[[ $TV == *:* ]] || TV="$TV:5555"
CONFIG=config/my-tv.json
APK=""
WOL=0
while [[ $# -gt 0 ]]; do
  case $1 in
    --config) CONFIG=$2; shift 2 ;;
    --apk) APK=$2; shift 2 ;;
    --wake-on-lan) WOL=1; shift ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

command -v adb >/dev/null || { echo "adb not found: install Android platform-tools" >&2; exit 1; }
step() { printf '\n== %s\n' "$*"; }
tv() { adb -s "$TV" "$@"; }

step "Connecting to $TV"
adb connect "$TV"
if [[ $(adb -s "$TV" get-state 2>&1) != device ]]; then
  echo "Not connected. Is debugging on, and did you accept the prompt on the TV?" >&2; exit 1
fi
echo "  $(tv shell getprop ro.product.manufacturer | tr -d '\r') $(tv shell getprop ro.product.model | tr -d '\r'), Android $(tv shell getprop ro.build.version.release | tr -d '\r')"

if [[ -z $APK ]]; then
  step "Downloading the latest release"
  APK=$(mktemp -t calm-tv.XXXXXX).apk
  curl -fsSL -o "$APK" https://github.com/omer182/calm-tv/releases/latest/download/calm-tv.apk
fi

step "Installing the app"
tv install -r "$APK"

if [[ -f $CONFIG ]]; then
  step "Copying settings from $CONFIG"
  tv push "$CONFIG" "/sdcard/Android/data/$PKG/files/calm.json"
else
  step "No settings file at $CONFIG (copy config/calm.example.json to start); using defaults"
fi

step "Making Calm TV the home screen"
tv shell cmd package set-home-activity "$PKG/.HomeActivity"
# Android TV keeps opening its own launcher while it is enabled. restore re-enables these.
for stock in com.google.android.tvlauncher com.google.android.apps.tv.launcherx com.google.android.leanbacklauncher; do
  if tv shell pm list packages "$stock" | tr -d '\r' | grep -qx "package:$stock"; then
    echo "  disabling $stock"; tv shell pm disable-user --user 0 "$stock" >/dev/null
  fi
done

step "Sleep-timer button, screen-off and screensaver"
tv shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS
# Clear then set: after an update Android only restarts the service when the setting changes.
tv shell settings delete secure enabled_accessibility_services >/dev/null
tv shell settings put secure enabled_accessibility_services "$PKG/$PKG.SleepService"
tv shell settings put secure accessibility_enabled 1
tv shell dpm set-active-admin "$PKG/.CalmAdmin" >/dev/null || true
tv shell settings put secure screensaver_components "$PKG/.CalmDream"
tv shell settings put secure screensaver_default_component "$PKG/.CalmDream"

if [[ $WOL == 1 ]]; then
  step "Wake-on-LAN (lets Home Assistant wake the TV for alarms)"
  tv shell settings put global network_wol 1
  tv shell settings put global network_wow 1
  mac=$(tv shell ip link show eth0 2>/dev/null | tr -d '\r' | awk '/link\/ether/ {print $2}')
  if [[ -n $mac ]]; then echo "  Ethernet MAC: $mac (use it in the Home Assistant package)"
  else echo "  No Ethernet MAC found: Wake-on-LAN usually needs the TV on a network cable"; fi
fi

tv shell input keyevent KEYCODE_HOME
step "Done. Press Home on the remote to see Calm TV."
