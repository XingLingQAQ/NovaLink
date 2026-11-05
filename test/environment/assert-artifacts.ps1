#requires -Version 7.4
[CmdletBinding()]
param([Parameter(Mandatory)][string]$EvidenceRoot, [Parameter(Mandatory)][string[]]$Cases)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'storage-probe.ps1')
. (Join-Path $PSScriptRoot 'evidence-checks.ps1')
$spec = Import-PowerShellDataFile (Join-Path $PSScriptRoot 'cases.psd1')
function Read-Json([string]$Path) { Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Bot($Public, $Phase, $Role, $RunId) {
    $r = Read-Json (Join-Path $Public "bots/$Phase/$Role.results.json")
    Require (Test-EnvironmentBotRecording $r $RunId $Phase $Role (Join-Path $Public "bots/$Phase/handoff")) "Invalid $Phase $Role recording"
    return $r
}
foreach ($case in $Cases) {
    Require ($case -cin $spec.Cases) "Unknown case: $case"
    $matches = @(Get-ChildItem -LiteralPath $EvidenceRoot -Directory | Where-Object { $_.Name.EndsWith("-$case") })
    Require ($matches.Count -eq 1) "Expected one evidence directory for $case; found $($matches.Count)"
    $public = Join-Path $matches[0].FullName 'public'
    $summary = Read-Json (Join-Path $public 'environment-summary.json')
    Require (-not (Test-EnvironmentSyntheticMarker $summary) -and $summary.case -ceq $case -and $summary.status -ceq 'PASS' -and $matches[0].Name -ceq "$($summary.runId)-$case") "Invalid $case summary"
    $assertions = @($summary.assertions)
    $ids = @($assertions | ForEach-Object id)
    Require ($ids.Count -eq @($spec.RequiredAssertions[$case]).Count -and @($ids | Select-Object -Unique).Count -eq $ids.Count -and
        @($spec.RequiredAssertions[$case] | Where-Object { $_ -cnotin $ids }).Count -eq 0 -and
        @($assertions | Where-Object { $_.pass -isnot [bool] -or -not $_.pass }).Count -eq 0) "Missing, duplicate or failed assertions for $case"
    $run = $summary.runId
    $b = Bot $public 'control' 'bukkit' $run; $f = Bot $public 'control' 'fabric' $run
    Require ((Test-EnvironmentDelivery $b $f "$run-control-bukkit") -and (Test-EnvironmentDelivery $f $b "$run-control-fabric")) "$case control delivery missing"
    $initialLog = Get-Content -Raw -LiteralPath (Join-Path $public 'backend/initial/backend-initial.stdout.log')
    foreach ($client in @('ENV_Bukkit', 'ENV_Fabric')) { Require ($initialLog -match "Client authenticated:\s*$client\s+from") "$case initial $client authentication missing" }
    if ($case -in @('mysql', 'postgresql', 'redis')) {
        Assert-EnvironmentStorageEvidence (Read-Json (Join-Path $public 'storage-probe.json')) $case
        $before = Read-Json (Join-Path $public 'row-before.json'); $after = Read-Json (Join-Path $public 'row-after.json')
        Require (Test-EnvironmentGameRow $before $b "$run-control-bukkit") "$case original row does not match game send"
        foreach ($field in @('id', 'channelId', 'senderId', 'senderName', 'clientId', 'content', 'timestamp')) {
            Require ($null -ne $after.$field -and [string]$before.$field -ceq [string]$after.$field) "$case recovered row mismatch: $field"
        }
        $rb = Bot $public 'restarted' 'bukkit' $run; $rf = Bot $public 'restarted' 'fabric' $run
        Require ((Test-EnvironmentDelivery $rb $rf "$run-restarted-bukkit") -and (Test-EnvironmentDelivery $rf $rb "$run-restarted-fabric")) "$case restart delivery missing"
        $postRow = Read-Json (Join-Path $public 'row-post-restart.json')
        Require ((Test-EnvironmentGameRow $postRow $rb "$run-restarted-bukkit") -and [long]$postRow.id -ne [long]$before.id) "$case post-restart row missing or mismatched"
        $restartLog = Get-Content -Raw -LiteralPath (Join-Path $public 'backend/restarted/backend-restarted.stdout.log')
        foreach ($client in @('ENV_Bukkit', 'ENV_Fabric')) { Require ($restartLog -match "Client authenticated:\s*$client\s+from") "$case restart $client authentication missing" }
        Require (Test-EnvironmentRestartLedger (Read-Json (Join-Path $public 'backend-restart.json')) $summary.runId $initialLog $restartLog) "$case backend process restart evidence invalid"
    } elseif ($case -eq 'tls-untrusted') {
        $nb = Bot $public 'rejected' 'bukkit' $run; $nf = Bot $public 'rejected' 'fabric' $run
        Require (Test-EnvironmentGameRow (Read-Json (Join-Path $public 'row-rejected.json')) $nb "$run-rejected-bukkit") 'TLS negative sender row missing'
        Require (Test-EnvironmentNegativeEvidence $nb $nf "$run-rejected-bukkit" (Join-Path $public 'bots/rejected/handoff') "$run-rejected") 'TLS negative observation missing or incomplete'
        $newLog = Get-Content -Raw -LiteralPath (Join-Path $public 'backend/rejection-window/window.stdout.log')
        Require ($newLog -notmatch 'Client authenticated:\s*ENV_Fabric\s+from') 'Rejected client authenticated'
        $failure = (Get-Content -Raw -LiteralPath (Join-Path $public 'fabric-rejected/fabric-rejected.stdout.log')) + (Get-Content -Raw -LiteralPath (Join-Path $public 'fabric-rejected/fabric-rejected.stderr.log'))
        Require ($failure -match 'PKIX path building failed|unable to find valid certification path|certificate_unknown|CERTIFICATE_VERIFY_FAILED|unable to get local issuer certificate') 'TLS certificate rejection missing'
    }
    Write-Output "$case evidence PASS"
}
