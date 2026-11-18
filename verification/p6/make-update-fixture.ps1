param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [int]$VersionCode = 2,
    [string]$AppId = 'io.github.mapleluvr.rikkahub.debug',
    [string]$Sdk = "$env:LOCALAPPDATA/Android/Sdk",
    [string]$DebugKeystore = "$env:USERPROFILE/.android/debug.keystore"
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
Set-StrictMode -Version Latest
if ($VersionCode -le 1 -or $AppId -notmatch '^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+\.debug$') {
    throw 'The fixture must target a .debug package with a version above the test baseline'
}
$tools = Join-Path $Sdk 'build-tools/36.1.0'
$android = Join-Path $Sdk 'platforms/android-36/android.jar'
$store = (Resolve-Path -LiteralPath $DebugKeystore).Path
$out = (New-Item -ItemType Directory -Path $OutputDirectory).FullName
$manifest = Join-Path $out 'AndroidManifest.xml'
$xml = @"
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$AppId"
    android:versionCode="$VersionCode"
    android:versionName="3.3.0-fixture.$VersionCode">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
    <application android:label="Host update test fixture" android:hasCode="false" />
</manifest>
"@
[IO.File]::WriteAllText($manifest, $xml)
$unsigned = Join-Path $out 'unsigned.apk'
$signed = Join-Path $out 'signed.apk'
& "$tools/aapt2.exe" link -I $android --manifest $manifest -o $unsigned
# Android Studio's standard debug test key and password.
& "$tools/apksigner.bat" sign --ks $store --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $signed $unsigned
& "$tools/apksigner.bat" verify --verbose $signed
