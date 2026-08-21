[CmdletBinding()]
param(
    [string]$BuildDirectory
)

$ErrorActionPreference = 'Stop'
$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($BuildDirectory)) {
    $BuildDirectory = Join-Path $repositoryRoot 'windows\.build\mozc'
}
$BuildDirectory = [IO.Path]::GetFullPath($BuildDirectory)
$mozcRevision = '851c3fe33060d2a6090363e4d7ec44fafde2c03d'
$patchPath = Join-Path $repositoryRoot 'windows\patches\kotonoha-mozc.patch'
$overlayPath = Join-Path $repositoryRoot 'windows\mozc_overlay\kotonoha'
$normalizedPatchPath = Join-Path ([IO.Path]::GetTempPath()) 'kotonoha-mozc-lf.patch'

if (-not (Test-Path -LiteralPath (Join-Path $BuildDirectory '.git'))) {
    New-Item -ItemType Directory -Path (Split-Path -Parent $BuildDirectory) -Force | Out-Null
    & git clone https://github.com/google/mozc.git $BuildDirectory
    if ($LASTEXITCODE -ne 0) { throw 'Failed to clone Mozc.' }
}

& git -C $BuildDirectory config core.autocrlf false
if ($LASTEXITCODE -ne 0) { throw 'Failed to configure Mozc line endings.' }
& git -C $BuildDirectory fetch origin $mozcRevision --depth=1
if ($LASTEXITCODE -ne 0) { throw 'Failed to fetch the pinned Mozc revision.' }
& git -C $BuildDirectory reset --hard $mozcRevision
if ($LASTEXITCODE -ne 0) { throw 'Failed to reset the Mozc worktree.' }
& git -C $BuildDirectory checkout-index --all --force
if ($LASTEXITCODE -ne 0) { throw 'Failed to materialize Mozc with LF line endings.' }
& git -C $BuildDirectory clean -fdx
if ($LASTEXITCODE -ne 0) { throw 'Failed to clean the Mozc worktree.' }

$destination = Join-Path $BuildDirectory 'src\kotonoha'
New-Item -ItemType Directory -Path $destination -Force | Out-Null
Copy-Item -Path (Join-Path $overlayPath '*') -Destination $destination -Recurse -Force

[IO.File]::WriteAllText(
    $normalizedPatchPath,
    [IO.File]::ReadAllText($patchPath).Replace("`r`n", "`n"),
    [Text.UTF8Encoding]::new($false)
)
try {
    & git -C $BuildDirectory apply --check $normalizedPatchPath
    if ($LASTEXITCODE -ne 0) { throw 'The Kotonoha patch does not apply to the pinned Mozc revision.' }
    & git -C $BuildDirectory apply $normalizedPatchPath
    if ($LASTEXITCODE -ne 0) { throw 'Failed to apply the Kotonoha patch.' }
}
finally {
    Remove-Item -LiteralPath $normalizedPatchPath -Force -ErrorAction SilentlyContinue
}

Write-Host "Prepared Mozc: $BuildDirectory"
Write-Output $BuildDirectory
