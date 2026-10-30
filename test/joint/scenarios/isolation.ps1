# --- Area 5: channel isolation -----------------------------------------------
#
# Dot-sourced by run-joint.ps1. Defines two functions and no global state; the
# file parses and loads on its own under PS 5.1.
#
# WHAT IT PROVES, AND WHY IT CAN FAIL
# -----------------------------------
# A line spoken in channel A must reach every player whose ACTIVE channel is A
# and must reach nobody else. Nothing in the harness tests the second half
# today: the existing chat assertions only prove ARRIVAL. Two independent
# render-side filters make the exclusion true -- the proxy's
# (ProxyChatCore.handleChatMessage: channelId.equals(state.getActiveChannel()))
# and, in relay, each platform's own (NetworkClient / ChatInterceptor). The
# backend does NOT filter by channel at all: MessagePipeline.calculateRecipients
# returns every authenticated connection for a GLOBAL channel, so a new adapter
# that renders what it receives leaks every line to every player. Only a
# negative assertion notices.
#
# The negatives are not vacuous, and that is the design:
#   * each phase carries a POSITIVE half on the sender's own recording. Measured
#     (run 20261021-151247 alice.results.json; run 20261021-145517
#     java.results.json): a sender's client records its own channel line as
#     `messagestr` plus a `chat` row. In lite that echo is produced by the same
#     loop that decides who receives the line, so "the sender has its own line"
#     means the fan-out ran, not that a send was attempted;
#   * phase 3 rejoins both bots to the same channel and requires a delivery.
#     That is what turns phase 1/2 silence into "isolation" rather than "the
#     channel stopped working".
#
# A MISSING RECORDING IS NOT AN ABSENCE. Every negative row below requires the
# observing bot to have produced a recording with at least one non-`spawn` line.
# With no recording there is nothing to search, and reporting "no leak" would be
# the same vacuous pass as asserting on a token that was never sent.
#
# EVIDENCE RULE
# -------------
# Every verdict is computed from the RECEIVER's recording (the Assert-Delivered
# / Assert-Absent contract), never from the sender's claim and never from a
# handoff. Only `text` is searched, never `raw`: for effect rows `raw` is the
# JSON payload (joint-bot.js record()), so a structured field could satisfy a
# chat assertion the player never saw.
#
# WHAT IT DOES NOT SEE (stated, not asserted away)
# ------------------------------------------------
#   * A direct-connect bot on the downstream leg (lite E2EDownstream, relay mod)
#     is deliberately NOT an observer: an absence there would also hold if the
#     sub-server stopped rendering chat altogether, which is the vacuous
#     negative COVERAGE-PLAN.md warns about.
#   * Leakage into a platform that never renders chat is indistinguishable from
#     correct filtering; this function does not claim it.
#   * The relay REST record (plan 5.4) is a DIAGNOSTIC when -RelayJwt is given,
#     never gated: a memory DB plus an async log writer makes a missing row
#     ambiguous.

