[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'scenarios/business.ps1')
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
      [PSCustomObject]@{ kind = 'reconnect'; phase = 'spawned'; atMs = 100 },
      (New-Row 'Channel Global online members (2):' 200),
      (New-Row 'E2EBizAlice, E2EBizBob' 201),
      (New-Row 'selftest-reconnect-inbound' 210)
    )
    sent = @([PSCustomObject]@{ text = '/nc who global'; atMs = 190 })
    assertions = @([PSCustomObject]@{ id = 'recovery-reconnect'; pass = $true; evidence = 'spawned'; kind = 'reconnect' })
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
$results.Alice.sent[0].atMs = 90
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and -not $_.pass }).Count -eq 1) 'pre-reconnect who cannot prove restored membership'

$results = New-Results
$results.Alice.received[4].text = '[NovaChat] E2EBizAlice, E2EBizBob'
$verdicts = Get-Verdicts $results
Assert-Check (@($verdicts | Where-Object { $_.id -eq 'round1/business/reconnect-membership' -and $_.pass }).Count -eq 1) 'Bungee prefixed member list accepted'

$results = New-Results
$results.Alice.received[4].text = '<E2EBizAlice> unrelated chat'
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
try {
  foreach ($case in @(@{ Proxy = 'velocity'; Mode = 'relay' }, @{ Proxy = 'velocity'; Mode = 'lite' }, @{ Proxy = 'bungee'; Mode = 'relay' })) {
    $mode = $case.Mode
    $proxy = $case.Proxy
    $assertions = @(Get-Verdicts (New-Results))
    $ids = if ($mode -eq 'relay') {
      @('java-to-bedrock', 'bedrock-to-java', 'proxy-plugin-loaded', 'relay-links-created',
        'relay-distinct-backend-identities', 'relay-chat-attribution-and-delivery')
    } else {
      @('lite-mode-active', 'downstream-upstream-roundtrip', 'proxy-to-downstream-chat')
    }
    foreach ($id in $ids) {
      $assertions += New-JointAssertion -Id "round1/$id" -Token 'synthetic' -Pass $true -Evidence 'fixture'
    }
    $summary = [PSCustomObject]@{
      proxyMode = $mode; overallPass = $true
      rounds = @([PSCustomObject]@{ status = 'PASS'; proxy = $proxy; java = 'bukkit'; mod = $null
        bedrock = $(if ($mode -eq 'relay') { 'nukkit' } else { $null }); assertions = $assertions })
    }
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy
    Assert-Check $true "CI $proxy/$mode accepts complete synthetic evidence"
    $wrongProxy = if ($proxy -eq 'velocity') { 'bungee' } else { 'velocity' }
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $wrongProxy } catch { $rejected = $true }
    Assert-Check $rejected "CI $proxy/$mode rejects wrong proxy"

    $required = $assertions | Where-Object { $_.id -eq 'round1/iso/phase1-isolated-from-bob' }
    $required.kind = 'diagnostic'
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy } catch { $rejected = $true }
    Assert-Check $rejected "CI $proxy/$mode rejects diagnostic substitution"

    $summary.rounds[0].assertions = @($assertions | Where-Object { $_.id -ne $required.id })
    [System.IO.File]::WriteAllText($summaryFile, ($summary | ConvertTo-Json -Depth 12))
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'assert-joint-ci.ps1') -JointRoot $summaryDir -ProxyMode $mode -ProxyPlatform $proxy } catch { $rejected = $true }
    Assert-Check $rejected "CI $proxy/$mode rejects missing assertion"
  }
} finally {
  Remove-Item -LiteralPath $summaryFile -ErrorAction SilentlyContinue
  Remove-Item -LiteralPath $summaryDir
}
Write-Host '[SELFTEST] Joint scenario and summary checks complete; no servers started'
