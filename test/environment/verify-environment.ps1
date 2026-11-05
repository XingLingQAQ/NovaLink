#requires -Version 5.1
[CmdletBinding()]
param([switch]$StaticOnly)
$ErrorActionPreference = 'Stop'
$files = @(Get-ChildItem -LiteralPath $PSScriptRoot -File | Where-Object Extension -In @('.ps1', '.psd1'))
foreach ($file in $files) {
    $parseErrors = $null
    $tokens = $null
    $null = [Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count -gt 0) { throw "$($file.Name): $($parseErrors.Message -join '; ')" }
}
if ($StaticOnly) {
    $repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $result = & node (Join-Path $PSScriptRoot 'verify-workflow.cjs') $repoRoot
    if ($LASTEXITCODE -ne 0) { throw 'Environment workflow static check failed' }
    $workflow = ($result -join "`n") | ConvertFrom-Json
    foreach ($step in $workflow.powershell) {
        $null = [Management.Automation.Language.Parser]::ParseInput($step.script, [ref]$tokens, [ref]$parseErrors)
        if ($parseErrors.Count -gt 0) { throw "$($step.name): $($parseErrors.Message -join '; ')" }
    }
    Write-Output 'Parsed environment PowerShell and workflow YAML; checked manual-only dispatch, Node 24, Ubuntu paths, module tasks and public-only uploads. No runtime validation was performed.'
    exit 0
}
$scenarioAst = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot 'scenarios.ps1'), [ref]$tokens, [ref]$parseErrors)
$pureFunctions = @('Get-EnvironmentCaseDefinitions', 'New-EnvironmentAssertion', 'Complete-EnvironmentAssertions')
foreach ($definition in $scenarioAst.FindAll({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] }, $true)) {
    if ($definition.Name -in $pureFunctions) { . ([scriptblock]::Create($definition.Extent.Text)) }
}
$definitions = @(Get-EnvironmentCaseDefinitions -SpecificationPath (Join-Path $PSScriptRoot 'cases.psd1'))
if ($definitions.Count -ne 5) { throw 'Expected five environment cases' }
$ids = @($definitions | ForEach-Object RequiredAssertionIds)
if (@($ids | Select-Object -Unique).Count -ne $ids.Count) { throw 'Assertion IDs must be unique' }
$missing = @(Complete-EnvironmentAssertions @() $ids)
if ($missing.Count -ne $ids.Count -or @($missing | Where-Object pass).Count -gt 0) { throw 'Missing evidence must fail every required assertion' }
$one = New-EnvironmentAssertion $ids[0] $true 'diagnostic fixture'
$partial = @(Complete-EnvironmentAssertions @($one) $ids)
if (@($partial | Where-Object pass).Count -ne 1 -or $partial.Count -ne $ids.Count) { throw 'Partial evidence must preserve failures' }
. (Join-Path $PSScriptRoot 'storage-probe.ps1')
. (Join-Path $PSScriptRoot 'evidence-checks.ps1')
$target = '11111111-1111-4111-8111-111111111111'
$unconsumed = @{ target = $target; used = $false; usedCount = 0; usedBy = $null; usedAt = 0 }
$consumed = @{ target = $target; used = $true; usedCount = 1; usedBy = $target; usedAt = 1234 }
$providerIds = @((Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')).RequiredAssertions.redis |
    Where-Object { $_ -notmatch '-(GAME-DELIVERY|STORED|RESTARTED|SAME-ROW|POST-RESTART-GAME|POST-RESTART-STORED)$' })
$fixture = @{ engine = 'redis'; server = 'redis_version:7.4.12'; status = 'PASS'; verification = 'real-database-provider'
    assertions = @($providerIds | ForEach-Object { @{ id = $_; pass = $true; verification = 'real-database-provider' } })
    operations = @{ target = $target; wrong = '22222222-2222-4222-8222-222222222222'; wrongResult = 0
        claims = @(1,0,0,0,0,0,0,0); saved = $unconsumed; databaseSaved = $unconsumed; afterWrong = $unconsumed
        reconnected = $unconsumed; consumed = $consumed; reconnectedConsumed = $consumed; databaseConsumed = $consumed } }
$fixture = ($fixture | ConvertTo-Json -Depth 10) | ConvertFrom-Json
Assert-EnvironmentStorageEvidence $fixture 'redis'
$fixture | Add-Member -NotePropertyName synthetic -NotePropertyValue $true
$rejected = $false; try { Assert-EnvironmentStorageEvidence $fixture 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'Synthetic provider evidence must fail' }
$fixture.synthetic = 'false'
$rejected = $false; try { Assert-EnvironmentStorageEvidence $fixture 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'Non-boolean synthetic marker must fail' }
$fixture.synthetic = $false
Assert-EnvironmentStorageEvidence $fixture 'redis'
$fixture.operations.claims = @(1,1,0,0,0,0,0,0)
$rejected = $false
try { Assert-EnvironmentStorageEvidence $fixture 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'Forged claim results must fail' }
$fixture.operations = $null
$rejected = $false
try { Assert-EnvironmentStorageEvidence $fixture 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'Missing operation evidence must fail' }
$sender = [pscustomobject]@{ sent = @(@{ text = 'token'; atMs = 1000 }); received = @(@{ kind = 'playerJoined'; data = @{ player = 'EnvBukkit'; uuid = $target } }) }
$row = [pscustomobject]@{ id = 1; channelId = 'global'; senderId = $target; senderName = 'EnvBukkit'; clientId = 'ENV_Bukkit'; content = 'token'; timestamp = 1100 }
if (-not (Test-EnvironmentGameRow $row $sender 'token')) { throw 'Expected bound game row' }
$row.content = 'forged'
if (Test-EnvironmentGameRow $row $sender 'token') { throw 'Forged game row must fail' }

$providerCopy = ($fixture | ConvertTo-Json -Depth 10) | ConvertFrom-Json
$providerCopy.operations = @{ target = $target; wrong = '22222222-2222-4222-8222-222222222222'; wrongResult = 0
    claims = @(1,0,0,0,0,0,0,0); saved = $unconsumed; databaseSaved = $unconsumed; afterWrong = $unconsumed
    reconnected = $unconsumed; consumed = $consumed; reconnectedConsumed = $consumed }
$providerCopy = ($providerCopy | ConvertTo-Json -Depth 10) | ConvertFrom-Json
$providerCopy.assertions[0].pass = 'true'
$rejected = $false; try { Assert-EnvironmentStorageEvidence $providerCopy 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'String pass must fail' }
$providerCopy.assertions[0].pass = $true
$providerCopy.operations.saved.used = 'false'
$rejected = $false; try { Assert-EnvironmentStorageEvidence $providerCopy 'redis' } catch { $rejected = $true }
if (-not $rejected) { throw 'String used must fail' }

$sender = [pscustomobject]@{ runId = 'run-control'; role = 'bukkit'; username = 'EnvBukkit'; sent = @(@{text='token';atMs=1500}) }
$receiver = [pscustomobject]@{ runId = 'run-control'; role = 'fabric'; username = 'EnvFabric'; received = @(@{kind='messagestr';text='[Global] EnvBukkit: token';raw='[Global] EnvBukkit: token';atMs=1600}) }
if (-not (Test-EnvironmentDelivery $sender $receiver 'token')) { throw 'Expected bound delivery' }
$receiver.received[0].text = '<EnvBukkit> token'; $receiver.received[0].raw = '<EnvBukkit> token'
if (Test-EnvironmentDelivery $sender $receiver 'token') { throw 'Vanilla echo must not prove delivery' }
$receiver.received[0].text = '[Global] EnvBukkit: token'; $receiver.received[0].raw = '[Global] EnvBukkit: token'; $receiver.received[0].atMs = 1499
if (Test-EnvironmentDelivery $sender $receiver 'token') { throw 'Receipt before send must fail' }
$receiver.received[0].atMs = 1600; $receiver.role = 'bukkit'
if (Test-EnvironmentDelivery $sender $receiver 'token') { throw 'Same-role delivery must fail' }

$dir = Join-Path ([IO.Path]::GetTempPath()) ('environment-bot-check-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($dir) | Out-Null
$path = Join-Path $dir 'run-control-bukkit-bukkit-complete.json'
try {
    @{runId='run-control';role='bukkit';key='bukkit-complete';value='spawned';t=[DateTimeOffset]::FromUnixTimeMilliseconds(2000).ToString('o')} |
        ConvertTo-Json | Set-Content -LiteralPath $path -Encoding UTF8
    $bot = @{runId='run-control';role='bukkit';kind='java';username='EnvBukkit';timedOut=$false;state=@{self='EnvBukkit';kicked=$null}
        sent=@(@{text='token';atMs=1500});received=@(@{kind='spawn';raw='spawned';atMs=1000},@{kind='end';atMs=2100})
        assertions=@(@{id='from-fabric';token='run-control-fabric';pass=$true});absentChecks=@()}
    $bot = ($bot | ConvertTo-Json -Depth 10) | ConvertFrom-Json
    if (-not (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir)) { throw 'Expected completed bot recording' }
    $bot | Add-Member -NotePropertyName synthetic -NotePropertyValue $true
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'Synthetic bot must fail' }
    $bot.synthetic = $false; $bot.assertions[0].pass = $false
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'Failed bot assertion must fail' }
    $bot.assertions[0].pass = 'true'
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'String bot pass must fail' }
    $bot.assertions[0].pass = $true; $bot.received[1].atMs = 1900
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'Early disconnect must fail' }
    $bot.received[1].atMs = 2100; $bot.received += [pscustomobject]@{kind='error';atMs=1800}
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'Bot error must fail' }
    $bot.received = @($bot.received | Where-Object { $_.kind -ne 'error' }); $bot.state.kicked = 'kicked'
    if (Test-EnvironmentBotRecording $bot 'run' 'control' 'bukkit' $dir) { throw 'Kicked bot must fail' }
} finally { Remove-Item -LiteralPath $path -ErrorAction SilentlyContinue; Remove-Item -LiteralPath $dir -ErrorAction SilentlyContinue }

