# SPDX-License-Identifier: GPL-3.0-or-later
param([switch]$Check, [switch]$Release)

$ErrorActionPreference = 'Stop'
$taskPreviousJava = $env:JAVA_HOME
$taskPreviousTemp = $env:TEMP
$taskPreviousTmp = $env:TMP
$taskPreviousJavaOptions = $env:JAVA_TOOL_OPTIONS
$taskJbr = Join-Path $env:ProgramFiles 'Android/Android Studio/jbr'
$taskTemp = Join-Path $env:USERPROFILE 'owtmp'

try {
    if (Test-Path -LiteralPath (Join-Path $taskJbr 'bin/java.exe')) { $env:JAVA_HOME = $taskJbr }
    New-Item -ItemType Directory -Path $taskTemp -Force | Out-Null
    $env:TEMP = $taskTemp
    $env:TMP = $taskTemp
    $env:JAVA_TOOL_OPTIONS = ($taskPreviousJavaOptions + ' "-Djava.io.tmpdir=' + $taskTemp + '"').Trim()
    Push-Location -LiteralPath $PSScriptRoot
    try {
        $taskVariant = if ($Release) { 'Release' } else { 'Debug' }
        $taskGradleArguments = @(":app:assemble$taskVariant", '--console=plain')
        if ($Check) {
            $taskGradleArguments += @(':app:testDebugUnitTest', ":app:lint$taskVariant")
            if ($Release) { $taskGradleArguments += ':app:compileDebugKotlin' }
        }
        & './gradlew.bat' @taskGradleArguments
        if ($LASTEXITCODE -ne 0) { throw 'The Android build failed. See the diagnostics above.' }
        if ($Check) { & (Join-Path $PSScriptRoot 'design/widget-validation/check.ps1') }
        $taskDist = Join-Path $PSScriptRoot 'dist'
        New-Item -ItemType Directory -Path $taskDist -Force | Out-Null
        $taskMetadata = Get-Content -LiteralPath (Join-Path $PSScriptRoot "app/build/outputs/apk/$($taskVariant.ToLower())/output-metadata.json") -Raw | ConvertFrom-Json
        $taskApk = $taskMetadata.elements[0]
        $taskSuffix = if ($Release) { 'unsigned' } else { 'debug' }
        $taskDestination = Join-Path $taskDist "obviouslyweather-$($taskApk.versionName)-arm64-$taskSuffix.apk"
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "app/build/outputs/apk/$($taskVariant.ToLower())/$($taskApk.outputFile)") -Destination $taskDestination -Force
        Write-Output ('APK ready: ' + $taskDestination)
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $taskPreviousJava
    $env:TEMP = $taskPreviousTemp
    $env:TMP = $taskPreviousTmp
    $env:JAVA_TOOL_OPTIONS = $taskPreviousJavaOptions
}
