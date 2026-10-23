# Builds the Calm TV launcher without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Runs on Windows PowerShell 5.1 and PowerShell 7 (Windows, macOS, Linux; the GitHub workflow
# uses it too). Output: build/calm-tv.apk. Install it with ../setup.ps1 or ../setup.sh.
#
# Needs JDK 17 and the Android SDK (build-tools 34.0.0, platforms;android-34):
#   ANDROID_HOME   SDK folder (default: ~/android-sdk)
#   JAVA_HOME      JDK folder (default: <sdk>/jdk if it exists, else java on PATH)
# Signing (optional; without it a local debug key is created next to this script):
#   CALM_KEYSTORE, CALM_KEYSTORE_PASSWORD, CALM_KEY_ALIAS (default "calm-tv")
param([string]$Version = '1.0.0', [int]$VersionCode = 1)

# Native tools write warnings to stderr; failures are caught by exit code in Run.
$ErrorActionPreference = 'Continue'
$onWindows = [IO.Path]::DirectorySeparatorChar -eq '\'
$exe = if ($onWindows) { '.exe' } else { '' }
$bat = if ($onWindows) { '.bat' } else { '' }

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $HOME 'android-sdk' }
$bt = Join-Path $sdk 'build-tools/34.0.0'
$jar = Join-Path $sdk 'platforms/android-34/android.jar'
if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $sdk 'jdk'))) { $env:JAVA_HOME = Join-Path $sdk 'jdk' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
function Jdk($tool) { if ($java) { Join-Path $java "$tool$exe" } else { $tool } }
if ($env:JAVA_HOME) { $env:PATH = "$java$([IO.Path]::PathSeparator)$env:PATH" }

$out = Join-Path $PSScriptRoot 'build'
function Run($tool) { & $tool @args; if ($LASTEXITCODE) { throw "$tool failed ($LASTEXITCODE)" } }

foreach ($need in "$bt/aapt2$exe", $jar) {
    if (-not (Test-Path $need)) { throw "Missing $need - install build-tools;34.0.0 and platforms;android-34" }
}

Remove-Item $out -Recurse -Force -ErrorAction SilentlyContinue
foreach ($d in 'gen', 'classes', 'dex') { New-Item -ItemType Directory -Force (Join-Path $out $d) | Out-Null }

Run "$bt/aapt2$exe" compile --dir (Join-Path $PSScriptRoot 'res') -o "$out/res.zip"
Run "$bt/aapt2$exe" link -I $jar --manifest (Join-Path $PSScriptRoot 'AndroidManifest.xml') `
    --min-sdk-version 26 --target-sdk-version 28 --version-code $VersionCode --version-name $Version `
    -A (Join-Path $PSScriptRoot 'assets') --java "$out/gen" -o "$out/unaligned.apk" "$out/res.zip"

$sources = Get-ChildItem (Join-Path $PSScriptRoot 'src'), "$out/gen" -Recurse -Filter *.java | ForEach-Object FullName
Run (Jdk javac) --release 8 -encoding UTF-8 -classpath $jar -nowarn -d "$out/classes" @sources

$classes = Get-ChildItem "$out/classes" -Recurse -Filter *.class | ForEach-Object FullName
Run "$bt/d8$bat" --release --min-api 26 --lib $jar --output "$out/dex" @classes

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::Open("$out/unaligned.apk", 'Update')
[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$out/dex/classes.dex", 'classes.dex')
$zip.Dispose()

Run "$bt/zipalign$exe" -f 4 "$out/unaligned.apk" "$out/aligned.apk"

$ks = $env:CALM_KEYSTORE
$pass = $env:CALM_KEYSTORE_PASSWORD
$alias = if ($env:CALM_KEY_ALIAS) { $env:CALM_KEY_ALIAS } else { 'calm-tv' }
if (-not $ks) {
    # Local builds without a release key: a throwaway debug key, kept so updates install over each other.
    $ks = Join-Path $PSScriptRoot 'debug.keystore'
    $pass = 'android'
    $alias = 'calm-tv'
    if (-not (Test-Path $ks)) {
        Run (Jdk keytool) -genkeypair -keystore $ks -alias $alias -keyalg RSA -keysize 2048 -validity 10000 `
            -storepass $pass -keypass $pass -dname 'CN=Calm TV debug'
    }
}
Run "$bt/apksigner$bat" sign --ks $ks --ks-key-alias $alias --ks-pass "pass:$pass" --key-pass "pass:$pass" `
    --out "$out/calm-tv.apk" "$out/aligned.apk"
"Built $out/calm-tv.apk ($([math]::Round((Get-Item "$out/calm-tv.apk").Length / 1MB, 1)) MB, v$Version)"
