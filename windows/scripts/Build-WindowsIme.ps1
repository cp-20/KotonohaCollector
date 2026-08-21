[CmdletBinding()]
param(
    [string]$BuildDirectory,
    [switch]$SkipDependencies,
    [switch]$SkipQt
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$prepareScript = Join-Path $PSScriptRoot 'Prepare-Mozc.ps1'
$BuildDirectory = & $prepareScript -BuildDirectory $BuildDirectory | Select-Object -Last 1
$sourceDirectory = Join-Path $BuildDirectory 'src'

Push-Location $sourceDirectory
try {
    if (-not $SkipDependencies) {
        & python build_tools/update_deps.py
        if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch Mozc dependencies.' }
    }
    if (-not $SkipQt) {
        & python build_tools/build_qt.py --release --confirm_license
        if ($LASTEXITCODE -ne 0) { throw 'Failed to build Qt.' }
    }
    & bazelisk test //kotonoha:event_builder_test --config release_build --copt=/DKOTONOHA_COLLECTOR_BUILD
    if ($LASTEXITCODE -ne 0) { throw 'Windows collector tests failed.' }
    & bazelisk build package --config release_build --copt=/DKOTONOHA_COLLECTOR_BUILD
    if ($LASTEXITCODE -ne 0) { throw 'Windows IME build failed.' }
}
finally {
    Pop-Location
}

$outputDirectory = Join-Path $repositoryRoot 'windows\dist'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
$sourceMsi = Join-Path $sourceDirectory 'bazel-bin\win32\installer\Mozc64.msi'
$destinationMsi = Join-Path $outputDirectory 'KotonohaCollector-Windows-x64.msi'
Copy-Item -LiteralPath $sourceMsi -Destination $destinationMsi -Force
Write-Host "Built: $destinationMsi"
