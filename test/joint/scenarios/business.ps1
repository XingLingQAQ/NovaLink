. (Join-Path $PSScriptRoot 'helpers.ps1')
. (Join-Path $PSScriptRoot 'isolation.ps1')

function Get-JointBusinessPlans {
  param([string]$RunId)

  $plans = Get-IsolationPlanSteps -RunId $RunId
  $outbound = "$RunId-reconnect-outbound"
  $inbound = "$RunId-reconnect-inbound"
  $plans.Alice += @(
    @{ id = 'recovery-wait-peer'; action = 'handoffGet'; key = 'recovery-peer-ready'; timeoutMs = 60000 },
    @{ id = 'recovery-reconnect'; action = 'reconnect'; timeoutMs = 45000; delayMs = 2000 },
    @{ id = 'recovery-arm-header'; action = 'arm'; token = 'online members (' },
    @{ id = 'recovery-arm-member'; action = 'arm'; token = 'E2EBizAlice' },
    @{ id = 'recovery-who'; action = 'cmd'; text = '/nc who global' },
    @{ id = 'recovery-header'; action = 'expect'; token = 'online members ('; timeoutMs = 20000 },
    @{ id = 'recovery-member'; action = 'expect'; token = 'E2EBizAlice'; timeoutMs = 20000 },
    @{ id = 'recovery-arm-inbound'; action = 'arm'; token = $inbound },
    @{ id = 'recovery-send'; action = 'chat'; text = "reconnected $outbound" },
    @{ id = 'recovery-receive'; action = 'expect'; token = $inbound; timeoutMs = 30000 },
    @{ id = 'recovery-received'; action = 'handoffSet'; key = 'recovery-received'; pattern = [regex]::Escape($inbound) },
    @{ id = 'quit'; action = 'quit' }
  )
  $plans.Bob += @(
    @{ id = 'recovery-arm-outbound'; action = 'arm'; token = $outbound },
    @{ id = 'recovery-peer-ready'; action = 'handoffSet'; key = 'recovery-peer-ready'; pattern = [regex]::Escape($plans.Tokens.Shared) },
    @{ id = 'recovery-receive'; action = 'expect'; token = $outbound; timeoutMs = 90000 },
    @{ id = 'recovery-send'; action = 'chat'; text = "welcome back $inbound" },
    @{ id = 'recovery-wait-received'; action = 'handoffGet'; key = 'recovery-received'; timeoutMs = 40000 },
    @{ id = 'quit'; action = 'quit' }
  )
  $plans.Tokens.Outbound = $outbound
  $plans.Tokens.Inbound = $inbound
  return $plans
}

