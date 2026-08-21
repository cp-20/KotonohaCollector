[CmdletBinding()]
param(
    [Parameter(Position = 0, Mandatory = $true)]
    [ValidateSet('enable', 'disable', 'status', 'export', 'clear')]
    [string]$Command,

    [Parameter(Position = 1)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$dataDirectory = Join-Path $env:LOCALAPPDATA 'KotonohaCollector'
$enabledPath = Join-Path $dataDirectory 'kotonoha-collector.enabled'
$eventPath = Join-Path $dataDirectory 'kotonoha-events.bin'

function Ensure-DataDirectory {
    New-Item -ItemType Directory -Path $dataDirectory -Force | Out-Null
}

function Export-Events([string]$Destination) {
    if (-not (Test-Path -LiteralPath $eventPath)) {
        throw "No collected events found: $eventPath"
    }
    if ([string]::IsNullOrWhiteSpace($Destination)) {
        $fileName = 'kotonoha-windows-events-{0}.jsonl' -f (Get-Date -Format 'yyyyMMdd-HHmmss')
        $Destination = Join-Path (Join-Path $HOME 'Downloads') $fileName
    }
    $Destination = [IO.Path]::GetFullPath($Destination)
    $destinationDirectory = Split-Path -Parent $Destination
    if ($destinationDirectory) {
        New-Item -ItemType Directory -Path $destinationDirectory -Force | Out-Null
    }

    $inputStream = [IO.File]::Open(
        $eventPath,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        [IO.FileShare]::ReadWrite
    )
    $reader = [IO.BinaryReader]::new($inputStream)
    $writer = [IO.StreamWriter]::new(
        $Destination,
        $false,
        [Text.UTF8Encoding]::new($false)
    )
    $exported = 0
    try {
        while ($inputStream.Position + 4 -le $inputStream.Length) {
            $length = $reader.ReadUInt32()
            if ($length -eq 0 -or $length -gt 16MB) {
                throw "Invalid event record (offset=$($inputStream.Position - 4), length=$length)"
            }
            $encrypted = $reader.ReadBytes($length)
            if ($encrypted.Length -ne $length) {
                Write-Warning 'Ignored an incomplete trailing record.'
                break
            }
            $plain = [Security.Cryptography.ProtectedData]::Unprotect(
                $encrypted,
                $null,
                [Security.Cryptography.DataProtectionScope]::CurrentUser
            )
            $json = [Text.Encoding]::UTF8.GetString($plain)
            $null = $json | ConvertFrom-Json
            $writer.WriteLine($json)
            $exported++
        }
    }
    finally {
        $writer.Dispose()
        $reader.Dispose()
        $inputStream.Dispose()
    }
    Write-Host "Exported $exported events: $Destination"
}

switch ($Command) {
    'enable' {
        Ensure-DataDirectory
        [IO.File]::WriteAllText($enabledPath, "enabled`n", [Text.UTF8Encoding]::new($false))
        Write-Host 'Collection enabled. It can take up to one second to apply.'
    }
    'disable' {
        if (Test-Path -LiteralPath $enabledPath) {
            Remove-Item -LiteralPath $enabledPath -Force
        }
        Start-Sleep -Milliseconds 1100
        Write-Host 'Collection disabled.'
    }
    'status' {
        $enabled = Test-Path -LiteralPath $enabledPath
        $size = if (Test-Path -LiteralPath $eventPath) {
            (Get-Item -LiteralPath $eventPath).Length
        } else {
            0
        }
        Write-Host ('Collection: {0}' -f $(if ($enabled) { 'ON' } else { 'OFF' }))
        Write-Host "Encrypted events: $eventPath ($size bytes)"
    }
    'export' {
        Export-Events $OutputPath
    }
    'clear' {
        if (Test-Path -LiteralPath $enabledPath) {
            throw 'Run disable before clearing collected events.'
        }
        if (Test-Path -LiteralPath $eventPath) {
            Remove-Item -LiteralPath $eventPath -Force
        }
        Write-Host 'Cleared collected events.'
    }
}
