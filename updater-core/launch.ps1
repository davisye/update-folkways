# Stable seed protocol v1. Install this script manually; it never replaces itself.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$GameDirectory,
    [string]$BootstrapJar,
    [string]$CompanionJar,
    [string]$Java = 'java',
    [switch]$Dedicated,
    [Parameter(Mandatory=$true)][string[]]$GameCommand
)
$ErrorActionPreference = 'Stop'
$game = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $GameDirectory).ProviderPath)
if (-not (Test-Path -LiteralPath $game -PathType Container)) { throw 'GameDirectory must exist.' }
$commandName = [IO.Path]::GetFileName($GameCommand[0]).ToLowerInvariant()
if ($commandName -ne 'java' -and $commandName -ne 'java.exe') { throw 'Seed v1 requires the foreground Java JVM command. Detached GUI launchers and shell wrappers are unsupported.' }
$updaterHome = Join-Path $game '.folkways-updater'
$bin = Join-Path $updaterHome 'bin\folkways-bootstrap.jar'
$runtime = Join-Path $updaterHome 'runtime'
function Assert-OwnPath([string]$path) {
    $current = [IO.Path]::GetFullPath($path)
    $prefix = $game.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $current.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Updater path escapes game directory.' }
    while ($current -ne $game) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Updater paths must not be symlinks or junctions.' }
        }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}
foreach ($path in @($updaterHome, (Split-Path -Parent $bin), $runtime, (Join-Path $updaterHome 'seed.lock'), $bin)) { Assert-OwnPath $path }
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bin), $runtime | Out-Null
$seedLock = $null
$oldProtocol = [Environment]::GetEnvironmentVariable('FOLKWAYS_SEED_PROTOCOL', 'Process')
$oldRestartCount = [Environment]::GetEnvironmentVariable('FOLKWAYS_SEED_RESTART_COUNT', 'Process')
$script:activeRuntime = $null
$script:runnerKnownExited = $false
function Remove-OwnRuntime {
    if (-not $script:activeRuntime) { return }
    Assert-OwnPath $script:activeRuntime
    if (-not [IO.Path]::GetFileName($script:activeRuntime).StartsWith('launch-')) { throw 'Invalid runtime cleanup target.' }
    $leasePath = Join-Path $script:activeRuntime 'owner.json'
    if (Test-Path -LiteralPath $leasePath) {
        $lease = Get-Content -LiteralPath $leasePath -Raw | ConvertFrom-Json
        $process = Get-Process -Id ([long]$lease.pid) -ErrorAction SilentlyContinue
        if ($process) { return }
    } elseif (-not $script:runnerKnownExited) {
        return
    }
    Remove-Item -LiteralPath $script:activeRuntime -Recurse -Force
    $script:activeRuntime = $null
}
function Invoke-Runner([string[]]$arguments) {
    $script:activeRuntime = Join-Path $runtime ('launch-' + [Guid]::NewGuid().ToString('N'))
    Assert-OwnPath $script:activeRuntime
    New-Item -ItemType Directory -Path $script:activeRuntime | Out-Null
    $runner = Join-Path $script:activeRuntime 'runner.jar'
    Copy-Item -LiteralPath $bin -Destination $runner
    $script:runnerKnownExited = $false
    & $Java '-jar' $runner @arguments | Out-Host
    $result = $LASTEXITCODE
    $script:runnerKnownExited = $true
    Remove-OwnRuntime
    return $result
}
try {
    $seedLock = [IO.File]::Open((Join-Path $updaterHome 'seed.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    if (-not (Test-Path -LiteralPath $bin -PathType Leaf)) {
        if (-not $BootstrapJar -or -not (Test-Path -LiteralPath $BootstrapJar -PathType Leaf)) { throw 'First installation requires -BootstrapJar pointing to the downloaded standalone JAR.' }
        $initial = Join-Path (Split-Path -Parent $bin) ('initial-' + [Guid]::NewGuid().ToString('N') + '.jar')
        Copy-Item -LiteralPath $BootstrapJar -Destination $initial
        Move-Item -LiteralPath $initial -Destination $bin
    }
    if ($CompanionJar -and -not (Test-Path -LiteralPath (Join-Path $updaterHome 'self-state.json'))) {
        $adoptResult = Invoke-Runner @('self-adopt', '--game-dir', $game, '--companion-jar', [IO.Path]::GetFullPath($CompanionJar))
        if ($adoptResult -ne 0) { throw 'Updater ownership setup failed.' }
    }
    [Environment]::SetEnvironmentVariable('FOLKWAYS_SEED_PROTOCOL', '1', 'Process')
    for ($bounce = 0; $bounce -le 50; $bounce++) {
        [Environment]::SetEnvironmentVariable('FOLKWAYS_SEED_RESTART_COUNT', [string]$bounce, 'Process')
        $prepareResult = Invoke-Runner @('self-prepare', '--game-dir', $game)
        if ($prepareResult -ne 0) { throw 'Updater launch gate refused replacement or recovery.' }
        $launchArguments = @('launch', '--game-dir', $game)
        if ($Dedicated) { $launchArguments += '--dedicated' }
        $launchArguments += '--'
        $launchArguments += $GameCommand
        $result = Invoke-Runner $launchArguments
        if ($result -ne 75) { exit $result }
    }
    throw 'Stable seed restart budget exhausted.'
} finally {
    # An active Java PID keeps its runtime; a later helper prunes only proven dead leases.
    if ($script:activeRuntime) { Remove-OwnRuntime }
    [Environment]::SetEnvironmentVariable('FOLKWAYS_SEED_PROTOCOL', $oldProtocol, 'Process')
    [Environment]::SetEnvironmentVariable('FOLKWAYS_SEED_RESTART_COUNT', $oldRestartCount, 'Process')
    if ($seedLock) { $seedLock.Dispose() }
}
