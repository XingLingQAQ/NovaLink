[CmdletBinding()]
param([string]$OutputRoot = '')

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'native-runtime.ps1')
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('.e2e-artifacts/bedrock-transfer-runtime-check-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'Use a new isolated runtime check directory.' }
$null = New-Item -ItemType Directory -Path $OutputRoot
$utf8 = [Text.UTF8Encoding]::new($false)
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
foreach ($file in @('run-bot.js', 'assert-evidence.js')) {
    & node --check (Join-Path $PSScriptRoot $file)
    if ($LASTEXITCODE -ne 0) { throw "JavaScript parse failed: $file" }
}
$config = Get-PnxLoopbackConfig -Port 19362 -Node a
foreach ($pattern in @("ip: '127\.0\.0\.1'", 'port: 19362', 'signalingPort: 19362', 'udpPort: 19362',
    'signalingMode: builtin', 'stun-servers: \[\]', 'ice-servers: \[\]', "advertise-addresses: \['127\.0\.0\.1'\]",
    'installSpark: false', 'enableMetrics: false')) {
    if ($config -notmatch $pattern) { throw "Missing native loopback setting: $pattern" }
}
[IO.File]::WriteAllText((Join-Path $OutputRoot 'pnx.yml'), $config, $utf8)
$readyLog = @'
This server is running PowerNukkitX version 3.0.6-SNAPSHOT (git-c574993) (API 3.0.6)
NovaChat-PNX enabled successfully!
Enabling BedrockTransferFixture v1.0
NetherNet signalling listening on tcp/19362
'@
if (Get-PnxReadyEvidence -Log $readyLog -Port 19362) { throw 'A listener must not satisfy native startup readiness.' }
$readyLog += "`nDone (168.193s)! For help, type ""help"" or ""?"""
if (-not (Get-PnxReadyEvidence -Log $readyLog -Port 19362)) { throw 'Native startFinished did not satisfy readiness.' }
if (Get-PnxReadyEvidence -Log $readyLog -Port 19363) { throw 'The wrong signalling port must not satisfy readiness.' }
$control = Join-Path $OutputRoot 'console'
$null = New-Item -ItemType Directory -Path $control
$childScript = Join-Path $OutputRoot 'stdin-echo.ps1'
[IO.File]::WriteAllText($childScript, @'
while (($line = [Console]::ReadLine()) -ne $null) {
    [Console]::Out.WriteLine("echo:" + $line)
    [Console]::Error.WriteLine("err:" + $line)
}
'@, $utf8)
$runtime = $null
try {
    $stdout = Join-Path $OutputRoot 'stdin.stdout.log'
    $stderr = Join-Path $OutputRoot 'stdin.stderr.log'
    $runtime = Start-HeldConsoleProcess -Executable (Join-Path $PSHOME 'powershell.exe') `
        -Arguments ('-NoProfile -NonInteractive -File "' + $childScript + '"') `
        -Directory $OutputRoot -Stdout $stdout -Stderr $stderr
    $runId = [guid]::NewGuid().ToString('N')
    $requestId = [guid]::NewGuid().ToString()
    $command = "btstate BTTraveler $runId-source-a-absent"
    $request = @{ id = $requestId; runId = $runId; node = 'a'; command = $command }
    [IO.File]::WriteAllText((Join-Path $control "$requestId.request.json"), ($request | ConvertTo-Json), $utf8)
    $handled = New-Object 'System.Collections.Generic.HashSet[string]'
    Send-PnxConsoleRequests -Directory $control -RunId $runId -Runtimes @{ a = $runtime } `
        -Handled $handled -AllowedNames @('BTTraveler')
    $deadline = (Get-Date).AddSeconds(10)
    while ((Read-PnxPlainLog $stdout) -notmatch [regex]::Escape("echo:$command") -or
        (Read-PnxPlainLog $stderr) -notmatch [regex]::Escape("err:$command")) {
        if ($runtime.Process.HasExited -or (Get-Date) -gt $deadline) { throw 'Held stdin or asynchronous log capture failed.' }
        Start-Sleep -Milliseconds 50
    }
    if ($runtime.Process.HasExited) { throw 'Console stdin reached EOF before disposal.' }
    $reply = Get-Content -LiteralPath (Join-Path $control "$requestId.reply.json") -Raw | ConvertFrom-Json
    if ($reply.id -cne $requestId -or $reply.submitted -ne $true) { throw 'Console request was not acknowledged.' }
    $runtime.Process.StandardInput.Close()
    if (-not $runtime.Process.WaitForExit(5000)) { throw 'Owned echo child did not exit after closing stdin.' }
} finally {
    if ($runtime) {
        if (-not $runtime.Process.HasExited) { $runtime.Process.Kill() }
        Close-HeldConsoleStreams $runtime
    }
}
& node --test (Join-Path $PSScriptRoot 'console-control.test.js') *> (Join-Path $OutputRoot 'console-contract.log')
if ($LASTEXITCODE -ne 0) { throw "Console receipt contract failed: $OutputRoot/console-contract.log" }
$summary = @{ pass = $true; scope = 'native loopback config, strict readiness, held stdin, console receipt; no servers'; outputRoot = $OutputRoot }
[IO.File]::WriteAllText((Join-Path $OutputRoot 'runtime-summary.json'), ($summary | ConvertTo-Json), $utf8)
Write-Host "PASS native loopback/runtime check (no servers): $OutputRoot"
