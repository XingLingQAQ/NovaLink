[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scenarios/business.ps1')
. (Join-Path $PSScriptRoot 'scenarios/registry.ps1')
Write-Host '[SYNTHETIC] Offline scenario and summary contracts; no real server evidence is produced'
$main = Join-Path $PSScriptRoot 'run-joint.ps1'
$tokens = $null
$errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($main, [ref]$tokens, [ref]$errors)
if ($errors.Count) { throw 'Main script did not parse' }
$expectations = $ast.Find({ param($node)
  $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-BotExpectations'
}, $true)
. ([scriptblock]::Create($expectations.Extent.Text))
$proxyCheck = $ast.Find({ param($node)
  $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-ProxyPluginLoaded'
}, $true)
. ([scriptblock]::Create($proxyCheck.Extent.Text))
$readyCheck = $ast.Find({ param($node)
  $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Wait-LogMarker'
}, $true)
. ([scriptblock]::Create($readyCheck.Extent.Text))
function Read-LogText([string]$Path) { return [System.IO.File]::ReadAllText($Path) }
function Log([string]$Message) {}

function Assert-Check([bool]$Pass, [string]$Label) {
  if (-not $Pass) { throw "SelfTest failed: $Label" }
  Write-Host "[SELFTEST PASS] $Label"
}

function New-Row([string]$Text, [double]$AtMs) {
  [PSCustomObject]@{ kind = 'messagestr'; text = $Text; atMs = $AtMs }
}

$proxyLog = [System.IO.Path]::GetTempFileName()
try {
  [System.IO.File]::WriteAllText($proxyLog, 'Loaded plugin NovaChat version 1.0')
  $verdict = Assert-ProxyPluginLoaded 'selftest/proxy' 'bungee' $proxyLog
  Assert-Check (-not $verdict.pass) 'loader registration does not prove plugin enabled'
  [System.IO.File]::WriteAllText($proxyLog, 'NovaChat BungeeCord plugin enabled!')
  $verdict = Assert-ProxyPluginLoaded 'selftest/proxy' 'bungee' $proxyLog
  Assert-Check $verdict.pass 'plugin enable marker accepted'
  [System.IO.File]::WriteAllText($proxyLog, 'java.net.BindException: Address already in use')
  Assert-Check (-not (Wait-LogMarker $proxyLog 'Listening on' 1 'proxy')) 'bind failure stops readiness polling'
} finally { Remove-Item -LiteralPath $proxyLog -Force }

function New-Results {
  $alice = [PSCustomObject]@{
    received = @(
      (New-Row 'Joined channel global' 1),
      (New-Row 'selftest-iso-control' 2),
      [PSCustomObject]@{ kind = 'checkpoint'; key = 'recovery-selecting'; atMs = 50 },
      (New-Row 'Joined channel local' 52),
      [PSCustomObject]@{ kind = 'reconnect'; phase = 'spawned'; atMs = 100 },
      (New-Row 'Channel Local online members (2):' 200),
      (New-Row 'E2EBizAlice, E2EBizBob' 201),
      (New-Row 'selftest-reconnect-inbound' 210)
    )
    sent = @([PSCustomObject]@{ text = '/nc join local'; atMs = 51 }, [PSCustomObject]@{ text = '/nc who'; atMs = 190 })
    assertions = @([PSCustomObject]@{ id = 'recovery-reconnect'; pass = $true; evidence = 'spawned'; kind = 'reconnect' },
      [PSCustomObject]@{ id = 'recovery-selected'; pass = $true; evidence = 'fresh local selection' })
    absentChecks = @(); timedOut = $false
  }
  $bob = [PSCustomObject]@{
    received = @(
      (New-Row 'Joined channel local' 1),
      (New-Row 'selftest-iso-fromb' 3),
      (New-Row 'Joined channel global' 4),
      (New-Row 'selftest-iso-shared' 5),
      (New-Row 'selftest-reconnect-outbound' 210)
    )
    assertions = @(); absentChecks = @(); timedOut = $false
  }
  return @{ Alice = $alice; Bob = $bob }
}

function Get-Verdicts($Results) {
  @(Assert-JointBusinessResults -RoundIndex 1 -RunId 'selftest' -AliceRes $Results.Alice -BobRes $Results.Bob)
}

$results = New-Results
$verdicts = Get-Verdicts $results
$failed = @($verdicts | Where-Object { $_.kind -ne 'diagnostic' -and -not $_.pass })
Assert-Check ($failed.Count -eq 0) 'synthetic isolation and reconnect evidence accepted'

$results.Bob.received += New-Row 'selftest-iso-control' 2
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/iso/phase1-isolated-from-bob' -and -not $_.pass }).Count -eq 1) 'global leak rejected'

$results = New-Results
$results.Alice.received += New-Row 'selftest-iso-fromb' 3
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/iso/phase2-isolated-from-alice' -and -not $_.pass }).Count -eq 1) 'local leak rejected'

$results = New-Results
$results.Bob = $null
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/bob-completed' -and -not $_.pass -and $_.kind -ne 'diagnostic' }).Count -eq 1) 'missing observer fails gating'

$results = New-Results
$results.Alice.sent[1].atMs = 90
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and -not $_.pass }).Count -eq 1) 'pre-reconnect who cannot prove restored membership'

