param([int]$MaxTries = 10)
$ErrorActionPreference = 'Continue'
$src = 'E:\MC\dev\SkyCodex-0.1.0-beta.2\source'
Set-Location $src
$env:JAVA_HOME = 'E:\MC\graalvm-jdk-21.0.10+8.1'; $env:Path = "$env:JAVA_HOME\bin;$env:Path"
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Find-ModJar([string]$modId) {
    foreach ($jar in Get-ChildItem run-modpack\mods -File -Filter *.jar) {
        try {
            $z = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
            $e = $z.GetEntry('META-INF/neoforge.mods.toml'); if (-not $e) { $e = $z.GetEntry('fabric.mod.json') }
            if ($e) { $r = New-Object IO.StreamReader($e.Open()); $txt = $r.ReadToEnd(); $r.Close()
                if ($txt -match "modId\s*=\s*`"$modId`"" -or $txt -match "`"id`"\s*:\s*`"$modId`"") { $z.Dispose(); return $jar } }
            $z.Dispose()
        } catch {}
    }
    return $null
}
for ($try = 1; $try -le $MaxTries; $try++) {
    Remove-Item run-modpack\config\skycodex-connection.json -ErrorAction SilentlyContinue
    $log = "E:\MC\dev\scratch\modpack-try$try.log"
    $p = Start-Process -FilePath "$src\gradlew.bat" -ArgumentList 'runModpack', '--no-daemon' -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru -WindowStyle Hidden
    $end = (Get-Date).AddMinutes(15)
    $inWorld = { (Test-Path run-modpack\logs\latest.log) -and (Select-String -Path run-modpack\logs\latest.log -Pattern 'SkyCodex \u5DF2\u542F\u7528' -Quiet -Encoding utf8) }
    Start-Sleep 20
    while ((Get-Date) -lt $end -and -not $p.HasExited -and -not (& $inWorld)) { Start-Sleep 5 }
    if (& $inWorld) { "TRY $try : in world, control enabled"; while (-not $p.HasExited) { Start-Sleep 5 }; "game exited"; exit 0 }
    if (-not $p.HasExited) { "TRY $try : not in world after 15 min (process still running)"; exit 2 }
    $latest = Get-Content run-modpack\logs\latest.log -ErrorAction SilentlyContinue
    $fatal = $latest | Select-String 'Mixin apply for mod (\S+) failed' | Select-Object -First 1
    if (-not $fatal) {
        "TRY $try : exited without a mixin failure; see run-modpack\logs\latest.log and crash-reports"
        $latest | Select-String '/FATAL\]|/ERROR\]' | Select-Object -Last 8 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(400, $_.Line.Length)) }
        exit 1
    }
    $mod = $fatal.Matches[0].Groups[1].Value
    $jar = Find-ModJar $mod
    if (-not $jar) { "TRY $try : mixin failure in $mod but jar not found"; exit 1 }
    Move-Item $jar.FullName run-modpack\mods-disabled\ -Force
    "TRY $try : disabled $mod ($($jar.Name)) -- $($fatal.Line.Substring(0,[Math]::Min(300,$fatal.Line.Length)))"
}
"gave up after $MaxTries tries"; exit 3
