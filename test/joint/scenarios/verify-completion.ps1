[CmdletBinding()]
param([string]$NativePacketsFile = '')

$ErrorActionPreference = 'Stop'
foreach ($name in @('completion.ps1', 'verify-completion.ps1', '../fixtures/completion/fixture.ps1')) {
  $tokens = $null; $errors = $null
  $null = [Management.Automation.Language.Parser]::ParseFile((Join-Path $PSScriptRoot $name), [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw "$name parse failed: $($errors.Message -join '; ')" }
}
. (Join-Path $PSScriptRoot 'completion.ps1')
. (Join-Path $PSScriptRoot '../fixtures/completion/fixture.ps1')

$protocolRoot = Join-Path $PSScriptRoot '../../bot/node_modules/minecraft-data/minecraft-data/data/pc/1.21.11'
$wire = Get-Content -LiteralPath (Join-Path $protocolRoot 'protocol.json') -Raw | ConvertFrom-Json
$version = Get-Content -LiteralPath (Join-Path $protocolRoot 'version.json') -Raw | ConvertFrom-Json
$packetId = $wire.play.toClient.types.packet[1][0].type[1].mappings.PSObject.Properties | Where-Object { $_.Value -ceq 'chat_suggestions' }
$adapter = [IO.File]::ReadAllText((Join-Path $PSScriptRoot '../../../NovaChat/Proxy/bungee/src/main/java/com/nova/chat/bungee/chat/MentionTabCompleter.java'))
if ($version.version -ne 774 -or $packetId.Name -cne '0x17' -or
    $adapter -notmatch 'PROTOCOL_VERSION = 774;' -or $adapter -notmatch 'CHAT_SUGGESTIONS_PACKET_ID = 0x17;') {
  throw 'Bungee native adapter no longer matches the pinned protocol schema.'
}

$required = @(Get-CompletionRequiredAssertionIds 1)
if ($required.Count -ne 13 -or @($required | Select-Object -Unique).Count -ne 13) { throw 'Completion assertion ID contract changed.' }
foreach ($proxy in @('velocity', 'bungee')) {
  foreach ($scope in @('native-chat', 'request-client')) {
    $plans = Get-JointCompletionPlans -RunId 'completion-contract' -Proxy $proxy -CoverageScope $scope
    if ($plans.Names.Requester -ceq $plans.Names.Target -or $plans.Names.Requester.Length -gt 16 -or $plans.Names.Target.Length -gt 16) {
      throw 'Completion must use distinct valid player identities.'
    }
    $captures = @($plans.Requester | Where-Object { $_.action -eq 'complete' })
    if ($captures.Count -ne 8 -or @($captures | Where-Object { $_.required -ne $true }).Count) { throw 'All completion phases must gate the plan.' }
    foreach ($step in $captures) {
      if ($step.id -notin @('completion-channel-away', 'completion-ignored', 'completion-permission-denied') -and
          ($step.minCandidates -lt 1 -or $step.mustInclude -cnotcontains ('@' + $plans.Names.Target))) { throw 'Positive phase must require the other real role.' }
    }
    $verdicts = @(Assert-JointCompletionResults -RoundIndex 1 -RunId 'completion-contract' -Proxy $proxy -Plans $plans)
    $capability = @($verdicts | Where-Object { $_.id -eq 'round1/completion/protocol-capability' })
    if ($capability.Count -ne 1 -or -not $capability[0].pass -or ($scope -eq 'native-chat' -and $plans.ProtocolMode -cne 'chat_suggestions')) {
      throw 'Both locked proxies must use native push by default.'
    }
    if (@($verdicts | Where-Object { $_.pass -and $_.id -ne 'round1/completion/protocol-capability' }).Count) { throw 'Empty recordings must never prove completion.' }
  }
}

$step = @{ id = 'completion-baseline'; text = '@Actual'; protocolMode = 'chat_suggestions' }
$fixture = [PSCustomObject]@{ received = @(
  [PSCustomObject]@{ kind = 'completionInput'; stepId = $step.id; text = '@Actual'; protocolMode = 'chat_suggestions'; localTime = 'fixture-local'; atMs = 100 },
  [PSCustomObject]@{ kind = 'completionPacket'; packetName = 'chat_suggestions'; direction = 'clientbound'; synthetic = $false;
    packet = [PSCustomObject]@{ action = 2; entries = @('@ActualPeer') }; localTime = 'fixture-local'; atMs = 101 },
  [PSCustomObject]@{ kind = 'completion'; stepId = $step.id; inputText = '@Actual'; prefix = '@Actual'; protocolMode = 'chat_suggestions';
    inputAtMs = 100; localTime = 'fixture-local'; atMs = 102; synthetic = $false; packetIndices = @(1); candidates = @('@ActualPeer') }
) }
if (-not (Get-CompletionWireCapture $fixture $step).Valid) { throw 'Synthetic wire capture baseline failed.' }
$fixture.received[2].candidates = @('@InventedPeer')
if ((Get-CompletionWireCapture $fixture $step).Valid) { throw 'Candidate list without matching protocol evidence was accepted.' }
$fixture.received[2].candidates = @('@ActualPeer'); $fixture.received[1].synthetic = $true
if ((Get-CompletionWireCapture $fixture $step).Valid) { throw 'Synthetic bot packets were accepted as server evidence.' }
$fixture.received[1].synthetic = $false
$step.sinceCheckpoint = 'missing-phase'
if ((Get-CompletionWireCapture $fixture $step).Valid) { throw 'Missing negative phase checkpoint was accepted.' }

$botArguments = @('--selftest-completion')
if ($NativePacketsFile) { $botArguments += @('--completion-native-packets', (Resolve-Path -LiteralPath $NativePacketsFile).ProviderPath) }
& node (Join-Path $PSScriptRoot '../bots/joint-bot.js') @botArguments
if ($LASTEXITCODE -ne 0) { throw "Completion bot self-test failed: $LASTEXITCODE" }
Write-Host '[SYNTHETIC PASS] completion syntax, plans, wire provenance, capability boundary and targeted bot self-test; no server started.'