$results = New-Results
$results.Alice.received[5].text = 'Channel Global online members (2):'
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and -not $_.pass }).Count -eq 1) 'fallback to global cannot prove non-default recovery'

$results = New-Results
$results.Alice.sent += [PSCustomObject]@{ text = '/nc join local'; atMs = 150 }
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-no-rejoin' -and -not $_.pass }).Count -eq 1) 'explicit post-reconnect join cannot repair the recovery evidence'

$results = New-Results
$results.Alice.received[6].text = '[NovaChat] E2EBizAlice, E2EBizBob'
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and $_.pass }).Count -eq 1) 'Bungee prefixed member list accepted'

$results = New-Results
$results.Alice.received[6].text = '<E2EBizAlice> unrelated chat'
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and -not $_.pass }).Count -eq 1) 'name in unrelated chat cannot prove membership'

$results = New-Results
$results.Alice.received[-1] = [PSCustomObject]@{ kind = 'messagestr'; text = 'unrelated'; raw = 'selftest-reconnect-inbound'; atMs = 210 }
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-inbound' -and -not $_.pass }).Count -eq 1) 'raw payload cannot prove received chat'

$results = New-Results
$results.Alice.timedOut = $true
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/alice-completed' -and -not $_.pass }).Count -eq 1) 'partial timed-out recording fails gating'

$summaryDir = Join-Path $PSScriptRoot ('.selftest-' + [guid]::NewGuid().ToString('N'))
$summaryFile = Join-Path $summaryDir 'joint-summary.json'
New-Item -ItemType Directory -Path $summaryDir | Out-Null
function New-ContractSummary {
  param([string]$Groups, [string]$Mode, [string]$Proxy, [string]$Secondary = '')
  $selected = @(Resolve-JointScenarioGroups -Value $Groups)
  $ids = @(Get-JointScenarioAssertionIds -ScenarioGroups $selected -RoundIndex 1 -Mode $Mode -Proxy $Proxy)
  if ($Mode -eq 'relay') { $ids += @('round1/java-to-bedrock', 'round1/bedrock-to-java', 'round1/relay-chat-attribution-and-delivery') }
  $rows = @($ids | ForEach-Object {
    New-JointAssertion -Id $_ -Token 'synthetic-contract' -Pass $true -Evidence 'Synthetic summary fixture only' -Kind 'contract-fixture'
  })
  [PSCustomObject]@{
    proxyMode = $Mode; overallPass = $true; scenarioGroups = $selected
    rounds = @([PSCustomObject]@{
      status = 'PASS'; proxy = $Proxy; java = 'bukkit'; mod = $null; secondaryJava = $Secondary
      proxySwitchDriver = $(if ('proxy-switch' -in $selected) { 'player-command' } else { $null })
      bedrock = $(if ($Mode -eq 'relay') { 'nukkit' } else { $null }); assertions = $rows
    })
  }
}

