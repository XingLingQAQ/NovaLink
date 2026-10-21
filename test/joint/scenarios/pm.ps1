# --- Area 1: private messaging ------------------------------------------------
#
# Dot-sourced by run-joint.ps1. Defines two functions and no global state; the
# file parses and loads on its own under PS 5.1.
#
# WHAT IT PROVES, AND WHY IT CAN FAIL
# -----------------------------------
# A DM must reach the target and ONLY the target. The lite round already covers
# "the target receives it" (round1/lite-direct-message); nothing covers
# exclusion, so a recipient-widening defect is invisible today:
#   * relay PrivateMessageHandler.sendPacket fan-out is per-connection
#     (target's connection + the sender's connection when they differ). Any
#     change that routed DMs through MessagePipeline's channel fan-out, or
#     dropped the isLocalOnline / targetConnection predicate, would render the
#     DM to every player whose state matched. The third-party absence is the only
#     assertion that catches it.
#   * lite PrivateMessageService.handleIncoming returns at most two Deliveries
#     (sender echo + target). ProxyChatCore.handlePrivateMessage then renders
#     them by id. A widened delivery list is a privacy defect.
#   * /nc r retargeting: recordConversation is keyed per player and written on
#     BOTH roles (PrivateMessageService.handleIncoming). A bug that records the
#     partner on the echo side only -- or on the wrong side -- makes /nc r reply
#     to the sender themselves. "the original sender receives it, the observer
#     does not" catches that.
#   * Offline-target refusal regressing to silence: renderError returns
#     Optional.empty() when senderId is missing or the sender is not local
#     (PrivateMessageService:193-196). Both topologies attach senderId today
#     (LocalAuthority...privateMessageError, PrivateMessageHandler.sendError);
#     dropping it turns "target offline" into a silent no-op, which is the same
#     class of bug the moderation-refusal finding already documented.
#
# NON-VACUITY
# -----------
# The offline half is NOT proven by "the token does not appear": a DM to a
# nonexistent player could also vanish because the command never ran. It is
# proven by the SENDER's own recording carrying the refusal line
# ('Player <name> is not online', chat.msg.offline, messages_en_US.properties
# :195 in the current tree). That line is a sender-side observable, so it is
# real evidence rather than an absence.
#
# Who the third-party observer is matters:
#   * relay: the bedrock bot is a RECEIVER on the same transport and has been
#     measured to receive DMs (chat.msg.received exists for Bedrock endstone,
#     levilamina and pmmp). Use it as the observer; an absence there is
#     meaningful.
#   * lite: the only third process is the direct-connect downstream bot, and it
#     cannot receive a proxy DM at all (LocalAuthority.findOnlineByName resolves
#     against the proxy roster only), so a leak to it is impossible by
#     construction. The lite third-party row is therefore reported as
#     DIAGNOSTIC, and the coverage plan's own rationale -- "the interesting case
#     is a leak through plugin messaging" -- is recorded in its evidence instead
#     of being turned into a green that proves nothing.
#
# EVIDENCE RULE
# -------------
# Every verdict is computed from the named RECEIVER's recording, never from the
# sender's claim and never from a handoff. Only `text` is searched, never `raw`
# (for effect rows `raw` is the JSON payload).
#
# WHAT IT CANNOT SEE (stated, not asserted away)
# ----------------------------------------------
#   * Whether the TARGET of a DM was told it was ignored: the DM-toward-ignorer
#     path deliberately answers NC-404 not_online (privacy split,
#     PrivateMessageHandler), indistinguishable from a genuinely offline target.
#   * Whether a POST-fix client renders the create/invite result keys.
#   * In lite, a DM to a name that lives only on the downstream leg is refused
#     as offline by design; that is a topology statement, not a defect.

