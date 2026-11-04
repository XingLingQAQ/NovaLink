. (Join-Path $PSScriptRoot 'helpers.ps1')

function Get-JointRenderCoordinationAssertionIds {
  param([int]$RoundIndex)
  foreach ($role in @('target', 'observer')) {
    foreach ($check in @('session', 'title', 'chat')) { "round$RoundIndex/render-coordination/$role-$check" }
  }
}

function Read-JointRenderEvents {
  param([string]$Path)
  $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
    ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
  $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true)
  try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
  $text = [regex]::Replace($text, ([string][char]27 + '\[[0-?]*[ -/]*[@-~]'), '')
  foreach ($line in $text -split "`n") {
    if ($line -notmatch '(?:^|\[Debug\]\s+)NOVA_RENDER_EVENT (?<json>\{.*\})\s*$') { continue }
    $event = $Matches.json | ConvertFrom-Json -ErrorAction Stop
    $playerId = [guid]::Empty; $session = [guid]::Empty
    if ($event.version -ne 1 -or $event.server -cne 'bukkit' -or
        -not [guid]::TryParse([string]$event.playerId, [ref]$playerId) -or $playerId -eq [guid]::Empty -or
        -not [guid]::TryParse([string]$event.session, [ref]$session) -or $session -eq [guid]::Empty) {
      throw 'Invalid render coordination event identity or version'
    }
    $event
  }
}

function Get-JointRenderCoordinationAssertions {
  param([string]$RoundDir, [string]$RunId, [string]$ProxyPlatform, [int]$RoundIndex = 1)
  $proxyEvents = @(Read-JointRenderEvents (Join-Path $RoundDir "$ProxyPlatform/proxy.stdout.log"))
  $serverEvents = @(Read-JointRenderEvents (Join-Path $RoundDir 'bukkit/server.stdout.log'))
  $names = @{ target = 'E2ECustomTarget'; observer = 'E2ECustomWatch' }
  $channels = @{ target = 'global'; observer = 'local' }
  $customRun = "$RunId-r$RoundIndex-custom-effects"
  $botDir = Join-Path $RoundDir "bots/custom-effects/$customRun"
  foreach ($role in @('target', 'observer')) {
    $bot = [IO.File]::ReadAllText((Join-Path $botDir "$role.results.json")) | ConvertFrom-Json
    $self = @($bot.received | Where-Object { $_.kind -ceq 'playerJoined' -and $_.data.player -ceq $names[$role] } |
      ForEach-Object { [string]$_.data.uuid } | Select-Object -Unique)
    $complete = $bot.runId -ceq $customRun -and $bot.username -ceq $names[$role] -and $bot.kind -ceq 'java' -and
      -not $bot.error -and $bot.timedOut -eq $false -and -not $bot.state.kicked -and $self.Count -eq 1 -and
      @($bot.received | Where-Object { $_.kind -ceq 'checkpoint' -and $_.key -ceq 'ce-completed' }).Count -eq 1
    $sessions = @()
    if ($complete) {
      $proxySessions = @($proxyEvents | Where-Object { $_.event -ceq 'negotiated' -and $_.playerId -ceq $self[0] } |
        Select-Object -ExpandProperty session -Unique)
      $serverSessions = @($serverEvents | Where-Object { $_.event -ceq 'negotiated' -and $_.playerId -ceq $self[0] } |
        Select-Object -ExpandProperty session -Unique)
      $sessions = @($proxySessions | Where-Object { $_ -cin $serverSessions })
    }
    $sessionOk = $complete -and $sessions.Count -gt 0
    New-JointAssertion -Id "round$RoundIndex/render-coordination/$role-session" -Token $names[$role] -Pass $sessionOk `
      -Evidence "fresh named bot complete=$complete; common authenticated peer sessions=$($sessions.Count)" -Kind 'render-coordination'
    $playerDecisions = @()
    $latestSession = $null
    if ($complete) {
      foreach ($event in $proxyEvents) {
        if ($event.playerId -cne $self[0]) { continue }
        if ($event.event -ceq 'negotiated') { $latestSession = [string]$event.session }
        if ($event.event -ceq 'decision') {
          $playerDecisions += [PSCustomObject]@{ Event = $event; LatestSession = $latestSession }
        }
      }
    }
    $epochsValid = $true
    foreach ($entry in $playerDecisions) {
      if (-not $entry.LatestSession -or $entry.Event.session -cne $entry.LatestSession -or
          $entry.Event.session -cnotin $serverSessions) { $epochsValid = $false }
    }
    foreach ($kind in @(@{ Name = 'title'; Id = 9 }, @{ Name = 'chat'; Id = 3 })) {
      $decisions = @()
      if ($sessionOk) {
        $decisions = @($playerDecisions | Where-Object {
          $_.Event.packetId -eq $kind.Id -and $_.Event.channelId -ceq $channels[$role] -and
          $_.Event.owner -cin @('proxy', 'backend')
        })
      }
      $requestIds = @()
      $valid = $sessionOk -and $epochsValid -and $decisions.Count -gt 0
      foreach ($entry in $decisions) {
        $decision = $entry.Event
        $requestId = [guid]::Empty
        if (-not [guid]::TryParse([string]$decision.requestId, [ref]$requestId) -or $requestId -eq [guid]::Empty) { $valid = $false }
        $requestIds += [string]$decision.requestId
      }
      $valid = $valid -and @($requestIds | Select-Object -Unique).Count -eq $requestIds.Count
      New-JointAssertion -Id "round$RoundIndex/render-coordination/$role-$($kind.Name)" -Token $names[$role] -Pass $valid `
        -Evidence "all decision epochs confirmed and current=$epochsValid; $($kind.Name) decisions=$($decisions.Count); channel=$($channels[$role]); gate=$valid" -Kind 'render-coordination'
    }
  }
}
