# Disables (does not uninstall) every package in a list, e.g. the ad/tracking apps of a TV model.
#   .\debloat\debloat.ps1 -Tv 192.168.1.50 -Packages debloat\xiaomi-mitv-mssp3.txt
# Undo with: .\restore.ps1 -Tv 192.168.1.50 -Packages <same list>
# Only use a list made for your exact model; see debloat/README.md for making your own.
param(
    [Parameter(Mandatory)][string]$Tv,
    [Parameter(Mandatory)][string]$Packages
)
$ErrorActionPreference = 'Continue'
if ($Tv -notmatch ':') { $Tv = "${Tv}:5555" }
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $HOME 'platform-tools/adb.exe' }

& $adb connect $Tv | Out-Null
$installed = (& $adb -s $Tv shell pm list packages) -replace '^package:', '' | ForEach-Object { $_.Trim() }
Get-Content $Packages | Where-Object { $_ -and $_ -notmatch '^\s*#' } | ForEach-Object {
    $p = ($_ -split '\s+')[0]
    if ($installed -notcontains $p) { "$p -> not on this TV, skipped"; return }
    "$p -> " + (& $adb -s $Tv shell pm disable-user --user 0 $p)
}
