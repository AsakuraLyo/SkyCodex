# Copy the working files that live outside this repo into backup/ so they are versioned with the mod.
#   powershell -ExecutionPolicy Bypass -File backup\sync.ps1
# Restore after a reinstall: see backup\RESTORE.md
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

$sources = @(
    @{ From = "$env:USERPROFILE\.claude\projects\E--MC\memory"; To = 'claude-memory'; Include = @('*.md') },
    @{ From = 'E:\MC\dev\scratch';                              To = 'scratch';       Include = @('*.mjs', '*.json', '*.ps1') },
    @{ From = 'E:\MC\SkyCodex\memory';                          To = 'server-memory'; Include = @('*.json', '*.md') }
)
foreach ($s in $sources) {
    $dest = Join-Path $here $s.To
    if (-not (Test-Path $s.From)) { Write-Host "skip (missing): $($s.From)"; continue }
    if (Test-Path $dest) { Remove-Item $dest -Recurse -Force }
    New-Item -ItemType Directory -Force $dest | Out-Null
    foreach ($pat in $s.Include) {
        Get-ChildItem $s.From -Filter $pat -File | Where-Object { $_.Name -notmatch 'connection|token|secret' } |
            Copy-Item -Destination $dest
    }
    Write-Host ("{0,-14} <- {1} ({2} files)" -f $s.To, $s.From, (Get-ChildItem $dest -File).Count)
}