function Invoke-IsolationScenarios {
  <#
  .SYNOPSIS
    Channel-isolation assertions for the joint E2E harness (coverage plan area 5).

  .DESCRIPTION
    Returns an array of assertion objects in the harness's existing shape:
        [PSCustomObject]@{ id; token; pass; evidence; kind }
    `kind = 'diagnostic'` reports but never gates (run-joint.ps1:4176, :4570).
    Every other kind gates.

    Never runs anything and never claims a pass it did not derive from a
    recording. Two call modes:

    1. STAGED / NOT WIRED (safe default). Omit -AliceRes / -BobRes. With no bot
       results every assertion is `diagnostic` with evidence starting
       'NOT EXECUTED: not wired'. Wiring the call cannot turn a green round red.

    2. WIRED. Pass both parsed results.json objects. Each assertion is computed
       from the named receiver's recording and gates.

  .PARAMETER RoundIndex
    Round number; builds assertion ids ('round<N>/iso/...').

  .PARAMETER RunId
    The round's token prefix, i.e. "$($script:RunId)-r$($round.Index)" in
    run-joint.ps1. Tokens are "<RunId>-iso-<name>". Must match
    Get-IsolationPlanSteps -RunId.

  .PARAMETER ChannelA / ChannelB
    Channel ids. Defaults 'global' / 'local': lite already declares both in
    [lite.channels] (Write-ProxyPluginConfig). Relay declares only 'global' in
    Write-BackendConfig, so ChannelB must be added there before relay can run it.

  .PARAMETER JoinAckToken
    Prefix of the success acknowledgement. Default 'Joined channel'
    (chat.join.joined = 'Joined channel {0}'). The channel id is appended to
    make each join unambiguous, so a second join cannot be satisfied by the
    first one's ack. `Joining channel ...` is emitted OPTIMISTICALLY before the
    authority answers (measured: bob's recording has `Joining channel
    nosuchchannel...` followed by NC-404), which is why the success form is the
    one matched. A localized client renders a different string; pass it here or
    the setup rows fail loudly instead of silently weakening the negatives.

  .PARAMETER AliceRes / BobRes
    Parsed result objects (Read-BotResult). Alice is in ChannelA, Bob in
    ChannelB. BOTH are required for any gating verdict.

  .PARAMETER RelayJwt
    Optional panel JWT (Connect-BackendRest). Adds one diagnostic row.

  .EXAMPLE
    # staged
    $a = Invoke-IsolationScenarios -RoundIndex 1 -RunId "$RunId-r1"

    # wired
    $a = Invoke-IsolationScenarios -RoundIndex $round.Index -RunId $tokenPrefix `
           -AliceRes $aliceRes -BobRes $bobRes
  #>
  param(
    [int]$RoundIndex,
    [string]$RunId,
    [string]$ChannelA = 'global',
    [string]$ChannelB = 'local',
    [string]$JoinAckToken = 'Joined channel',
    $AliceRes = $null,
    $BobRes = $null,
    [string]$RelayJwt = ''
  )

  $prefix = "round$RoundIndex/iso"
  $out = @()

  # One token per logical line, never reused: a token shared with another
  # scenario could be satisfied by the wrong line, and a negative on a reused
  # token would fail because of the other scenario's traffic.
  $tControl = "$RunId-iso-control"   # alice speaks in A
  $tBobLine = "$RunId-iso-fromb"     # bob speaks in B
  $tShared  = "$RunId-iso-shared"    # alice speaks after both are in A

  # ---- evidence helpers -----------------------------------------------------
  # A recording exists only if the bot produced inbound lines beyond its own
  # spawn marker. This is the guard that stops an absence row from passing
  # against a null/crashed recording.
  $hasRecording = {
    param($res)
    if (-not $res -or -not $res.received) { return $false }
    foreach ($e in $res.received) {
      if ($e.kind -ne 'spawn' -and $e.kind -ne 'end' -and $e.kind -ne 'close') { return $true }
    }
    return $false
  }
  $hitsFor = {
    param($res, [string]$token)
    $hits = @()
    if (-not $res -or -not $res.received -or -not $token) { return $hits }
    foreach ($e in $res.received) {
      if ($e.kind -eq 'spawn' -or $e.kind -eq 'end' -or $e.kind -eq 'close') { continue }
      if ($e.text -and ([string]$e.text).Contains($token)) { $hits += $e }
    }
    return $hits
  }
  $firstLine = {
    param($hits, [string]$who)
    if (@($hits).Count -eq 0) { return '' }
    "$who recorded $($hits[0].kind): $($hits[0].text)"
  }

  # ---- stage detection ------------------------------------------------------
  # "Not wired" is decided from the evidence, not from a flag. BOTH results must
  # carry a recording: a missing one would make every absence row below pass.
  $aliceOk = & $hasRecording $AliceRes
  $bobOk = & $hasRecording $BobRes

  if (-not ($aliceOk -and $bobOk)) {
    $missing = @()
    if (-not $aliceOk) { $missing += 'alice' }
    if (-not $bobOk) { $missing += 'bob' }
    $whyNot = if (-not ($AliceRes -or $BobRes)) {
      'NOT EXECUTED: not wired (no bot results passed; call with -AliceRes and -BobRes after Wait-Bots)'
    } else {
      "NOT EXECUTED: not wired (no usable recording from: $($missing -join ', '); an absence cannot be judged against a missing recording)"
    }
    $rows = @(
      @{ Id = 'phase1-alice-line-accepted';      Token = $tControl;  What = "alice records her OWN line in '$ChannelA' (positive half)" }
      @{ Id = 'phase1-isolated-from-bob';        Token = $tControl;  What = "bob, whose active channel is '$ChannelB', never receives it" }
      @{ Id = 'phase2-bob-line-accepted';        Token = $tBobLine;  What = "bob records his OWN line in '$ChannelB' (positive half)" }
      @{ Id = 'phase2-isolated-from-alice';      Token = $tBobLine;  What = "alice, whose active channel is '$ChannelA', never receives it" }
      @{ Id = 'phase3-shared-channel-delivered'; Token = $tShared;   What = "after both are in '$ChannelA', alice's line IS delivered to bob" }
      @{ Id = 'join-b-ack-observed';             Token = "$JoinAckToken $ChannelB"; What = "bob acknowledged joining '$ChannelB'" }
      @{ Id = 'join-a-ack-after-phase2';         Token = "$JoinAckToken $ChannelA"; What = "bob acknowledged re-joining '$ChannelA' for phase 3" }
      @{ Id = 'join-a-ack-from-alice';           Token = "$JoinAckToken $ChannelA"; What = "alice acknowledged joining '$ChannelA'" }
    )
    foreach ($row in $rows) {
      $out += [PSCustomObject]@{
        id = "$prefix/$($row.Id)"
        token = $row.Token
        pass = $false
        evidence = "$whyNot -- would assert: $($row.What)"
        kind = 'diagnostic'
      }
    }
    return $out
  }

  # ---- phase 1: alice speaks in A, bob (in B) must not hear ----------------
  # The positive half is alice's own echo. In lite that echo is produced by the
  # same loop that selects recipients, so it is evidence the fan-out ran.
  $aliceEcho = & $hitsFor $AliceRes $tControl
  $aliceAccepted = (@($aliceEcho).Count -gt 0)
  $out += [PSCustomObject]@{
    id = "$prefix/phase1-alice-line-accepted"
    token = $tControl
    pass = $aliceAccepted
    evidence = if ($aliceAccepted) {
      (& $firstLine $aliceEcho 'alice (sender)')
    } else {
      "alice never recorded her own line in '$ChannelA'; either she never spoke or the channel " +
      'refused it, and the isolation negative below is BLOCKED'
    }
    kind = 'isolation-control'
  }

  $bobLeak = & $hitsFor $BobRes $tControl
  $out += [PSCustomObject]@{
    id = "$prefix/phase1-isolated-from-bob"
    token = $tControl
    pass = if ($aliceAccepted) { (@($bobLeak).Count -eq 0) } else { $false }
    evidence = if (-not $aliceAccepted) {
      "BLOCKED: the '$ChannelA' line was not established on the sender either, so its absence on bob proves nothing"
    } elseif (@($bobLeak).Count -gt 0) {
      "LEAK: bob (active channel '$ChannelB') recorded $($bobLeak[0].kind): $($bobLeak[0].text)"
    } else {
      "bob's recording ($(@($BobRes.received).Count) entries) carries no line with the '$ChannelA' token " +
      "while his active channel was '$ChannelB'"
    }
    kind = 'isolation-absence'
  }

  # ---- phase 2: bob speaks in B, alice (in A) must not hear ----------------
  $bobEcho = & $hitsFor $BobRes $tBobLine
  $bobAccepted = (@($bobEcho).Count -gt 0)
  $out += [PSCustomObject]@{
    id = "$prefix/phase2-bob-line-accepted"
    token = $tBobLine
    pass = $bobAccepted
    evidence = if ($bobAccepted) {
      (& $firstLine $bobEcho 'bob (sender)')
    } else {
      "bob never recorded his own line in '$ChannelB'; either he never spoke or the channel " +
      'refused it, and the isolation negative below is BLOCKED'
    }
    kind = 'isolation-control'
  }

  $aliceLeak = & $hitsFor $AliceRes $tBobLine
  $out += [PSCustomObject]@{
    id = "$prefix/phase2-isolated-from-alice"
    token = $tBobLine
    pass = if ($bobAccepted) { (@($aliceLeak).Count -eq 0) } else { $false }
    evidence = if (-not $bobAccepted) {
      "BLOCKED: bob's '$ChannelB' line was not established on the sender either, so its absence on alice proves nothing"
    } elseif (@($aliceLeak).Count -gt 0) {
      "LEAK: alice (active channel '$ChannelA') recorded $($aliceLeak[0].kind): $($aliceLeak[0].text)"
    } else {
      "alice's recording ($(@($AliceRes.received).Count) entries) carries no line with the '$ChannelB' token " +
      "while her active channel was '$ChannelA'"
    }
    kind = 'isolation-absence'
  }

  # ---- phase 3: the same channel DOES deliver -------------------------------
  # Second positive control: same sender, same receiver, a channel they now
  # share. Without it, phase 1/2 silence is also consistent with "chat broke".
  $sharedHits = & $hitsFor $BobRes $tShared
  $sharedDelivered = (@($sharedHits).Count -gt 0)
  $out += [PSCustomObject]@{
    id = "$prefix/phase3-shared-channel-delivered"
    token = $tShared
    pass = $sharedDelivered
    evidence = if ($sharedDelivered) {
      (& $firstLine $sharedHits "bob (now in '$ChannelA')")
    } else {
      "bob did not record alice's line after both joined '$ChannelA'; the phase-1/2 silence is then " +
      'not attributable to isolation'
    }
    kind = 'isolation-delivery'
  }

  # ---- join acknowledgements ------------------------------------------------
  # The channel id is part of the match, so a second join cannot be satisfied by
  # the first one's ack. Bob's joins gate: they are what establishes his ACTIVE
  # channel in each phase. Alice's is diagnostic (she joins only once, and it is
  # read off her own recording).
  $bobJoinB = & $hitsFor $BobRes "$JoinAckToken $ChannelB"
  $out += [PSCustomObject]@{
    id = "$prefix/join-b-ack-observed"
    token = "$JoinAckToken $ChannelB"
    pass = (@($bobJoinB).Count -gt 0)
    evidence = if (@($bobJoinB).Count -gt 0) {
      (& $firstLine $bobJoinB 'bob')
    } else {
      "bob recorded no '$JoinAckToken $ChannelB' line, so his active channel was never established " +
      "as '$ChannelB' and the phase-1 negative is judged against an unproven state"
    }
    kind = 'isolation-setup'
  }

  $bobJoinA = & $hitsFor $BobRes "$JoinAckToken $ChannelA"
  $out += [PSCustomObject]@{
    id = "$prefix/join-a-ack-after-phase2"
    token = "$JoinAckToken $ChannelA"
    pass = (@($bobJoinA).Count -gt 0)
    evidence = if (@($bobJoinA).Count -gt 0) {
      (& $firstLine $bobJoinA 'bob')
    } else {
      "bob recorded no '$JoinAckToken $ChannelA' line: the phase-3 join never succeeded, so the " +
      'shared-channel delivery below was attempted from the wrong channel'
    }
    kind = 'isolation-setup'
  }

  $aliceJoinA = & $hitsFor $AliceRes "$JoinAckToken $ChannelA"
  $out += [PSCustomObject]@{
    id = "$prefix/join-a-ack-from-alice"
    token = "$JoinAckToken $ChannelA"
    pass = (@($aliceJoinA).Count -gt 0)
    kind = 'diagnostic'
    evidence = if (@($aliceJoinA).Count -gt 0) {
      (& $firstLine $aliceJoinA 'alice')
    } else {
      "alice recorded no '$JoinAckToken $ChannelA' line, so her active channel is read from config " +
      '(default_channel = global) rather than from an acknowledgement'
    }
  }

  # ---- in-bot verdicts (diagnostic only) ------------------------------------
  # These bots' plans live in run-joint.ps1. If they armed the expects the
  # verdicts corroborate; if not, the verdict is absent. Neither may gate here.
  $botVerdict = {
    param($res, [string]$role, [string]$botId)
    if (-not $res) { return "$role produced no results.json" }
    foreach ($bucket in @($res.assertions, $res.absentChecks)) {
      if (-not $bucket) { continue }
      foreach ($a in $bucket) {
        if ([string]$a.id -eq $botId) { return "[$role] id=$botId pass=$($a.pass) $($a.evidence)" }
      }
    }
    return "$role never recorded a '$botId' verdict (its plan did not arm it)"
  }
  $out += [PSCustomObject]@{
    id = "$prefix/in-bot-arming"
    token = 'iso-absent-control / iso-expect-shared'
    pass = $false
    kind = 'diagnostic'
    evidence = ((& $botVerdict $BobRes 'bob' 'iso-absent-control') + ' | ' +
                (& $botVerdict $BobRes 'bob' 'iso-expect-shared'))
  }

  # ---- optional relay-side corroboration (diagnostic) ----------------------
  if ($RelayJwt) {
    $match = @(Get-BackendMessagesForToken $tControl $RelayJwt)
    $channelIds = @($match | ForEach-Object { $_.channelId } | Sort-Object -Unique)
    $out += [PSCustomObject]@{
      id = "$prefix/relay-record-channel"
      token = "$tControl@$ChannelA"
      pass = ($match.Count -gt 0)
      evidence = if ($match.Count -eq 0) {
        'no persisted record carries the phase-1 token (message log disabled, or the line never reached the backend)'
      } else {
        "backend persisted $($match.Count) record(s); channelId(s)='$($channelIds -join ', ')'"
      }
      kind = 'diagnostic'
    }
  }

  return $out
}

# --- plan fragments ----------------------------------------------------------
#
# Step arrays for the two existing proxy bots. Returned as plain hashtables so
# run-joint.ps1 can splice them with `+=` into its existing $aliceSteps /
# $bobSteps -- no new bot, and New-CrossServerPlan stays the only place a plan is
# serialized.
#
# ORDERING IS LOAD-BEARING (joint-bot.js noteInbound): a bot records a token
# only once `expectedTokens` contains it. `arm` precedes the producer;
# `expect` waits afterwards. Handoffs release the other actor.
#
# ACKS. A handoffSet whose pattern never matched THROWS and aborts the rest of
# that bot's plan (joint-bot.js runPlan), so every ack below is keyed on a line
# the publishing role definitely has. No ack is keyed on the line the other role
# is supposed to miss: that line's absence is correct behaviour, and keying an
# ack on it would abort the plan exactly when the product works.
#
# HANDOFF PROTOCOL (harness side: Set-Handoff / Wait-Handoff):
#   bob   publishes 'iso-bob-ready'  after joining B and arming the absence
#   alice publishes 'iso-alice-ack1' after her own phase-1 line is recorded
#   bob   publishes 'iso-bob-ack2'   after his own phase-2 line is recorded
#   bob   publishes 'iso-bob-ready3' after joining A and arming the delivery
#   alice publishes 'iso-alice-ack3' after her own phase-3 line is recorded
#   bob   publishes 'iso-bob-ack3'   when the shared line arrives
#   harness sets      'iso-phase1' -> 'iso-phase2' -> 'iso-phase3'
#
# Sequence the harness must follow (each step bounded, as Invoke-ModerationDrive
# does, so a stalled bot cannot hang the round):
#   wait iso-bob-ready -> set iso-phase1 -> wait iso-alice-ack1
#   -> set iso-phase2 -> wait iso-bob-ack2
#   -> set iso-phase3 -> wait iso-bob-ready3 -> wait iso-bob-ack3
function Get-IsolationPlanSteps {
  <#
  .SYNOPSIS
    Step fragments that drive the isolation scenario for the existing alice/bob
    plans.

  .DESCRIPTION
    Kept separate from Invoke-IsolationScenarios so a wiring mistake in either
    half is visible on its own: this function can be inspected for shape without
    running a round, and the assertion function can be exercised with synthetic
    results.

    Returns: @{ Alice = @(...); Bob = @(...); Tokens = @{...} }

  .PARAMETER RunId
    The round token prefix; must match Invoke-IsolationScenarios -RunId.

  .PARAMETER ChannelA / ChannelB
    Channel ids. Defaults 'global' / 'local'.

  .PARAMETER JoinAckToken
    Prefix of the success acknowledgement. The channel id is appended for each
    join. Must match the value passed to Invoke-IsolationScenarios.
  #>
  param(
    [string]$RunId,
    [string]$ChannelA = 'global',
    [string]$ChannelB = 'local',
    [string]$JoinAckToken = 'Joined channel'
  )

  $tControl = "$RunId-iso-control"
  $tBobLine = "$RunId-iso-fromb"
  $tShared  = "$RunId-iso-shared"
  $ackA     = [regex]::Escape("$JoinAckToken $ChannelA")
  $ackB     = [regex]::Escape("$JoinAckToken $ChannelB")

  $alice = @(
    @{ id = 'iso-arm-a'; action = 'arm'; token = "$JoinAckToken $ChannelA" },
    @{ id = 'iso-join-a'; action = 'cmd'; text = "/nc join $ChannelA" },
    @{ id = 'iso-join-a-settle'; action = 'expect'; token = "$JoinAckToken $ChannelA"; timeoutMs = 20000 },
    @{ id = 'iso-wait-ready'; action = 'handoffGet'; key = 'iso-bob-ready'; timeoutMs = 60000 },
    @{ id = 'iso-wait-phase1'; action = 'handoffGet'; key = 'iso-phase1'; timeoutMs = 60000 },
    @{ id = 'iso-arm-own-echo'; action = 'arm'; token = $tControl },
    @{ id = 'iso-send-phase1'; action = 'chat'; text = "iso control $tControl" },
    @{ id = 'iso-expect-own-echo'; action = 'expect'; token = $tControl; timeoutMs = 20000 },
    @{ id = 'iso-absent-control-marker'; action = 'expectAbsent'; token = $tBobLine },
    @{ id = 'iso-ready2'; action = 'handoffSet'; key = 'iso-alice-ready2'; pattern = [regex]::Escape($tControl) },
    @{ id = 'iso-ack1'; action = 'handoffSet'; key = 'iso-alice-ack1'; pattern = [regex]::Escape($tControl) },
    @{ id = 'iso-wait-phase3'; action = 'handoffGet'; key = 'iso-phase3'; timeoutMs = 60000 },
    @{ id = 'iso-wait-ready3'; action = 'handoffGet'; key = 'iso-bob-ready3'; timeoutMs = 60000 },
    @{ id = 'iso-arm-own-shared'; action = 'arm'; token = $tShared },
    @{ id = 'iso-send-phase3'; action = 'chat'; text = "iso shared $tShared" },
    @{ id = 'iso-expect-own-shared'; action = 'expect'; token = $tShared; timeoutMs = 20000 },
    @{ id = 'iso-ack3'; action = 'handoffSet'; key = 'iso-alice-ack3'; pattern = [regex]::Escape($tShared) }
  )

  $bob = @(
    @{ id = 'iso-arm-b'; action = 'arm'; token = "$JoinAckToken $ChannelB" },
    @{ id = 'iso-join-b'; action = 'cmd'; text = "/nc join $ChannelB" },
    @{ id = 'iso-join-b-settle'; action = 'expect'; token = "$JoinAckToken $ChannelB"; timeoutMs = 20000 },
    @{ id = 'iso-absent-control'; action = 'expectAbsent'; token = $tControl },
    @{ id = 'iso-ready'; action = 'handoffSet'; key = 'iso-bob-ready'; pattern = $ackB },
    @{ id = 'iso-wait-phase2'; action = 'handoffGet'; key = 'iso-phase2'; timeoutMs = 60000 },
    @{ id = 'iso-arm-own-echo'; action = 'arm'; token = $tBobLine },
    @{ id = 'iso-send-phase2'; action = 'chat'; text = "iso fromb $tBobLine" },
    @{ id = 'iso-expect-own-echo'; action = 'expect'; token = $tBobLine; timeoutMs = 20000 },
    @{ id = 'iso-ack2'; action = 'handoffSet'; key = 'iso-bob-ack2'; pattern = [regex]::Escape($tBobLine) },
    @{ id = 'iso-wait-phase3'; action = 'handoffGet'; key = 'iso-phase3'; timeoutMs = 60000 },
    @{ id = 'iso-arm-a'; action = 'arm'; token = "$JoinAckToken $ChannelA" },
    @{ id = 'iso-join-a'; action = 'cmd'; text = "/nc join $ChannelA" },
    @{ id = 'iso-join-a-settle'; action = 'expect'; token = "$JoinAckToken $ChannelA"; timeoutMs = 20000 },
    @{ id = 'iso-arm-shared'; action = 'arm'; token = $tShared },
    @{ id = 'iso-ready3'; action = 'handoffSet'; key = 'iso-bob-ready3'; pattern = $ackA },
    @{ id = 'iso-expect-shared'; action = 'expect'; token = $tShared; timeoutMs = 30000 },
    @{ id = 'iso-ack3'; action = 'handoffSet'; key = 'iso-bob-ack3'; pattern = [regex]::Escape($tShared) }
  )

  return @{
    Alice = $alice
    Bob = $bob
    Tokens = @{ Control = $tControl; BobLine = $tBobLine; Shared = $tShared }
  }
}
