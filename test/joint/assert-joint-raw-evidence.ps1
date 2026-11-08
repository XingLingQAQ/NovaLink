[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$RoundDir,
  [Parameter(Mandatory = $true)][string]$RunId,
  [Parameter(Mandatory = $true)][ValidateSet('relay','lite')][string]$Mode,
  [Parameter(Mandatory = $true)][ValidateSet('velocity','bungee')][string]$Proxy,
  [Parameter(Mandatory = $true)][string]$Bedrock,
  [Parameter(Mandatory = $true)][string[]]$Groups,
  [string]$Secondary = 'none',
  [switch]$RequireRenderEffects
)
$ErrorActionPreference = 'Stop'
foreach ($name in @('business','extended-business','lite-moderation','recovery','proxy-switch','channel-lifecycle','social-contract','custom-effects','completion','registry')) {
  . (Join-Path $PSScriptRoot "scenarios/$name.ps1")
}
. (Join-Path $PSScriptRoot 'evidence-checks.ps1')
function Read-LogText([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing raw log: $Path" }
  $text = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
  if ([string]::IsNullOrWhiteSpace($text)) { throw "Empty raw log: $Path" }
  $escape = [string][char]27
  $text = [regex]::Replace($text, ($escape + '\][^\x07\x1B]*(?:\x07|' + $escape + '\\)'), '')
  $text = [regex]::Replace($text, ($escape + '\[[0-?]*[ -/]*[@-~]'), '')
  return [regex]::Replace($text, '(?m)^[ \t]*(?:>[ \t]*)+', '')
}
function Read-Raw([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing raw evidence: $Path" }
  $data = [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
  if ([string]::IsNullOrWhiteSpace($data)) { throw "Empty raw evidence: $Path" }
  $result = $data | ConvertFrom-Json
  foreach ($row in @($result)) {
    if ($row -and $row.PSObject.Properties['synthetic'] -and ($row.synthetic -isnot [bool] -or $row.synthetic)) { throw "Synthetic or invalid raw evidence: $Path" }
  }
  return $result
}
function Test-JointAcceptanceRecording($Recording) {
  if (-not (Test-JointBotRecording $Recording)) { return $false }
  $spawns = @($Recording.received | Where-Object {
    $_.kind -ceq 'spawn' -and $_.raw -ceq 'spawned' -and $_.text -ceq 'spawned' -and $_.atMs -gt 0
  })
  if ($spawns.Count -eq 0) { return $false }
  foreach ($row in @($Recording.received) + @($Recording.sent)) {
    if ($null -eq $row) { continue }
    if ($row.kind -in @('error', 'planError', 'kicked')) { return $false }
    if ($row.PSObject.Properties['synthetic'] -and ($row.synthetic -isnot [bool] -or $row.synthetic)) { return $false }
  }
  return $true
}
function Read-Role([string]$Dir, [string]$File, [string]$ExpectedRun, [string]$Role, [string]$Kind = 'java') {
  $r = Read-Raw (Join-Path $Dir "$File.results.json")
  if ($r.synthetic -eq $true -or $r.runId -cne $ExpectedRun -or $r.role -cne $Role -or $r.kind -cne $Kind -or
      $r.host -cne '127.0.0.1' -or $r.port -le 0 -or
      -not (Test-JointAcceptanceRecording $r) -or $r.error -or $r.timedOut) { throw "Invalid raw bot recording: $Dir/$File.results.json" }
  return $r
}
function Assert-Rows([object[]]$Rows, [string[]]$Ids) {
  foreach ($id in $Ids) {
    $hits = @($Rows | Where-Object { $_.id -ceq $id })
    if ($hits.Count -ne 1 -or $hits[0].kind -eq 'diagnostic' -or $hits[0].pass -isnot [bool] -or -not $hits[0].pass) {
      throw "Raw scorer failed or missing: $id"
    }
  }
}
function Assert-Processes([string]$Dir, [string]$ExpectedRun, [string[]]$Roles) {
  $files = @(Get-ChildItem -LiteralPath $Dir -Filter 'plan-*.process.json' -File -ErrorAction SilentlyContinue)
  $receipts = @($files | ForEach-Object { Read-Raw $_.FullName })
  $pids = @()
  foreach ($role in $Roles) {
    $hit = @($receipts | Where-Object { $_.runId -ceq $ExpectedRun -and $_.role -ceq $role })
    if ($hit.Count -ne 1 -or ($hit[0].exitCode -isnot [int] -and $hit[0].exitCode -isnot [long]) -or $hit[0].exitCode -ne 0 -or [long]$hit[0].processId -le 0) {
      throw "Missing successful process receipt: $Dir role=$role runId=$ExpectedRun"
    }
    $pids += $hit[0].processId
  }
  if (@($pids | Select-Object -Unique).Count -ne $Roles.Count) { throw "Bot process IDs are not distinct: $Dir" }
}
function Get-Work([string]$Base, [string]$ExpectedRun, [string]$File) {
  $root = Join-Path $RoundDir "bots/$Base"
  if (-not (Test-Path -LiteralPath $root -PathType Container)) { throw "Missing raw directory: $root" }
  $hits = @(Get-ChildItem -LiteralPath $root -Recurse -Filter "$File.results.json" -File | Where-Object {
    $r = Read-Raw $_.FullName; $r.runId -ceq $ExpectedRun
  })
  if ($hits.Count -ne 1) { throw "Expected one $Base/$File recording for $ExpectedRun; found $($hits.Count)" }
  return $hits[0].DirectoryName
}
function Group-Ids([string]$Group) {
  $all = @(Get-JointScenarioAssertionIds -ScenarioGroups @($Group) -RoundIndex 1 -Mode $Mode -Proxy $Proxy)
  $core = @(Get-JointScenarioAssertionIds -ScenarioGroups @('core') -RoundIndex 1 -Mode $Mode -Proxy $Proxy)
  $separate = @('round1/lite-moderation/drive', 'round1/social-contract/drive',
    'round1/custom-effects/drive', 'round1/completion/drive', 'round1/recovery/backend-process-restarted')
  return @($all | Where-Object { $_ -cnotin $core -and $_ -cnotin $separate })
}
function Assert-ListeningPort([string]$LogPath, [string]$Marker, [int]$Port) {
  $log = Read-LogText $LogPath
  if ($Port -le 0 -or $log -notmatch ($Marker + '.*127\.0\.0\.1:' + $Port + '(?:\D|$)')) {
    throw "Missing listener evidence for port $Port in $LogPath"
  }
}
$prefix = "$RunId-r1"
$proxyLog = Join-Path $RoundDir "$Proxy/proxy.stdout.log"
$backendLog = Join-Path $RoundDir 'backend/stdout.log'
$businessDir = Join-Path $RoundDir 'bots/business'
$businessAlice = Read-Role $businessDir 'alice' "$prefix-business" 'business-alice'
$businessBob = Read-Role $businessDir 'bob' "$prefix-business" 'business-bob'
if ($businessAlice.username -cne 'E2EBizAlice' -or $businessBob.username -cne 'E2EBizBob') { throw 'Core business bot identity mismatch' }
if ($businessAlice.port -ne $businessBob.port) { throw 'Core business proxy ports differ' }
Assert-ListeningPort $proxyLog 'Listening on' $businessAlice.port
$coreScores = @(Assert-JointBusinessResults -RoundIndex 1 -RunId "$prefix-business" -AliceRes $businessAlice -BobRes $businessBob)
$coreIds = @(Get-JointScenarioAssertionIds -ScenarioGroups @('core') -RoundIndex 1 -Mode $Mode -Proxy $Proxy |
  Where-Object { $_ -clike 'round1/iso/*' -or $_ -clike 'round1/business/*' })
Assert-Rows $coreScores $coreIds
if ($Mode -eq 'lite') {
  $work = Join-Path $RoundDir 'bots'; $rawRun = "$prefix-lite"
  $alice = Read-Role $work 'alice' $rawRun 'alice'
  $bob = Read-Role $work 'bob' $rawRun 'bob'
  $down = Read-Role (Join-Path $work 'downstream') 'downstream' $rawRun 'downstream'
  if ($alice.username -cne 'E2ELiteAlice' -or $bob.username -cne 'E2ELiteBob' -or $down.username -cne 'E2EDownstream' -or
      $alice.host -cne '127.0.0.1' -or $bob.host -cne $alice.host -or $down.host -cne $alice.host -or
      $alice.port -le 0 -or $alice.port -ne $bob.port -or $down.port -le 0 -or $down.port -eq $alice.port -or
      @($alice.sent | Where-Object { ([string]$_.text).Contains("$prefix-lite-fanout") }).Count -ne 1 -or
      @($down.sent | Where-Object { ([string]$_.text).Contains("$prefix-downstream-chat") }).Count -ne 1) {
    throw 'Lite raw recordings do not establish the requested proxy/downstream topology and sends'
  }
  Assert-ListeningPort $proxyLog 'Listening on' $alice.port
  Assert-ListeningPort (Join-Path $RoundDir 'bukkit/server.stdout.log') 'Starting Minecraft server on' $down.port
  $scores = @(
    Assert-LogContains 'round1/lite-mode-active' 'local-authority' (Read-LogText $proxyLog) 'NovaChat running in lite mode \(local authority, no backend connection\)'
    Assert-Delivered 'round1/downstream-upstream-roundtrip' "$prefix-downstream-chat" $down
    Assert-Delivered 'round1/proxy-to-downstream-chat' "$prefix-lite-fanout" $down
  )
  Assert-Rows $scores @('round1/lite-mode-active','round1/downstream-upstream-roundtrip','round1/proxy-to-downstream-chat')
  if ($Proxy -eq 'velocity') {
    $mention = @($bob.received | Where-Object { $_.kind -eq 'messagestr' -and ([string]$_.text).Contains("$prefix-lite-mention") })
    $title = @($bob.received | Where-Object { $_.kind -eq 'title' -and ([string]$_.text).Contains('E2ELiteAlice') -and $mention.Count -gt 0 -and $_.atMs -ge $mention[0].atMs })
    $sounds = @($bob.received | Where-Object { $_.kind -eq 'sound' -and $_.data.sound -ceq 'minecraft:entity.experience_orb.pickup' -and $title.Count -gt 0 -and $_.atMs -ge $title[0].atMs -and $_.atMs -le ($title[0].atMs + 8000) })
    if (-not $sounds.Count) { throw 'Missing raw bob/expect-mention-sound after mention' }
  }
} else {
  $work = Join-Path $RoundDir 'bots'; $rawRun = $prefix
  $java = Read-Role $work 'java' $rawRun 'java'
  $bed = Read-Role $work 'bedrock' $rawRun 'bedrock' 'bedrock'
  if ($java.username -cne 'E2EJointJava' -or $bed.username -cne 'E2EJointBedrock' -or
      @($java.sent | Where-Object { ([string]$_.text).Contains("$prefix-java-to-bedrock") }).Count -ne 1 -or
      @($bed.sent | Where-Object { ([string]$_.text).Contains("$prefix-bedrock-to-java") }).Count -ne 1) {
    throw 'Relay raw recordings do not establish the requested senders and sends'
  }
  Assert-ListeningPort $proxyLog 'Listening on' $java.port
  $servers = @('bukkit'); if ($Secondary -eq 'bukkit') { $servers += 'bukkit2' }
  $identities = @($servers | ForEach-Object { "E2E_Relay_$_" })
  $scores = @(
    Assert-ProxyPluginLoaded 'round1/proxy-plugin-loaded' $Proxy (Read-LogText $proxyLog)
    Assert-RelayLinksCreated 'round1/relay-links-created' (Read-LogText $proxyLog) $servers
    Assert-RelayDistinctIdentities 'round1/relay-distinct-backend-identities' (Read-LogText $backendLog) $identities @('E2E_JointProxy')
    Assert-Delivered 'round1/java-to-bedrock' "$prefix-java-to-bedrock" $bed
    Assert-Delivered 'round1/bedrock-to-java' "$prefix-bedrock-to-java" $java
  )
  Assert-Rows $scores @('round1/proxy-plugin-loaded','round1/relay-links-created','round1/relay-distinct-backend-identities','round1/java-to-bedrock','round1/bedrock-to-java')
  $ledger = Read-Raw (Join-Path $RoundDir 'backend/relay-attribution.results.json')
  $token = "$prefix-java-to-bedrock"
  $messages = @($ledger.messages | Where-Object { ([string]$_.content).Contains($token) })
  $ids = @($messages | ForEach-Object { $_.clientId } | Sort-Object -Unique)
  if ($ledger.runId -cne $RunId -or $ledger.token -cne $token -or $messages.Count -eq 0 -or $ids.Count -ne 1 -or $ids[0] -cne 'E2E_Relay_bukkit') {
    throw 'Raw backend attribution does not establish round1/relay-chat-attribution-and-delivery'
  }
  if ($Bedrock -eq 'pnx') {
    . (Join-Path $PSScriptRoot '../versions.lock.ps1')
    $lock = @(Get-LockedServer -Name 'powernukkitx')
    $pnx = Read-LogText (Join-Path $RoundDir 'pnx/server.stdout.log')
    if ($pnx -notmatch ('This server is running PowerNukkitX version ' + [regex]::Escape($lock.Build) + '(?:-SNAPSHOT)?\s+[^\r\n]*\(API 3\.\d+(?:\.\d+)*\)') -or $pnx -notmatch 'NovaChat-PNX enabled successfully!') { throw 'Raw PNX runtime or plugin evidence missing' }
  }
}
foreach ($group in $Groups | Where-Object { $_ -ne 'core' }) {
  $scores = @(); $work = ''; $rr = "$prefix-$group"; $roles = @()
  switch ($group) {
    'business' {
      $work = Join-Path $RoundDir 'bots/extended-business'; $rr = "$prefix-extended"
      $s = Read-Role $work 'sender' $rr 'extended-sender'; $t = Read-Role $work 'target' $rr 'extended-target'; $o = Read-Role $work 'observer' $rr 'extended-observer'
      if ($s.username -cne 'E2EExtSender' -or $t.username -cne 'E2EExtTarget' -or $o.username -cne 'E2EExtWatch' -or $s.port -ne $t.port -or $s.port -ne $o.port) { throw 'Extended business bot identity or topology mismatch' }
      Assert-ListeningPort $proxyLog 'Listening on' $s.port
      $requests = @(Read-Raw (Join-Path $work 'admin-requests.json'))
      if ($requests.Count -ne 2 -or $requests[0].command -cne 'nc mute E2EExtSender 5m global' -or
          $requests[1].command -cne 'nc unmute E2EExtSender global' -or
          -not $requests[0].requestedAt -or -not $requests[1].requestedAt -or
          [datetimeoffset]$requests[0].requestedAt -ge [datetimeoffset]$requests[1].requestedAt) { throw 'Missing ordered extended business console requests' }
      $scores = @(Assert-ExtendedBusinessResults 1 $rr $s $t $o); $roles = @('extended-sender','extended-target','extended-observer')
    }
    'lite-moderation' {
      $work = Join-Path $RoundDir 'bots/lite-moderation'
      $t = Read-Role $work 'target' $rr 'lite-mod-target'; $o = Read-Role $work 'observer' $rr 'lite-mod-observer'
      if ($t.username -cne 'E2ELModTarget' -or $o.username -cne 'E2ELModPeer' -or $t.port -ne $o.port) { throw 'Lite moderation bot identity or topology mismatch' }
      Assert-ListeningPort $proxyLog 'Listening on' $t.port
      $requests = @(Read-Raw (Join-Path $work 'admin-requests.json'))
      $expectedCommands = @(Get-JointLiteModerationStages | Where-Object { $_.Admin } | ForEach-Object { "nc $($_.Admin)" })
      if ($requests.Count -ne $expectedCommands.Count) { throw 'Missing Lite moderation console command receipts' }
      for ($i = 0; $i -lt $expectedCommands.Count; $i++) {
        if ($requests[$i].runId -cne $rr -or $requests[$i].command -cne $expectedCommands[$i] -or
            [long]$requests[$i].processId -le 0 -or -not $requests[$i].writtenAt -or
            ($i -gt 0 -and ([datetimeoffset]$requests[$i].writtenAt -lt [datetimeoffset]$requests[$i-1].writtenAt -or
              $requests[$i].processId -ne $requests[0].processId))) { throw 'Invalid Lite moderation console command receipt' }
      }
      $scores = @(Assert-JointLiteModerationResults 1 $rr $t $o); $roles = @('lite-mod-target','lite-mod-observer')
    }
    'recovery' {
      $work = Join-Path $RoundDir 'bots/recovery'
      $j = Read-Role $work 'Java' $rr 'Java'; $b = Read-Role $work 'Bedrock' $rr 'Bedrock' 'bedrock'
      if ($j.username -cne 'E2ERecoverJava' -or $b.username -cne 'E2ERecoverBed') { throw 'Recovery bot identity mismatch' }
      Assert-ListeningPort $proxyLog 'Listening on' $j.port
      $scores = @(Assert-JointRecoveryResults 1 $rr $j $b 'E2ERecoverJava' 'E2ERecoverBed' 'local')
      $receipt = Read-Raw (Join-Path $RoundDir 'backend/restart-process.results.json')
      if ($receipt.runId -cne $rr -or [long]$receipt.stoppedProcessId -le 0 -or [long]$receipt.startedProcessId -le 0 -or $receipt.stoppedProcessId -eq $receipt.startedProcessId) { throw 'Missing raw recovery backend-process-restarted evidence' }
      $before = Get-BackendAuthIdentities (Read-LogText $backendLog)
      $after = Get-BackendAuthIdentities (Read-LogText (Join-Path $RoundDir 'backend/restart-1/stdout.log'))
      $missing = @($before.Identities | Sort-Object -Unique | Where-Object { $_ -cnotin $after.Identities })
      if (@($before.Identities).Count -eq 0 -or $missing.Count) { throw "Recovery backend reauthentication missing: $($missing -join ', ')" }
      $roles = @('Java','Bedrock')
    }
    'proxy-switch' {
      $work = Join-Path $RoundDir 'bots/proxy-switch'
      $m = Read-Role $work 'Mover' $rr 'Mover'; $l = Read-Role $work 'Left' $rr 'Left'; $r = Read-Role $work 'Right' $rr 'Right'
      if ($m.username -cne 'E2ESwitchMover' -or $l.username -cne 'E2ESwitchLeft' -or $r.username -cne 'E2ESwitchRight' -or $m.port -ne $l.port -or $m.port -ne $r.port) { throw 'Proxy switch bot identity or topology mismatch' }
      Assert-ListeningPort $proxyLog 'Listening on' $m.port
      $scores = @(Assert-JointProxySwitchResults 1 $rr $m $l $r 'global' 'joint-switch-owned' -SwitchMode 'player-command' -PrimaryServer 'bukkit' -SecondaryServer 'bukkit2')
      $roles = @('Mover','Left','Right')
    }
    'channel-lifecycle' {
      $work = Join-Path $RoundDir 'bots/channel-lifecycle'
      $o = Read-Role $work 'owner' $rr 'channel-lifecycle-owner'; $i = Read-Role $work 'invitee' $rr 'channel-lifecycle-invitee'; $w = Read-Role $work 'observer' $rr 'channel-lifecycle-observer'
      Assert-ListeningPort (Join-Path $RoundDir 'bukkit/server.stdout.log') 'Starting Minecraft server on' $o.port
      Assert-ListeningPort (Join-Path $RoundDir 'bukkit2/server.stdout.log') 'Starting Minecraft server on' $i.port
      $scores = @(Assert-JointChannelLifecycleResults 1 $rr $o.port $i.port $o $i $w)
      $roles = @('channel-lifecycle-owner','channel-lifecycle-invitee','channel-lifecycle-observer')
    }
    'social-contract' {
      $root = Join-Path $RoundDir 'bots/social-contract'
      $files = @(Get-ChildItem -LiteralPath $root -Recurse -Filter 'sender.results.json' -File -ErrorAction SilentlyContinue | Where-Object { (Read-Raw $_.FullName).runId -cmatch ('^' + [regex]::Escape("$prefix-social-") + '[0-9a-f]{8}$') })
      if ($files.Count -ne 1) { throw "Expected one social-contract sender for $prefix; found $($files.Count)" }
      $work = $files[0].DirectoryName; $rr = (Read-Raw $files[0].FullName).runId
      $s = Read-Role $work 'sender' $rr 'social-sender'; $t = Read-Role $work 'target' $rr 'social-target'; $o = Read-Role $work 'observer' $rr 'social-observer'
      if ($s.port -ne $t.port -or $s.port -ne $o.port) { throw 'Social contract proxy ports differ' }
      Assert-ListeningPort $proxyLog 'Listening on' $s.port
      $forbidden = @(); if ('proxy-switch' -in $Groups) { $forbidden += 'joint-switch-owned' }
      $scores = @(Assert-JointSocialContractResults 1 $rr $s $t $o -ChannelScopes @{ global='GLOBAL'; local='GLOBAL' } -ForbiddenChannelIds $forbidden); $roles = @('social-sender','social-target','social-observer')
    }
    'custom-effects' {
      $work = Get-Work 'custom-effects' $rr 'sender'
      $s = Read-Role $work 'sender' $rr 'custom-effects-sender'; $t = Read-Role $work 'target' $rr 'custom-effects-target'; $o = Read-Role $work 'observer' $rr 'custom-effects-observer'
      if ($t.port -ne $o.port -or $s.port -eq $t.port) { throw 'Custom effects bot topology mismatch' }
      Assert-ListeningPort $proxyLog 'Listening on' $t.port
      Assert-ListeningPort (Join-Path $RoundDir 'bukkit/server.stdout.log') 'Starting Minecraft server on' $s.port
      $scores = @(Assert-CustomEffectsResults 1 $rr $s $t $o); $roles = @('custom-effects-sender','custom-effects-target','custom-effects-observer')
    }
    'completion' {
      $work = Get-Work 'completion' $rr 'requester'
      $q = Read-Role $work 'requester' $rr 'completion-requester'; $t = Read-Role $work 'target' $rr 'completion-target'
      if ($q.port -ne $t.port) { throw 'Completion proxy ports differ' }
      Assert-ListeningPort $proxyLog 'Listening on' $q.port
      $plans = Get-JointCompletionPlans -RunId $rr -Proxy $Proxy
      $changes = @()
      foreach ($line in ((Read-LogText $proxyLog) -split "`n")) {
        $match = [regex]::Match($line, 'JOINT_COMPLETION_PERMISSION (\{[^\r\n]*\})')
        if (-not $match.Success) { continue }
        $receipt = $match.Groups[1].Value | ConvertFrom-Json
        if ($receipt.player -cne $plans.Names.Requester -or $receipt.node -cne 'novachat.feature.mention') { continue }
        if ($receipt.ok -ne $true -or $receipt.allowed -isnot [bool] -or $receipt.online -isnot [bool] -or
            (-not $receipt.allowed -and -not $receipt.online) -or
            ($receipt.online -and $receipt.effective -ne $receipt.allowed) -or
            $receipt.nonce -cnotmatch '^[0-9a-f]{32}$') { throw 'Invalid completion permission provider receipt' }
        $changes += [PSCustomObject]@{ username = $receipt.player; permission = $receipt.node; allowed = $receipt.allowed; evidence = $line; localTime = $line; providerReceipt = $receipt }
      }
      if ($changes.Count -ne 3 -or @($changes.providerReceipt.nonce | Select-Object -Unique).Count -ne 3) { throw 'Missing unique completion permission provider receipts in proxy log' }
      $changesPath = Join-Path $work 'permission-changes.json'
      if (Test-Path -LiteralPath $changesPath -PathType Leaf) {
        $saved = @(Read-Raw $changesPath)
        if ($saved.Count -ne 3) { throw 'Invalid completion permission change ledger' }
        for ($i = 0; $i -lt 3; $i++) {
          if ($saved[$i].providerReceipt.nonce -cne $changes[$i].providerReceipt.nonce) { throw 'Completion permission ledger does not match proxy receipts' }
        }
      }
      $scores = @(Assert-JointCompletionResults 1 $rr $Proxy $plans $q $t $changes); $roles = @('completion-requester','completion-target')
    }
    default { throw "No raw scorer for group $group" }
  }
  Assert-Rows $scores @(Group-Ids $group)
  $driveId = "round1/$group/drive"
  if ($driveId -cin @(Get-JointScenarioAssertionIds -ScenarioGroups @($group) -RoundIndex 1 -Mode $Mode -Proxy $Proxy) -and -not $roles.Count) {
    throw "No raw driver completion check configured for $driveId"
  }
  if ($roles.Count) { Assert-Processes $work $rr $roles }
}
if ($RequireRenderEffects) {
  $rr = "$prefix-render-effects"
  $work = Get-Work 'render-effects' $rr 'source'
  Assert-Processes $work $rr @('render-effects-source','render-effects-target','render-effects-watch')
}
Write-Host "Raw joint evidence recomputed: mode=$Mode groups=[$($Groups -join ',')]"
