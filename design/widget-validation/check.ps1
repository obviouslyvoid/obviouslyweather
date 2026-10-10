# SPDX-License-Identifier: GPL-3.0-or-later
param([string]$StudioRoot = (Join-Path $env:ProgramFiles 'Android/Android Studio'), [string]$Font)
$ErrorActionPreference = 'Stop'
$taskProject = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$taskCp = @(
    $PSScriptRoot
    (Join-Path $taskProject 'app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes')
    (Join-Path $StudioRoot 'plugins/design-tools/lib/layoutlib.jar')
    (Join-Path $StudioRoot 'plugins/design-tools/resources/layoutlib/data/layoutlib-extensions.jar')
    (Join-Path $StudioRoot 'plugins/android/lib/*')
    (Join-Path $StudioRoot 'plugins/Kotlin/kotlinc/lib/*')
    (Join-Path $StudioRoot 'lib/*')
) -join ';'
$taskFontArguments = @()
if ($Font) {
    $taskSamples = Join-Path $taskProject "dist/imported-font-validation"
    New-Item -ItemType Directory -Path $taskSamples -Force | Out-Null
    $taskFontArguments += @("-Dwidget.font=$([IO.Path]::GetFullPath($Font))", "-Dwidget.samples=$taskSamples")
}
Push-Location -LiteralPath $taskProject
try {
    & (Join-Path $StudioRoot 'jbr/bin/javac.exe') -cp $taskCp (Join-Path $PSScriptRoot 'NativeForecastCheck.java')
    if ($LASTEXITCODE -ne 0) { throw 'The native widget validation harness did not compile.' }
    & (Join-Path $StudioRoot 'jbr/bin/java.exe') '--enable-native-access=ALL-UNNAMED' '-Djava.awt.headless=true' "-Dwidget.layoutlib.data=$(Join-Path $StudioRoot 'plugins/design-tools/resources/layoutlib/data')" @taskFontArguments -cp $taskCp NativeForecastCheck
    if ($LASTEXITCODE -ne 0) { throw 'The rendered widget pixels did not pass alignment checks.' }
} finally { Pop-Location }
