# AGENTS.md: setting up Calm TV for someone

You are helping a person install Calm TV on their Android TV. Work in this order, ask before
anything you can't undo, and verify each step with a screenshot
(`adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png`).

## 0. Preconditions (check, don't assume)
- `adb` is installed on the computer (`adb version`). If not, ask before downloading
  platform-tools from dl.google.com.
- The TV runs Android TV / Google TV 8.0+ (`adb shell getprop ro.build.version.release`).
  Samsung, LG, Roku, Vizio and Apple TV cannot run it: stop and say so.
- Debugging is on and the person accepted the prompt: `adb connect <ip>:5555` then
  `adb -s <ip>:5555 get-state` prints `device`. If it prints `unauthorized`, ask them to accept
  the "Allow debugging?" prompt on the TV (tick "Always allow").

## 1. Interview (ask, never guess)
1. Names for the welcome ("Good evening, …"), or none.
2. City for the weather (look up lat/lon yourself).
3. Which apps they use, in order, for the dock. List installed TV apps with
   `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | grep packageName`.
4. Alarm: do they want one? Spotify playlist link, or the built-in tunes?
5. Do they run Home Assistant? If yes: its URL, which AC/thermostat and switches to show, and
   whether the TV is on a network cable (needed for Wake-on-LAN alarms).
6. Spare remote buttons: ask them to press each one while you watch
   `adb logcat | grep interceptKeyTq`. Use one the TV ignores for `sleepKey`; map app buttons
   (e.g. Netflix) under `buttons` if they stopped working.
7. Debloat: only if a list for their exact model exists in `debloat/`. Otherwise skip it, or
   build a list with them one package at a time.

## 2. Config
Copy `config/calm.example.json` to `config/my-tv.json` (git-ignored) and fill it in. Generate
random webhook ids (e.g. `calm_alarm_` + 12 random letters). Never commit this file.

## 3. Install
`./setup.sh <ip> [--wake-on-lan]` or `.\setup.ps1 -Tv <ip> [-WakeOnLan]`. Then press Home on the
TV (or `adb shell input keyevent KEYCODE_HOME`) and screenshot it.

## 4. Home Assistant (if wanted)
Edit `homeassistant/calm_tv.yaml`: webhook ids (same as calm.json), the TV's MAC (setup prints
it) and IP, the climate entity and switch entities. Show the person the diff and ask before
writing to their Home Assistant config or restarting it. Run Home Assistant's config check
before restarting.

## 5. Verify
- Home screen shows their names, weather and dock.
- Alarm: `adb shell am broadcast -n io.github.omer182.calmtv/.AlarmReceiver -a io.github.omer182.calmtv.RING_NOW`
  (warn them first: it plays sound).
- Sleep timer: `adb shell am broadcast -a io.github.omer182.calmtv.SLEEP_TEST` shows
  "Sleep in 30 min"; `--ei seconds -1` cancels. Real remote presses must be tested by the person.
- Button service running: `adb shell dumpsys accessibility` lists `Calm sleep timer`. If it
  doesn't after an update, a TV restart fixes it (ask first).

## Never
- Uninstall system packages (`pm uninstall`): only `pm disable-user`.
- Disable: system UI, settings, package installer, Play services, live TV / input services,
  remote pairing, Bluetooth, `com.mstar.netflixobserver` (Xiaomi Netflix button).
- Send the TV to standby, reboot it or play sound without asking.
- Put names, addresses, webhook ids or the signing key in the repo.

## Undo
`.\restore.ps1 -Tv <ip> [-Packages debloat/<list>.txt]` removes the app and turns the stock home
screen, Google's screensaver and any disabled packages back on.
