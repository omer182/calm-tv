# Debloat lists

Optional. Each file lists packages to **disable** (never uninstall) on one TV model: ads,
tracking, auto-updaters and preinstalled extras. Disabling is fully reversible:

```bash
pwsh ./debloat/debloat.ps1 -Tv 192.168.1.50 -Packages debloat/xiaomi-mitv-mssp3.txt
pwsh ./restore.ps1        -Tv 192.168.1.50 -Packages debloat/xiaomi-mitv-mssp3.txt
```

| List | Model | Android | Status |
|---|---|---|---|
| [xiaomi-mitv-mssp3.txt](xiaomi-mitv-mssp3.txt) | Xiaomi Mi TV (MiTV-MSSP3) | 9 | Tested, daily use |

## Making a list for your TV

1. `adb shell pm list packages -f` and look for names with `analytics`, `statistic`, `ads`,
   `recommend`, `update`, `partner`, `feedback`, or the vendor's own launcher/home app.
2. Disable **one** package, use the TV for a day (inputs, live TV, casting, remote buttons),
   then add it to your list.
3. Open a pull request with the list and your model in the table above.

**Never disable** the system UI, settings, the package installer, input/live-TV services,
remote pairing, Bluetooth, or Google Play services. On Xiaomi TVs also keep
`com.mstar.netflixobserver`: it handles the remote's Netflix button.
