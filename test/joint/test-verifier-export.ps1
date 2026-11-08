$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../bin/e2e-process.ps1')
$folder = Join-Path ([IO.Path]::GetTempPath()) ('novalink-verifier-' + [guid]::NewGuid().ToString('N'))
try {
    & (Join-Path $PSScriptRoot 'export-verifier.ps1') -Destination $folder
    $entry = Join-Path $folder 'test/joint'
    if (Test-Path (Join-Path $entry 'run-joint.ps1')) { throw 'Verifier still ships the runner' }
    . (Join-Path $entry 'evidence-checks.ps1')
    if (-not (Assert-ProxyPluginLoaded 'plugin' 'velocity' 'NovaChat Velocity plugin enabled').pass) { throw 'Plugin receipt rejected' }
    if ((Assert-ProxyPluginLoaded 'plugin' 'velocity' 'server started').pass) { throw 'Missing plugin accepted' }
    $auth = "Client authenticated: A from /127.0.0.1:1`nClient authenticated: B from /127.0.0.1:2"
    if (-not (Assert-RelayDistinctIdentities 'auth' $auth @('A','B') @('fallback')).pass) { throw 'Distinct identities rejected' }
    if ((Assert-RelayDistinctIdentities 'auth' $auth @('missing') @()).pass) { throw 'Missing identity accepted' }
    if ((Assert-RelayDistinctIdentities 'auth' $auth @('A') @('B')).pass) { throw 'Fallback identity accepted' }
    if (-not (Assert-RelayLinksCreated 'links' 'Relay backend links: 2 connection(s) (bukkit, fabric)' @('bukkit','fabric')).pass) { throw 'Dedicated links rejected' }
    if ((Assert-RelayLinksCreated 'links' 'Relay backend links: 1 connection(s) (bukkit)' @('bukkit','fabric')).pass) { throw 'Missing link accepted' }
    $raw = Join-Path $entry 'assert-joint-raw-evidence.ps1'
    $run = Start-E2EProcess -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$raw`"",
        '-RoundDir', "`"$folder`"", '-RunId', 'offline-fixture', '-Mode', 'relay', '-Proxy', 'velocity', '-Bedrock', 'pnx', '-Groups', 'core') `
        -WorkingDirectory $folder -Stdout (Join-Path $folder 'out.log') -Stderr (Join-Path $folder 'err.log')
    $result = Wait-E2EProcess $run 15
    if ($result.ExitCode -eq 0 -or (Get-Content $result.Stderr -Raw) -notmatch 'Missing raw evidence') { throw 'Export did not reject missing raw evidence' }
    Write-Host 'PASS relocated verifier: pure scorers and missing-raw rejection; no server evidence produced'
} finally {
    if ($run -and -not $run.Process.HasExited) { $run.Process.Kill(); $run.Process.WaitForExit() }
    $resolved = [IO.Path]::GetFullPath($folder)
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if ((Split-Path -Parent $resolved) -ne $parent -or (Split-Path -Leaf $resolved) -notmatch '^novalink-verifier-[0-9a-f]{32}$') { throw 'Unexpected cleanup path' }
    if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