function Invoke-ContractGuard {
  param($Summary, [string]$Groups, [string]$Mode, [string]$Proxy, [string]$Secondary = 'none')
  [IO.File]::WriteAllText($summaryFile, ($Summary | ConvertTo-Json -Depth 12))
  & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $Mode `
    -ProxyPlatform $Proxy -BedrockPlatform 'nukkit' -ScenarioGroups $Groups -SecondaryJavaPlatform $Secondary
}

function Assert-ContractRejected {
  param($Summary, [string]$Groups, [string]$Mode, [string]$Proxy, [string]$Reason, [string]$Secondary = 'none')
  $failure = ''
  try { Invoke-ContractGuard $Summary $Groups $Mode $Proxy $Secondary } catch { $failure = $_.Exception.Message }
  Assert-Check ($failure.Contains($Reason)) "synthetic contract rejects $Reason"
}

try {
  foreach ($case in @(
    @{ Proxy = 'velocity'; Mode = 'relay'; Bedrock = 'nukkit' },
    @{ Proxy = 'velocity'; Mode = 'relay'; Bedrock = 'pnx' },
    @{ Proxy = 'velocity'; Mode = 'lite'; Bedrock = 'nukkit' },
    @{ Proxy = 'bungee'; Mode = 'lite'; Bedrock = 'nukkit' },
    @{ Proxy = 'bungee'; Mode = 'relay'; Bedrock = 'nukkit' },
    @{ Proxy = 'bungee'; Mode = 'relay'; Bedrock = 'pnx' }
  )) {
    $mode = $case.Mode
    $proxy = $case.Proxy
    $bedrock = $case.Bedrock
    $label = if ($mode -eq 'relay') { "$proxy/$mode/$bedrock" } else { "$proxy/$mode" }
    $assertions = @(Get-Verdicts (New-Results))
    $ids = if ($mode -eq 'relay') {
      @('java-to-bedrock', 'bedrock-to-java', 'proxy-plugin-loaded', 'relay-links-created',
        'relay-distinct-backend-identities', 'relay-chat-attribution-and-delivery')
    } else {
      @('lite-mode-active', 'downstream-upstream-roundtrip', 'proxy-to-downstream-chat')
    }
    if ($mode -eq 'relay' -and $bedrock -eq 'pnx') { $ids += 'native-pnx-runtime' }
    foreach ($id in $ids) {
      $assertions += New-JointAssertion -Id "round1/$id" -Token 'synthetic' -Pass $true -Evidence 'fixture'
    }
    if ($mode -eq 'lite' -and $proxy -eq 'velocity') {
      $assertions += New-JointAssertion -Id 'bob/expect-mention-sound' -Token 'minecraft:entity.experience_orb.pickup' -Pass $true -Evidence 'fixture'
    }
    $summary = [PSCustomObject]@{
      proxyMode = $mode; overallPass = $true; scenarioGroups = @('core')
      rounds = @([PSCustomObject]@{ status = 'PASS'; proxy = $proxy; java = 'bukkit'; mod = $null
        bedrock = $(if ($mode -eq 'relay') { $bedrock } else { $null }); secondaryJava = $null; assertions = $assertions })
    }
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $bedrock
    Assert-Check $true "CI $label accepts complete synthetic evidence"
    $wrongProxy = if ($proxy -eq 'velocity') { 'bungee' } else { 'velocity' }
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $wrongProxy -BedrockPlatform $bedrock } catch { $rejected = $true }
    Assert-Check $rejected "CI $label rejects wrong proxy"

    if ($mode -eq 'relay') {
      $wrongBedrock = if ($bedrock -eq 'pnx') { 'nukkit' } else { 'pnx' }
      $rejected = $false
      try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $wrongBedrock } catch { $rejected = $true }
      Assert-Check $rejected "CI $label rejects a different Bedrock server"
    }

    $required = $assertions | Where-Object { $_.id -eq 'round1/iso/phase1-isolated-from-bob' }
    $requiredKind = $required.kind
    $required.kind = 'diagnostic'
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $bedrock } catch { $rejected = $true }
    Assert-Check $rejected "CI $label rejects diagnostic substitution"

    $summary.rounds[0].assertions = @($assertions | Where-Object { $_.id -ne $required.id })
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $bedrock } catch { $rejected = $true }
    Assert-Check $rejected "CI $label rejects missing assertion"

    if ($mode -eq 'relay' -and $bedrock -eq 'pnx') {
      $required.kind = $requiredKind
      $summary.rounds[0].assertions = $assertions
      $native = $assertions | Where-Object { $_.id -eq 'round1/native-pnx-runtime' }
      $native.kind = 'diagnostic'
      [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
      $rejected = $false
      try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $bedrock } catch { $rejected = $_.Exception.Message -match 'native-pnx-runtime' }
      Assert-Check $rejected "CI $label rejects diagnostic native-runtime evidence"
      $summary.rounds[0].assertions = @($assertions | Where-Object { $_.id -ne 'round1/native-pnx-runtime' })
      [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
      $rejected = $false
      try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy -BedrockPlatform $bedrock } catch { $rejected = $_.Exception.Message -match 'native-pnx-runtime' }
      Assert-Check $rejected "CI $label rejects missing native-runtime evidence"
    }
  }

  Assert-Check ((@(Resolve-JointScenarioGroups ' BUSINESS,core,business ') -join ',') -ceq 'core,business') 'scenario groups include core and normalize duplicates'
  Assert-Check ((@(Resolve-JointScenarioGroups '') -join ',') -ceq 'core') 'empty selection defaults to core'
  foreach ($selection in @('core,unknown', 'core,,business')) {
    $rejected = $false
    try { $null = Resolve-JointScenarioGroups $selection } catch { $rejected = $_.Exception.Message -match 'Unknown joint scenario group' }
    Assert-Check $rejected "unknown or empty CSV group rejected: $selection"
  }
  foreach ($case in @(
    @{ Groups = 'lite-moderation'; Mode = 'relay' },
    @{ Groups = 'recovery'; Mode = 'lite' },
    @{ Groups = 'proxy-switch'; Mode = 'lite' },
    @{ Groups = 'channel-lifecycle'; Mode = 'lite' },
    @{ Groups = 'custom-effects'; Mode = 'lite' }
  )) {
    $rejected = $false
    try { $null = Get-JointScenarioAssertionIds -ScenarioGroups $case.Groups -RoundIndex 1 -Mode $case.Mode -Proxy 'bungee' } catch {
      $rejected = $_.Exception.Message.Contains("Scenario group '$($case.Groups)' requires")
    }
    Assert-Check $rejected "registry rejects $($case.Groups) in $($case.Mode)"
  }
  $liteIds = @(Get-JointScenarioAssertionIds -ScenarioGroups @('lite-moderation') -RoundIndex 1 -Mode 'lite' -Proxy 'bungee')
  Assert-Check (@($liteIds | Where-Object { $_ -like 'round1/lite-moderation/*' }).Count -eq 18 -and
    'round1/lite-moderation/drive' -in $liteIds) 'all 18 Lite moderation IDs including drive are mandatory'

  foreach ($case in @(
    @{ Group = 'business'; Mode = 'lite'; Proxy = 'bungee'; Secondary = 'none'; Id = 'round1/pm/observer-positive-control' },
    @{ Group = 'lite-moderation'; Mode = 'lite'; Proxy = 'bungee'; Secondary = 'none'; Id = 'round1/lite-moderation/permission-unmute' },
    @{ Group = 'recovery'; Mode = 'relay'; Proxy = 'velocity'; Secondary = 'none'; Id = 'round1/recovery/backend-process-restarted' },
    @{ Group = 'proxy-switch'; Mode = 'relay'; Proxy = 'bungee'; Secondary = 'bukkit'; Id = 'round1/proxy-switch/wrong-link-denied' },
    @{ Group = 'social-contract'; Mode = 'lite'; Proxy = 'bungee'; Secondary = 'none'; Id = 'round1/social-contract/ignore-suppressed' },
    @{ Group = 'channel-lifecycle'; Mode = 'relay'; Proxy = 'velocity'; Secondary = 'bukkit'; Id = 'round1/channel-lifecycle/created-unique-id' },
    @{ Group = 'custom-effects'; Mode = 'relay'; Proxy = 'velocity'; Secondary = 'none'; Id = 'round1/custom-effects/target-timings' },
    @{ Group = 'completion'; Mode = 'lite'; Proxy = 'velocity'; Secondary = 'none'; Id = 'round1/completion/baseline-peer' }
  )) {
    $leg = if ($case.Secondary -eq 'bukkit') { 'bukkit2' } else { '' }
    $fixture = New-ContractSummary $case.Group $case.Mode $case.Proxy $leg
    Invoke-ContractGuard $fixture $case.Group $case.Mode $case.Proxy $case.Secondary
    Assert-Check $true "complete synthetic $($case.Group) summary accepted"
    $requiredRow = @($fixture.rounds[0].assertions | Where-Object { $_.id -ceq $case.Id })[0]
    $requiredRow.kind = 'diagnostic'
    Assert-ContractRejected $fixture $case.Group $case.Mode $case.Proxy $case.Id $case.Secondary
    $fixture.rounds[0].assertions = @($fixture.rounds[0].assertions | Where-Object { $_.id -cne $case.Id })
    $driveId = "round1/$($case.Group)/drive"
    if (@($fixture.rounds[0].assertions | Where-Object { $_.id -ceq $driveId }).Count -eq 0) {
      $fixture.rounds[0].assertions += New-JointAssertion -Id $driveId -Token 'synthetic' -Pass $true -Evidence 'Drive alone is insufficient'
    }
    Assert-ContractRejected $fixture $case.Group $case.Mode $case.Proxy $case.Id $case.Secondary
  }

  $fixture = New-ContractSummary 'business' 'lite' 'bungee'
  $fixture.scenarioGroups = @('core')
  Assert-ContractRejected $fixture 'business' 'lite' 'bungee' 'summary.scenarioGroups mismatch'
  $fixture = New-ContractSummary 'core' 'lite' 'bungee'
  $fixture.PSObject.Properties.Remove('scenarioGroups')
  Assert-ContractRejected $fixture 'core' 'lite' 'bungee' 'summary.scenarioGroups'
  $fixture = New-ContractSummary 'business' 'lite' 'bungee'
  Assert-ContractRejected $fixture 'core' 'lite' 'bungee' 'summary.scenarioGroups mismatch'

  $fixture = New-ContractSummary 'recovery' 'relay' 'velocity'
  $fixture.rounds[0].bedrock = $null
  Assert-ContractRejected $fixture 'recovery' 'relay' 'velocity' "Scenario group 'recovery' requires"
  $fixture = New-ContractSummary 'proxy-switch' 'relay' 'bungee' 'bukkit2'
  $fixture.rounds[0].secondaryJava = 'bukkit'
  Assert-ContractRejected $fixture 'proxy-switch' 'relay' 'bungee' "Scenario group 'proxy-switch' requires" 'bukkit'
  $fixture = New-ContractSummary 'proxy-switch' 'relay' 'bungee' 'bukkit2'
  $fixture.rounds[0].proxySwitchDriver = 'fixtureNativeAPI'
  Assert-ContractRejected $fixture 'proxy-switch' 'relay' 'bungee' 'recorded player-command evidence' 'bukkit'
  $fixture = New-ContractSummary 'channel-lifecycle' 'relay' 'velocity' 'bukkit2'
  $fixture.rounds[0].secondaryJava = $null
  Assert-ContractRejected $fixture 'channel-lifecycle' 'relay' 'velocity' "Scenario group 'channel-lifecycle' requires" 'bukkit'
  $fixture = New-ContractSummary 'core' 'relay' 'bungee' 'bukkit2'
  Assert-ContractRejected $fixture 'core' 'relay' 'bungee' 'Unexpected secondary Java leg'
  $fixture = New-ContractSummary 'core' 'relay' 'bungee'
  Assert-ContractRejected $fixture 'core' 'relay' 'bungee' 'Requested secondary Bukkit' 'bukkit'

  $fixture = New-ContractSummary 'core' 'lite' 'bungee'
  $fixture.rounds[0].assertions += $fixture.rounds[0].assertions[0]
  Assert-ContractRejected $fixture 'core' 'lite' 'bungee' 'Required gating assertion'
  $fixture = New-ContractSummary 'core' 'lite' 'bungee'
  $fixture.rounds[0].assertions[0].pass = 'true'
  Assert-ContractRejected $fixture 'core' 'lite' 'bungee' 'Required gating assertion'
} finally {
  Remove-Item -LiteralPath $summaryFile -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $summaryDir
}
Write-Host '[SELFTEST] Joint scenario and summary checks complete; no servers started'