$legacy = '{"before":[["code","channel","inviter","1","2","0",null,"0","1","0",null]],"after":[["code","channel","inviter","1","2","0",null,"0","1","0",null]]}' | ConvertFrom-Json
if (-not (Test-EnvironmentLegacyInvitations $legacy.before $legacy.after)) { throw 'Expected matching SQL rows' }
$legacy.after[0][2] = 'changed'
if (Test-EnvironmentLegacyInvitations $legacy.before $legacy.after) { throw 'Changed SQL invitation must fail' }
if (Test-EnvironmentLegacyInvitations $legacy.before $null) { throw 'Missing SQL invitation snapshot must fail' }
$ledger = @{runId='run'; old=@{pid=100;startedAt='2026-01-01T00:00:00Z';stopRequestedAt='2026-01-01T00:00:01Z';exitedAt='2026-01-01T00:00:02Z';exitCode=0;stopMode='sigterm';wasRunning=$true;forced=$false}
    new=@{pid=101;startedAt='2026-01-01T00:00:03Z'}}
if (-not (Test-EnvironmentRestartLedger $ledger 'run' 'NovaLink core stopped in 10 ms' 'started successfully')) { throw 'Expected restart ledger' }
if (Test-EnvironmentRestartLedger $ledger 'wrong-run' 'NovaLink core stopped in 10 ms' 'started successfully') { throw 'Wrong run must fail' }
if ((ConvertTo-EnvironmentTimestamp ([DateTime]::SpecifyKind([DateTime]'2026-01-01T00:00:00.123', [DateTimeKind]::Utc))).Millisecond -ne 123) { throw 'Timestamp precision lost' }
if (Test-EnvironmentRestartLedger $ledger 'run' '' 'started successfully') { throw 'Missing shutdown log must fail' }
$ledger.new.pid = 100
if (Test-EnvironmentRestartLedger $ledger 'run' 'NovaLink core stopped in 10 ms' 'started successfully') { throw 'Reused PID must fail' }
$ledger.new.pid = 101; $ledger.old.forced = $true
if (Test-EnvironmentRestartLedger $ledger 'run' 'NovaLink core stopped in 10 ms' 'started successfully') { throw 'Forced stop must fail' }
Write-Output 'PowerShell syntax and pure evidence checks passed; no server, TLS handshake or SQL persistence was exercised.'
