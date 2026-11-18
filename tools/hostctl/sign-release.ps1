# Run in the trusted local signing environment, never in Gradle or CI.
param(
    [Parameter(Mandatory)][string]$UnsignedApk,
    [Parameter(Mandatory)][ValidatePattern('^[0-9a-fA-F]{64}$')][string]$ExpectedUnsignedSha256,
    [Parameter(Mandatory)][string]$OutputApk,
    [Parameter(Mandatory)][string]$BuildTools,
    [Parameter(Mandatory)][string]$Java,
    [string]$Profile = 'rikka-bfac2440',
    [string]$SigningDirectory = (Join-Path $HOME '.rikkahub-plugin-host/signing')
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
Set-StrictMode -Version Latest
if (!$IsWindows) { throw 'This signer requires Windows DPAPI. Use a separate trusted signer on other platforms.' }

function Resolve-RegularFile([string]$path) {
    $item = Get-Item -LiteralPath $path
    if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw "Expected a regular file: $path"
    }
    return $item.FullName
}

function Read-Apk([string]$operation, [string]$path) {
    $extra = @()
    if ($operation -eq 'verify-apk') { $extra = @('--java', $javaPath, '--apksigner-jar', $jar) }
    $result = & node (Join-Path $PSScriptRoot 'cli.mjs') $operation --profile $Profile --apk $path --aapt2 $aapt @extra
    if ($LASTEXITCODE -ne 0) { throw "hostctl $operation rejected the APK" }
    return ($result -join "`n") | ConvertFrom-Json
}

$unsigned = Resolve-RegularFile $UnsignedApk
$aapt = Resolve-RegularFile (Join-Path $BuildTools 'aapt2.exe')
$zipalign = Resolve-RegularFile (Join-Path $BuildTools 'zipalign.exe')
$jar = Resolve-RegularFile (Join-Path $BuildTools 'lib/apksigner.jar')
$javaPath = Resolve-RegularFile $Java
$storeDir = Get-Item -LiteralPath $SigningDirectory
if (!$storeDir.PSIsContainer -or ($storeDir.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
    throw 'The signing directory must be a real directory'
}
$store = Resolve-RegularFile (Join-Path $storeDir.FullName 'release.p12')
$envelope = Resolve-RegularFile (Join-Path $storeDir.FullName 'password.dpapi')
$output = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
$parent = Split-Path -Parent $output
if (!(Test-Path -PathType Container -LiteralPath $parent) -or (Test-Path -LiteralPath $output)) {
    throw 'The output parent must exist and the output APK must not'
}

$stage = Join-Path $parent ('.apk-sign-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($stage)
try {
    # Work from a private copy; the expected digest is supplied from the trusted build record.
    $candidate = Join-Path $stage 'input.apk'
    [IO.File]::Copy($unsigned, $candidate)
    if ((Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash -ne $ExpectedUnsignedSha256) {
        throw 'Unsigned APK differs from the expected build digest'
    }
    $inputIdentity = Read-Apk 'inspect-unsigned-apk' $candidate
    if ($inputIdentity.apk.sha256 -ne $ExpectedUnsignedSha256) { throw 'Unsigned APK changed during inspection' }
    $aligned = Join-Path $stage 'aligned.apk'
    $signed = Join-Path $stage 'signed.apk'
    & $zipalign -P 16 -f 4 $candidate $aligned
    if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

    $password = ConvertTo-SecureString -String ([IO.File]::ReadAllText($envelope))
    try {
        $process = [Diagnostics.Process]::new()
        try {
            $start = $process.StartInfo
            $start.FileName = $javaPath
            $start.UseShellExecute = $false
            $start.RedirectStandardInput = $true
            foreach ($arg in @('-jar', $jar, 'sign', '--ks', $store, '--ks-type', 'PKCS12',
                '--ks-key-alias', 'rikkahub-plugin-host', '--ks-pass', 'stdin', '--key-pass', 'stdin',
                '--v4-signing-enabled', 'false', '--out', $signed, $aligned)) { $start.ArgumentList.Add($arg) }
            if (!$process.Start()) { throw 'Unable to start apksigner' }
            $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($password)
            try {
                $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
                $process.StandardInput.WriteLine($plain)
                $process.StandardInput.WriteLine($plain)
            } finally {
                $plain = $null
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
                $process.StandardInput.Close()
            }
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) { throw 'apksigner failed' }
        } finally { $process.Dispose() }
    } finally { $password.Dispose() }

    & $zipalign -c -P 16 4 $signed
    if ($LASTEXITCODE -ne 0) { throw 'Signed APK alignment check failed' }
    $verification = Read-Apk 'verify-apk' $signed
    if ($verification.productDigest -ne $inputIdentity.productDigest) { throw 'Product inputs changed during signing' }
    [IO.File]::Move($signed, $output)
    [pscustomobject]@{
        schemaVersion = 1
        productDigest = $verification.productDigest
        unsignedSha256 = $inputIdentity.apk.sha256
        apk = $verification.apk
        output = $output
    } | ConvertTo-Json -Depth 5
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force
}
