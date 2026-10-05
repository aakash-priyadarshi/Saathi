param(
    [Parameter(Mandatory = $true)][string]$Model,
    [ValidateSet('debug', 'staging', 'release')][string]$BuildType = 'debug',
    [switch]$ReverseDevelopmentApi
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$taskSdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$taskAdb = Join-Path $taskSdk 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $taskAdb)) { throw 'Android SDK platform-tools/adb.exe is required.' }
if ($ReverseDevelopmentApi -and $BuildType -ne 'debug') { throw 'USB development API forwarding is only for debug builds.' }
$taskMatches = @()
foreach ($taskRow in (& $taskAdb devices)) {
    if ($taskRow -match '^(\S+)\s+device\s*$') {
        $taskDevice = $Matches[1]
        $taskModel = (& $taskAdb -s $taskDevice shell getprop ro.product.model | Out-String).Trim()
        if ($taskModel -eq $Model) { $taskMatches += $taskDevice }
    }
}
if ($taskMatches.Count -ne 1) { throw "Exactly one authorized '$Model' must be connected; found $($taskMatches.Count)." }
$taskApk = Join-Path $taskRoot "apps\android\app\build\outputs\apk\$BuildType\app-$BuildType.apk"
if (-not (Test-Path -LiteralPath $taskApk)) { throw 'Build the selected APK before installing.' }
& $taskAdb -s $taskMatches[0] install -r $taskApk
if ($LASTEXITCODE -ne 0) { throw 'Android refused this update. Check signing/version compatibility; private work was not cleared.' }
if ($ReverseDevelopmentApi) {
    & $taskAdb -s $taskMatches[0] reverse tcp:4000 tcp:4000
    if ($LASTEXITCODE -ne 0) { throw 'APK installed, but development API forwarding failed.' }
}
Write-Output "Installed Saathi $BuildType on the authorized $Model."
