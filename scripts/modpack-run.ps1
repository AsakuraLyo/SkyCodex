# Copies a modpack instance (read-only) into run-modpack so the dev client (gradlew runModpack) loads the whole pack
# with an offline test account and a local test world. The original instance is never modified.
#   powershell -File scripts\modpack-run.ps1 "E:\MC\.minecraft\versions\<instance>"
param([Parameter(Mandatory = $true)][string]$Instance)
$ErrorActionPreference = 'Stop'
$dest = Join-Path $PSScriptRoot '..\run-modpack'
if (-not (Test-Path (Join-Path $Instance 'mods'))) { throw "No mods folder in $Instance" }
New-Item -ItemType Directory -Force $dest | Out-Null
# Skip worlds, logs, shaders, caches and launcher files; the dev client brings its own NeoForge, SkyCodex and Baritone.
$skipDirs = @('saves', 'logs', 'crash-reports', 'screenshots', 'shaderpacks', '.cache', 'downloads', 'PCL', '.mixin.out')
Get-ChildItem $Instance -Directory | Where-Object { $skipDirs -notcontains $_.Name } | ForEach-Object {
    robocopy $_.FullName (Join-Path $dest $_.Name) /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "robocopy failed for $($_.Name)" }
}
# Never load a second copy of SkyCodex/Baritone from the pack; the dev classpath provides them.
Get-ChildItem (Join-Path $dest 'mods') -File | Where-Object { $_.Name -match 'skycodex|baritone' } | Remove-Item
Write-Output "Copied $((Get-ChildItem (Join-Path $dest 'mods') -File).Count) mod files to $dest"