function Assert-JointBusinessResults {
  param([int]$RoundIndex, [string]$RunId, $AliceRes, $BobRes)

  $prefix = "round$RoundIndex/business"
  foreach ($pair in @(@{ Role = 'alice'; Result = $AliceRes }, @{ Role = 'bob'; Result = $BobRes })) {
    $res = $pair.Result
    $usable = (Test-JointBotRecording $res) -and -not $res.error -and -not $res.timedOut
    New-JointAssertion -Id "$prefix/$($pair.Role)-completed" -Token 'results' -Pass $usable `
      -Evidence "recording=$([bool]$res); error=$($res.error); timedOut=$($res.timedOut)" -Kind 'business'
    if ($res) { Assert-BotExpectations "business-$($pair.Role)" $res }
  }

  Invoke-IsolationScenarios -RoundIndex $RoundIndex -RunId $RunId -AliceRes $AliceRes -BobRes $BobRes

  $reconnect = Find-JointBotVerdict $AliceRes 'recovery-reconnect'
  New-JointAssertion -Id "$prefix/reconnect-spawned" -Token 'same Java username, one connection attempt' `
    -Pass ($null -ne $reconnect -and $reconnect.pass) -Evidence "$($reconnect.evidence)" -Kind 'recovery'

  $spawn = @($AliceRes.received | Where-Object { $_.kind -eq 'reconnect' -and $_.phase -eq 'spawned' }) | Select-Object -Last 1
  $query = @($AliceRes.sent | Where-Object { $_.text -eq '/nc who global' }) | Select-Object -Last 1
  $body = ''
  if ($spawn -and $query -and $query.atMs -ge $spawn.atMs) {
    $lines = @(Get-JointBotRows -BotResult $AliceRes -Kind 'messagestr' -SinceMs $query.atMs)
    for ($i = 0; $i -lt $lines.Count - 1; $i++) {
      if ($lines[$i].text -match 'online members \([1-9][0-9]*\):') {
        $body = [string]$lines[$i + 1].text
        $body = [regex]::Replace($body, ([string][char]0x00A7 + '.|&[0-9a-fk-or]'), '')
        $body = ($body -replace '^\[NovaChat\]\s*', '').Trim()
        break
      }
    }
  }
  New-JointAssertion -Id "$prefix/reconnect-membership" -Token 'E2EBizAlice' `
    -Pass ($body -match '^[A-Za-z0-9_]+(?:,\s*[A-Za-z0-9_]+)*$' -and @($body -split ',\s*') -ccontains 'E2EBizAlice') `
    -Evidence "post-reconnect who body: $body" -Kind 'recovery'

  foreach ($pair in @(
    @{ Name = 'outbound'; Result = $BobRes },
    @{ Name = 'inbound'; Result = $AliceRes }
  )) {
    $token = "$RunId-reconnect-$($pair.Name)"
    $hits = @(Get-JointBotRows -BotResult $pair.Result -Kind 'messagestr' -TextContains $token)
    New-JointAssertion -Id "$prefix/reconnect-$($pair.Name)" -Token $token -Pass ($hits.Count -gt 0) `
      -Evidence "receiver chat matches: $($hits.Count)" -Kind 'recovery'
  }
}

function Invoke-JointBusinessScenarios {
  param($Round, [string]$RoundDir, [string]$McVersion)

  if ($Round.Proxy -notin @('velocity', 'bungee') -or -not $Round.Java) {
    New-JointAssertion -Id "round$($Round.Index)/business/topology" -Token 'Velocity/Bungee with Java downstream' `
      -Pass $false -Evidence 'NOT EXECUTED: business driver requires Velocity/Bungee and a Java downstream' -Kind 'diagnostic'
    return
  }

  $runId = "$($script:RunId)-r$($Round.Index)-business"
  $work = Join-Path $RoundDir 'bots/business'
  $handoffs = Join-Path $work 'handoff'
  New-Item -ItemType Directory -Force -Path $handoffs | Out-Null
  $plans = Get-JointBusinessPlans -RunId $runId
  $bots = @()
  $verdicts = @()
  try {
    foreach ($role in @('Alice', 'Bob')) {
      $name = $role.ToLowerInvariant()
      $planPath = Join-Path $work "plan-$name.json"
      $json = New-CrossServerPlan -role "business-$name" -kind 'java' -username "E2EBiz$role" `
        -port $ProxyPort -mcVersion $McVersion -resultsFile (Join-Path $work "$name.results.json") `
        -handoffDir $handoffs -timeoutMs 240000 -runId $runId -steps $plans.$role
      [System.IO.File]::WriteAllText($planPath, $json, (New-Object System.Text.UTF8Encoding $false))
      $bots += [PSCustomObject]@{ Role = $name; Process = (Start-Bot "business-$name" $planPath $work `
        (Join-Path $work "$name.stdout.log") (Join-Path $work "$name.stderr.log")) }
    }

    if (-not (Wait-Handoff $handoffs $runId 'iso-bob-ready' 45)) { throw 'isolation Bob did not join local' }
    Set-Handoff $handoffs $runId 'iso-phase1' 'go'
    if (-not (Wait-Handoff $handoffs $runId 'iso-alice-ack1' 40)) { throw 'isolation global control missing' }
    if (-not (Wait-Handoff $handoffs $runId 'iso-alice-ready2' 10)) { throw 'isolation Alice did not arm phase 2' }
    Set-Handoff $handoffs $runId 'iso-phase2' 'go'
    if (-not (Wait-Handoff $handoffs $runId 'iso-bob-ack2' 40)) { throw 'isolation local control missing' }
    # Keep Bob in local while both negative windows settle.
    Start-Sleep -Seconds 3
    Set-Handoff $handoffs $runId 'iso-phase3' 'go'
    Wait-Bots $bots 120
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited -or $bot.Process.ExitCode -ne 0) { throw "business $($bot.Role) exited unsuccessfully" }
    }
  } catch {
    $verdicts += New-JointAssertion -Id "round$($Round.Index)/business/drive" -Token 'isolation + reconnect' `
      -Pass $false -Evidence $_.Exception.Message -Kind 'business'
  } finally {
    foreach ($bot in $bots) {
      if (-not $bot.Process.HasExited) { Stop-Process -Id $bot.Process.Id -Force -ErrorAction SilentlyContinue }
    }
  }
  $alice = Read-BotResult (Join-Path $work 'alice.results.json')
  $bob = Read-BotResult (Join-Path $work 'bob.results.json')
  $verdicts += Assert-JointBusinessResults -RoundIndex $Round.Index -RunId $runId -AliceRes $alice -BobRes $bob
  return $verdicts
}
