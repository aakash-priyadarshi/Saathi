param([ValidateSet('debug', 'staging', 'release')][string]$BuildType = 'debug', [string]$TrustDirectory, [switch]$Instrument)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if ($TrustDirectory) {
    $taskTrust = (Resolve-Path -LiteralPath $TrustDirectory).Path
    $taskConfig = Get-Content -Raw -LiteralPath (Join-Path $taskTrust 'service-config.json') | ConvertFrom-Json
    $env:SAATHI_CONFIG_ROOT_PUBLIC_JWK = (Get-Content -Raw -LiteralPath (Join-Path $taskTrust 'configuration-root.public.json')).Trim()
    $env:SAATHI_CONFIG_BOOTSTRAP = $taskConfig.body.apiEndpoints[0].TrimEnd('/') + '/api/v1/sync/service-config'
}
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }
Push-Location (Join-Path $taskRoot 'apps\android')
try {
    $taskVariant = (Get-Culture).TextInfo.ToTitleCase($BuildType)
    $gradleTasks = @(":app:assemble$taskVariant", ":app:lint$taskVariant", ':app:testDebugUnitTest')
    if ($BuildType -eq 'debug') { $gradleTasks += ':app:assembleDebugAndroidTest' }
    if ($Instrument -and $BuildType -ne 'debug') {
        throw 'Instrumented tests currently target the development debug build; staging has its own product build and unit tests.'
    }
    & .\gradlew.bat @gradleTasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Android validation failed.' }
    if ($Instrument) {
        & .\gradlew.bat ":app:connected${taskVariant}AndroidTest" --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'Android device tests failed.' }
    }
} finally { Pop-Location }