function Invoke-PrivateMessageScenarios {
  <#
  .SYNOPSIS
    Private-messaging assertions for the joint E2E harness (coverage plan area 1).

  .DESCRIPTION
    Returns an array of assertion objects in the harness's existing shape:
        [PSCustomObject]@{ id; token; pass; evidence; kind }
    `kind = 'diagnostic'` reports but never gates. Every other kind gates.

    Never runs anything and never claims a pass it did not derive from a
    recording. Two call modes:

    1. STAGED / NOT WIRED (safe default). Omit the -SenderRes / -TargetRes /
       -ObserverRes parameters. With no results at all every assertion is
       returned as `diagnostic` with evidence starting
       'NOT EXECUTED: not wired'. Wiring the call cannot turn a green round red.

    2. WIRED. Pass the parsed results.json objects; each assertion is computed
       from the named receiver's recording and is gating (except the rows the
       file header documents as topology-shaped).

  .PARAMETER RoundIndex
    Round number; builds assertion ids ('round<N>/pm/...').

  .PARAMETER RunId
    The round's token prefix, i.e. "$($script:RunId)-r$($round.Index)" in
    run-joint.ps1. Tokens are "<RunId>-pm-<name>". Must match
    Get-PrivateMessagePlanSteps -RunId.

  .PARAMETER TargetName / OfflineName
    The DM partner (must be the target bot's leading name so the product's
    whisper line carries it) and the name that must be refused. OfflineName
    defaults to NoSuchPlayer, which no bot in any round uses.

  .PARAMETER SenderRes / TargetRes / ObserverRes
    Parsed result objects (Read-BotResult). Sender is the DM author; Target
    receives and replies; Observer is a third player on the SAME transport as
    the target.

  .PARAMETER Topology
    'lite' or 'relay'. It selects two row behaviours that are topology facts
    rather than product claims: the lite observer cannot see a proxy DM at all,
    and only relay can corroborate the refusal against the backend log.

  .PARAMETER BackendLog
    Optional path to the relay backend's stdout.log. When supplied, one
    diagnostic row reports whether the backend logged the target as not found.
  #>
  param(
    [int]$RoundIndex,
    [string]$RunId,
    [string]$TargetName = 'E2EJointBedrock',
    [string]$OfflineName = 'NoSuchPlayer',
    $SenderRes = $null,
    $TargetRes = $null,
    $ObserverRes = $null,
    [ValidateSet('lite', 'relay', '')]
    [string]$Topology = '',
    [string]$BackendLog = ''
  )

  $prefix = "round$RoundIndex/pm"
  $out = @()

  # -Topology has no default on purpose. It decides whether the two privacy rows
  # gate or are diagnostic, and guessing it would silently apply one topology's
  # semantics to the other -- the exact class of mistake this file exists to
  # avoid. Report it instead of throwing: a scenario must never abort a round.
  if (-not $Topology) {
    return @([PSCustomObject]@{
      id = "$prefix/topology-required"
      token = '-Topology'
      pass = $false
      evidence = "NOT EXECUTED: -Topology was not supplied. It selects which privacy rows gate: in lite the " +
        "only third player is direct-connect and cannot receive a proxy DM (LocalAuthority.findOnlineByName " +
        "resolves against the proxy roster), so that absence is reported as diagnostic; in relay the third " +
        "player shares the transport and the absence gates."
      kind = 'diagnostic'
    })
  }

  # One token per line, never reused (see isolation.ps1 for why).
  $tDm       = "$RunId-pm-dm"        # sender -> target
  $tReply    = "$RunId-pm-reply"     # target -> sender via /nc r
  $tOffline  = "$RunId-pm-offline"   # sender -> a name that must be refused

  # ---- evidence helpers -----------------------------------------------------
  # A recording exists only if the bot produced inbound lines beyond its own
  # spawn marker. This is the guard that stops an absence row from passing
  # against a null/crashed recording: with nothing recorded there is nothing to
  # search, and "no leak" would be the same vacuous pass as asserting on a token
  # that was never sent.
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
  # "Not wired" is decided from the evidence, not from a flag. All THREE results
  # must carry a recording: the observer's is what the privacy rows search, and
  # a missing one would make both of them pass by default. -Topology is required
  # because it selects which of those rows gate; a default would silently pick
  # one topology's semantics for the other.
  $senderOk = & $hasRecording $SenderRes
  $targetOk = & $hasRecording $TargetRes
  $observerOk = & $hasRecording $ObserverRes

  if (-not ($senderOk -and $targetOk -and $observerOk)) {
    $missing = @()
    if (-not $senderOk) { $missing += 'sender' }
    if (-not $targetOk) { $missing += 'target' }
    if (-not $observerOk) { $missing += 'observer' }
    $whyNot = if (-not ($SenderRes -or $TargetRes -or $ObserverRes)) {
      'NOT EXECUTED: not wired (no bot results passed; call with -SenderRes/-TargetRes/-ObserverRes after Wait-Bots)'
    } else {
      "NOT EXECUTED: not wired (no usable recording from: $($missing -join ', '); an absence cannot be judged against a missing recording)"
    }
    $rows = @(
      @{ Id = 'dm-echo-support';            Token = $tDm;      What = 'the sender records its own /nc msg line OR the target records the DM (support for the privacy half)' }
      @{ Id = 'dm-delivered-to-target';     Token = $tDm;      What = "the target records '$TargetName whispers to you: <token>'" }
      @{ Id = 'dm-not-leaked-to-observer';  Token = $tDm;      What = 'the third observer records no line carrying the DM token' }
      @{ Id = 'reply-delivered-to-sender';  Token = $tReply;   What = 'the original sender records the /nc r reply' }
      @{ Id = 'reply-not-leaked-to-observer'; Token = $tReply; What = 'the third observer records no line carrying the reply token' }
      @{ Id = 'offline-refused-to-sender';  Token = "'$OfflineName' + 'is not online'"; What = "the sender records the refusal 'Player $OfflineName is not online'" }
      @{ Id = 'offline-not-delivered-anywhere'; Token = $tOffline; What = 'no recording carries the offline DM token' }
      @{ Id = 'sender-delivery-confirmation'; Token = 'chat.msg.sent'; What = "the sender sees its own delivery acknowledgement ('You whisper to ...') rather than silence" }
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

  # The receiver-side DM line, in the shape the product actually renders:
  # chat.msg.received = '&e{0} &7whispers to you: &f{1}'. Matching the bare token
  # would also accept the sender's own echo, so the privacy assertion requires
  # the receiver-only wording plus the token. The sender name is included when
  # the product derived it from the target's leading name; a caller that renamed
  # the bot can still match on the wording alone.
  $receivedLineHits = {
    param($res, [string]$token)
    $hits = @()
    if (-not $res -or -not $res.received) { return $hits }
    foreach ($e in $res.received) {
      if ($e.kind -eq 'spawn' -or $e.kind -eq 'end' -or $e.kind -eq 'close') { continue }
      $t = if ($e.text) { [string]$e.text } else { '' }
      if ($t -and $t.Contains('whispers to you') -and $t.Contains($token)) { $hits += $e }
    }
    return $hits
  }

  $senderName = if ($TargetName) { ($TargetName -split ' ')[0] } else { '' }

  # ---- 1+2. the DM reaches the target and only the target ------------------
  # Support for the privacy half: the DM command demonstrably produced output
  # before the absence is judged. If neither the sender's echo nor the target's
  # DM exists, the observer's silence is also true for "the DM was never sent".
  $senderEchoHits = & $hitsFor $SenderRes $tDm
  $targetDmHits = & $receivedLineHits $TargetRes $tDm
  $targetAnyHits = & $hitsFor $TargetRes $tDm
  $dmSupported = ((@($senderEchoHits).Count -gt 0) -or (@($targetDmHits).Count -gt 0))
  $out += [PSCustomObject]@{
    id = "$prefix/dm-echo-support"
    token = $tDm
    pass = $dmSupported
    evidence = if (@($senderEchoHits).Count -gt 0) {
      "sender side: " + (& $firstLine $senderEchoHits 'the sender')
    } elseif (@($targetDmHits).Count -gt 0) {
      "receiver side: " + (& $firstLine $targetDmHits 'the target')
    } elseif (@($targetAnyHits).Count -gt 0) {
      # Token present but not in the receiver-only wording: the DM reached the
      # target through some other render path. Worth reporting verbatim.
      "the target recorded the token, but not as a whisper line: " + (& $firstLine $targetAnyHits 'the target')
    } else {
      "neither the sender's echo nor the target's whisper line carries the DM token; either the DM " +
      'was never sent or it was dropped, and the leak check below is BLOCKED'
    }
    kind = 'pm-control'
  }

  $out += [PSCustomObject]@{
    id = "$prefix/dm-delivered-to-target"
    token = $tDm
    pass = (@($targetDmHits).Count -gt 0)
    evidence = if (@($targetDmHits).Count -gt 0) {
      (& $firstLine $targetDmHits 'the target')
    } else {
      "the target recorded no line matching 'whispers to you' for the DM token" +
      $(if (@($targetAnyHits).Count -gt 0) { " (it recorded the token as: $($targetAnyHits[0].text))" } else { '' })
    }
    kind = 'pm-delivery'
  }

  $observerLeak = & $hitsFor $ObserverRes $tDm
  # Topology: in lite the only third player is direct-connect and cannot receive
  # a proxy DM (LocalAuthority resolves names against the proxy roster), so the
  # assertion could never fail for a product reason. Report it, never gate it.
  $observerKind = if ($Topology -eq 'lite') { 'diagnostic' } else { 'pm-privacy' }
  # Guarded on the TARGET's delivery, not on the sender's echo. A sender echo
  # proves the command produced output, but "the observer did not receive it" is
  # vacuous when the DM never reached its intended recipient -- the very defect
  # this row is supposed to be independent of.
  $dmDeliveredToTarget = (@($targetDmHits).Count -gt 0)
  $out += [PSCustomObject]@{
    id = "$prefix/dm-not-leaked-to-observer"
    token = $tDm
    pass = if ($dmDeliveredToTarget) { (@($observerLeak).Count -eq 0) } else { $false }
    evidence = if (-not $dmDeliveredToTarget) {
      'BLOCKED: the DM never reached its intended target, so the observer''s silence proves nothing about privacy'
    } elseif (@($observerLeak).Count -gt 0) {
      "LEAK: the observer recorded $($observerLeak[0].kind): $($observerLeak[0].text)"
    } elseif ($Topology -eq 'lite') {
      'the direct-connect observer recorded no line carrying the DM token. DIAGNOSTIC by topology: ' +
      'a proxy-side DM is resolved against the proxy roster only (LocalAuthority.findOnlineByName), so ' +
      'this observer could not receive it even if the delivery list widened; the relay round is where ' +
      'this row gates.'
    } else {
      'the third observer (same transport as the target) recorded no line carrying the DM token'
    }
    kind = $observerKind
  }

  # ---- 3. /nc r replies to the last partner -------------------------------
  # The reply line is chat.msg.received again, this time on the ORIGINAL sender.
  # /nc r resolves the stored partner: LocalAuthority/PMS recordConversation on
  # the receive path (PrivateMessageService.handleIncoming), so the target's
  # reply must land on the sender and nowhere else.
  $replyHits = & $receivedLineHits $SenderRes $tReply
  $replyAnyHits = & $hitsFor $SenderRes $tReply
  $out += [PSCustomObject]@{
    id = "$prefix/reply-delivered-to-sender"
    token = $tReply
    pass = (@($replyHits).Count -gt 0)
    evidence = if (@($replyHits).Count -gt 0) {
      (& $firstLine $replyHits 'the original sender')
    } elseif (@($replyAnyHits).Count -gt 0) {
      "the sender recorded the reply token, but not as a whisper line: " + (& $firstLine $replyAnyHits 'the sender')
    } else {
      'the original sender recorded no whisper line for the reply token: /nc r did not reach the ' +
      'partner it resolved, or resolved somebody else'
    }
    kind = 'pm-delivery'
  }

  $replyLeak = & $hitsFor $ObserverRes $tReply
  $out += [PSCustomObject]@{
    id = "$prefix/reply-not-leaked-to-observer"
    token = $tReply
    pass = if (@($replyHits).Count -gt 0) { (@($replyLeak).Count -eq 0) } else { $false }
    evidence = if (@($replyHits).Count -eq 0) {
      'BLOCKED: the reply never reached the sender, so its absence on the observer proves nothing'
    } elseif (@($replyLeak).Count -gt 0) {
      "LEAK: the observer recorded $($replyLeak[0].kind): $($replyLeak[0].text)"
    } elseif ($Topology -eq 'lite') {
      'the direct-connect observer recorded no line carrying the reply token (DIAGNOSTIC by the same ' +
      'topology reason as dm-not-leaked-to-observer)'
    } else {
      'the third observer recorded no line carrying the reply token'
    }
    kind = $observerKind
  }

  # ---- 4. the offline target is REFUSED, visibly --------------------------
  # This is the assertion that makes the offline half non-vacuous: the refusal
  # is a line the SENDER receives. chat.msg.offline = 'Player <name> is not
  # online' (messages_en_US.properties:195). Matching name + 'is not online'
  # avoids a false pass from an unrelated error that merely names the player.
  $offlineNameHits = if ($OfflineName) { & $hitsFor $SenderRes $OfflineName } else { @() }
  $offlineRefusalHits = @($offlineNameHits | Where-Object { $_.text -and ([string]$_.text).Contains('is not online') })
  $out += [PSCustomObject]@{
    id = "$prefix/offline-refused-to-sender"
    token = "'$OfflineName' + 'is not online'"
    pass = (@($offlineRefusalHits).Count -gt 0)
    evidence = if (@($offlineRefusalHits).Count -gt 0) {
      (& $firstLine $offlineRefusalHits 'the sender')
    } elseif (@($offlineNameHits).Count -gt 0) {
      "the sender's recording names '$OfflineName' but not with the offline wording: " + (& $firstLine $offlineNameHits 'the sender')
    } else {
      "the sender recorded nothing naming '$OfflineName': the refusal is not rendered (renderError returns " +
      'empty when senderId is missing or the sender is not local), or the command never ran'
    }
    kind = 'pm-refusal'
  }

  $offlineAnywhere = @()
  foreach ($pair in @(
      @{ Role = 'sender';   Res = $SenderRes },
      @{ Role = 'target';   Res = $TargetRes },
      @{ Role = 'observer'; Res = $ObserverRes })) {
    $h = & $hitsFor $pair.Res $tOffline
    foreach ($e in $h) { $offlineAnywhere += "$($pair.Role): $($e.text)" }
  }
  # Guarded on the refusal half AND on a non-empty token. Without either, this
  # row would report a privacy pass for a command that produced no output at
  # all: "the refused DM appeared nowhere" is also true when the DM was never
  # attempted, and that is exactly the failure the row above exists to catch.
  $refusalObserved = (@($offlineRefusalHits).Count -gt 0)
  $out += [PSCustomObject]@{
    id = "$prefix/offline-not-delivered-anywhere"
    token = $tOffline
    pass = if ($refusalObserved -and $tOffline) { (@($offlineAnywhere).Count -eq 0) } else { $false }
    evidence = if (-not $tOffline) {
      'BLOCKED: no offline token was built (empty -RunId), so absence cannot be judged'
    } elseif (-not $refusalObserved) {
      'BLOCKED: no offline refusal was rendered to the sender, so there is no evidence the DM was attempted; ' +
      'its absence everywhere proves nothing'
    } elseif (@($offlineAnywhere).Count -gt 0) {
      "LEAK: the refused DM's token appears: $($offlineAnywhere -join ' | ')"
    } else {
      'no recording carries the offline DM token'
    }
    kind = 'pm-privacy'
  }

  # ---- 5. diagnostic: does the sender see its OWN delivery confirmation? --
  # Not a contract, a measurement. In lite the echo comes from
  # PrivateMessageService.handleIncoming (Role.ECHO, chat.msg.sent); in relay the
  # echo is rendered by each platform from the echoed packet. A sender that sees
  # nothing cannot tell a delivered DM from a dropped one.
  #
  # Matched on 'You whisper to' (chat.msg.sent), not on the bare word 'whisper':
  # the sender ALSO records the incoming reply, which contains 'whispers to you'
  # and would make this row pass without any confirmation of its own send ever
  # having been rendered.
  $echoHits = @()
  if ($SenderRes -and $SenderRes.received) {
    foreach ($e in $SenderRes.received) {
      $t = if ($e.text) { [string]$e.text } else { '' }
      if ($t -and $t.Contains('You whisper to')) { $echoHits += $e }
    }
  }
  $out += [PSCustomObject]@{
    id = "$prefix/sender-delivery-confirmation"
    token = 'chat.msg.sent (You whisper to ...)'
    pass = (@($echoHits).Count -gt 0)
    kind = 'diagnostic'
    evidence = if (@($echoHits).Count -gt 0) {
      "the sender saw $($echoHits.Count) own-send confirmation(s); first: $($echoHits[0].text)"
    } else {
      'the sender saw NO own-send confirmation (chat.msg.sent): a player cannot tell a delivered DM ' +
      'from a dropped one (recorded as a measurement, not as a contract)'
    }
  }

  # ---- 6. optional relay-side corroboration (diagnostic) ------------------
  # PrivateMessageHandler logs the not-found drop at DEBUG and (in relay debug is
  # off) sends NC-404 to the sender. The line below is only present when the
  # backend log level happens to include it; absence must not gate.
  if ($BackendLog) {
    $text = Read-LogText $BackendLog
    $pattern = "PrivateMessage drop: target '" + [regex]::Escape($OfflineName) + "'"
    $hit = [bool]($text -and ($text -match $pattern))
    $out += [PSCustomObject]@{
      id = "$prefix/offline-drop-backend-log"
      token = $OfflineName
      pass = $hit
      kind = 'diagnostic'
      evidence = if ($hit) {
        (($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1) -replace '\s+$', '')
      } else {
        "no backend log line matched $pattern (PrivateMessageHandler emits it at DEBUG; the relay round runs with debug lines disabled), so the authority-side half is unobservable"
      }
    }
  }

  return $out
}

# --- plan fragments ----------------------------------------------------------
#
# Step arrays for the three existing relay-round roles. Returned as plain
# hashtables so run-joint.ps1 can splice them into $javaSteps / $bedSteps /
# $modSteps with `+=`.
#
# ORDERING IS LOAD-BEARING (joint-bot.js noteInbound): a bot records a token
# only once `expectedTokens` contains it, and only `expect` / `expectAbsent`
# steps add to that list. In each plan every expect precedes the action that
# produces its line, and actors are released by handoffs, not fixed sleeps.
#
# HANDOFF PROTOCOL (the harness drives these):
#   observer publishes 'pm-observer-ready' after arming both absences
#   sender   publishes 'pm-sent'           after the DM has left its client
#   target   publishes 'pm-got'            after the DM is in its recording
#   target   publishes 'pm-replied'        after /nc r has left its client
#   sender   publishes 'pm-offline-sent'   after /nc msg OfflineName
#   harness sets      'pm-go-send' -> 'pm-go-reply' -> 'pm-go-offline'
#
# Every ack is published from a line the publishing role ITSELF produced or
# received. An ack keyed on a line a correct product suppresses would make the
# ack step throw and abort the rest of that bot's plan.
function Get-PrivateMessagePlanSteps {
  <#
  .SYNOPSIS
    Step fragments that drive the private-message scenario for the existing
    relay-round roles (java = sender, bedrock = target, mod = observer).

  .DESCRIPTION
    Kept separate from Invoke-PrivateMessageScenarios so a wiring mistake in
    either half is visible on its own.

    Returns: @{ Sender = @(...); Target = @(...); Observer = @(...); Tokens = @{...} }

  .PARAMETER RunId
    The round's token prefix; must match Invoke-PrivateMessageScenarios -RunId.

  .PARAMETER TargetDisplayName
    The target bot's username, as used in the /nc msg argument. Its LEADING name
    (text before the first space) is what the product's whisper line renders, so
    '-TargetName' on the assertion side must be given the same value.

  .PARAMETER OfflineName
    A name no bot uses; the DM to it must be refused.
  #>
  param(
    [string]$RunId,
    [string]$TargetDisplayName = 'E2EJointBedrock',
    [string]$OfflineName = 'NoSuchPlayer'
  )

  $tDm      = "$RunId-pm-dm"
  $tReply   = "$RunId-pm-reply"
  $tOffline = "$RunId-pm-offline"

  # SENDER (java). Arms the delivery-side expectation for the reply BEFORE the
  # reply can be sent, and arms nothing that a correct product suppresses.
  $sender = @(
    @{ id = 'pm-wait-go-send';   action = 'handoffGet'; key = 'pm-go-send'; timeoutMs = 200000 },
    @{ id = 'pm-expect-reply';   action = 'expect'; token = $tReply; timeoutMs = 200000 },
    # Own echo (chat.msg.sent: 'You whisper to <target>: <content>'). This is the
    # measured sender-side confirmation on every platform that renders it.
    # Armed before the command so the echo cannot arrive first.
    @{ id = 'pm-expect-echo';    action = 'expect'; token = $tDm; timeoutMs = 60000 },
    @{ id = 'pm-send-dm';        action = 'cmd';  text = "/nc msg $TargetDisplayName $tDm" },
    @{ id = 'pm-sent';           action = 'handoffSet'; key = 'pm-sent'; pattern = [regex]::Escape($tDm) },
    @{ id = 'pm-wait-go-offline'; action = 'handoffGet'; key = 'pm-go-offline'; timeoutMs = 200000 },
    # Arm the refusal BEFORE sending; it is a positive assertion on this bot's
    # own recording, so arming late would only cause a false failure.
    @{ id = 'pm-expect-offline'; action = 'expect'; token = 'is not online'; timeoutMs = 60000 },
    @{ id = 'pm-send-offline';   action = 'cmd';  text = "/nc msg $OfflineName $tOffline" },
    @{ id = 'pm-offline-sent';   action = 'handoffSet'; key = 'pm-offline-sent'; pattern = 'is not online' }
  )

  # TARGET (bedrock). Arms the DM before the sender can speak, then replies.
  $target = @(
    @{ id = 'pm-expect-dm';      action = 'expect'; token = $tDm; timeoutMs = 200000 },
    @{ id = 'pm-got';            action = 'handoffSet'; key = 'pm-got'; pattern = [regex]::Escape($tDm) },
    @{ id = 'pm-wait-go-reply';  action = 'handoffGet'; key = 'pm-go-reply'; timeoutMs = 200000 },
    @{ id = 'pm-expect-own-reply'; action = 'expect'; token = $tReply; timeoutMs = 60000 },
    @{ id = 'pm-send-reply';     action = 'cmd';  text = "/nc r $tReply" },
    @{ id = 'pm-replied';        action = 'handoffSet'; key = 'pm-replied'; pattern = [regex]::Escape($tReply) }
  )

  # OBSERVER (mod). Arms all three absences, then publishes readiness off a line
  # it definitely has.
  #
  # Readiness MUST NOT be keyed on the DM token. handoffSet only publishes when
  # its pattern matched inbound traffic, and on a correct product the observer
  # never receives the DM -- so a DM-keyed readiness step would throw exactly
  # when the product works, aborting this bot's plan (joint-bot.js runPlan).
  # The spawn marker is what Invoke-Round's own 'peerup' publish already uses
  # (run-joint.ps1: '/nc join global' then handoffSet pattern 'spawned'), so it
  # is proven to be present in this bot's recording.
  $observer = @(
    @{ id = 'pm-absent-dm';       action = 'expectAbsent'; token = $tDm },
    @{ id = 'pm-absent-reply';    action = 'expectAbsent'; token = $tReply },
    @{ id = 'pm-absent-offline';  action = 'expectAbsent'; token = $tOffline },
    @{ id = 'pm-observer-ready';  action = 'handoffSet'; key = 'pm-observer-ready'; pattern = '^spawned$' }
  )

  return @{
    Sender = $sender
    Target = $target
    Observer = $observer
    Tokens = @{ Dm = $tDm; Reply = $tReply; Offline = $tOffline }
  }
}
