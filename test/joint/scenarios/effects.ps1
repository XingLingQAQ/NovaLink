# --- Areas 2/3/4: mention, title and action-bar EFFECTS ----------------------
#
# Dot-sourced by run-joint.ps1. Defines two functions and no global state; the
# file parses and loads on its own under PS 5.1. It dot-sources helpers.ps1
# (idempotent) so it also loads standalone.
#
# WHAT IS NEW HERE
# ----------------
# The joint bot can observe non-chat effects (expectEvent / expectEventAbsent /
# expectKicked / expectState over kinds title, actionBar, sound, soundId,
# playerJoined, playerLeft, kicked, respawn) since commit 46c7b8a, and the
# effect stream is written to results.effects[]. The plans have used EXACTLY ONE
# of those primitives: a join action bar (alice/bob `expect-join-actionbar`) and
# a mention title on Bob. Nothing has ever asserted:
#
#   * that a mention does NOT reach a third in-channel player,
#   * that a mention does NOT reach a player whose active channel differs,
#   * what an action bar SAYS about the player's own channel,
#   * any property of a `/nc title` packet at all.
#
# WHY EACH NEGATIVE IS NOT VACUOUS
# --------------------------------
# A missing effect is also consistent with "the feature is dead". Every negative
# row below therefore carries a POSITIVE control that must hold in the SAME
# recording(s):
#
#   mention-third-party-absent   controlled by mention-title-delivered-to-bob
#                                (the mention title DID arrive, on the mentioned
#                                player) plus the third player's own guard that
#                                they received the chat line carrying the
#                                mention (so they were in the fan-out and their
#                                effect stream was live).
#   mention-channel-scoped-absent controlled by the same delivered title AND by
#                                the off-channel player's positive control that
#                                their OWN channel still works (a line in their
#                                channel reaches them). Without that control,
#                                "no mention title" is also true when the player
#                                receives nothing at all.
#   title-absent-other-channel   controlled by title-delivered-in-channel.
#   actionbar-content            is itself the positive; the negative companion
#                                (actionbar-not-on-peer) is controlled by the
#                                bar appearing on the join's own player.
#
# THE FAIL-OPEN CLAUSE, AND HOW THE PLAN AVOIDS PROVING NOTHING
# -------------------------------------------------------------
# Both mention filters were just made channel-scoped and both FAIL OPEN:
#   StarLink MentionResolver.resolvePlayerUUID -> isInChannel =
#       active == null || active.isBlank() || active.equals(channelId)
#   NovaChat ProxyChatCore.handleMention     -> isInChannel has the same shape
# so a player whose active channel is UNKNOWN is still pinged. A channel-scoping
# assertion run against a player with no recorded active channel would pass for
# the wrong reason and prove nothing. Get-EffectsPlanSteps therefore never lets
# an off-channel observer's channel be a guess: the observer's plan performs an
# explicit `/nc join <other>` and the assertion side reads the join
# acknowledgement OUT OF THAT OBSERVER'S OWN RECORDING before the negative row
# is allowed to gate. If the ack is missing, the row is reported as not-executed
# with that reason instead of a green.
#
# SOUND: CONFIRMED UNOBSERVABLE, DIAGNOSTIC
# -----------------------------------------
# COVERAGE-PLAN area 2.5 says the mention sound is unobservable on the current
# topology. That is CONFIRMED, and the reason is in Velocity's bytecode, not in
# guesswork:
#
#   com.velocitypowered.proxy.connection.client.ConnectedPlayer#playSound
#   (velocity-4.1.0-SNAPSHOT-16, verified with javap on the pinned jar):
#     bytecode 17-76  -> getConnectedServer() must be non-null AND the protocol
#                        must be >= 1.19.3 AND the connection state must be PLAY,
#                        otherwise `return` -- no packet is written at all;
#     bytecode 77-131 -> the emitter must be `self` (or another ConnectedPlayer
#                        on the SAME registered server), otherwise `return`;
#     bytecode 132-157 -> the packet written is ClientboundSoundEntityPacket,
#                        i.e. the `entity_sound_effect` wire packet, never
#                        `sound_effect`.
#
#   A joint bot connects to the PROXY, so it has no backend server entity in the
#   proxy's model at the instant the mention is rendered by proxy code; and the
#   one leg where an entity exists is a sub-server plugin (Bukkit's
#   NetworkClient.handleMention -> player.playSound(...), which the server sends
#   itself) -- but that path only fires for a player whose mention packet
#   arrived on the SUB-SERVER, i.e. a direct-connect player, and in relay the
#   proxy already notified that player and passes them in `excluded`.
#
#   Measured, not just read (artifacts under .e2e-artifacts/joint). The one
#   clean experiment is the LITE run, which fired a real mention:
#     * lite 20261021-151247 (alice mentioned bob, token
#       ...-r1-lite-mention): bob's and alice's effects[] contain ZERO rows of
#       kind `sound`, `soundId` or `rawSoundPacket`, while the DIRECT-CONNECT
#       `downstream` bot on the same backend recorded 7 `sound` + 7
#       `rawSoundPacket` rows. So the proxy-connected client's sound path is
#       instrumented and working in general, and no sound was sent to it at the
#       mention. Bob DID receive the mention title, so the notification path
#       ran; only the sound half produced nothing.
#     * relay 20261021-145517 is NOT evidence here: that run never sent a
#       mention at all (java's `sent[]` has no '@' line), so its recordings say
#       nothing about mention sound. The relay-side reasoning above is
#       code-derived (the `excluded` set in NetworkClient.handleMention), not
#       measured.
#
#   Verdict: the coverage plan's claim is CONFIRMED for the lite/proxy-connected
#   topology, by one clean experiment plus the bytecode reading. It is NOT
#   measured for relay (that run never exercised it) and NOT measured for a
#   direct-connect Bedrock-Java bot receiving a mention. The assertion is
#   therefore `diagnostic`, and it is written so it CAN become gating the moment
#   a sound packet is observed: it reports what was seen (including the raw
#   packet names) rather than only "not found", and it flips to pass=True when
#   `experience_orb` appears.
#
# WHAT THIS FILE DOES NOT SEE (stated, not asserted away)
# -------------------------------------------------------
#   * Custom fadeIn/stay/fadeOut. No client sets the extras (TitleCommand sets
#     only title/subtitle; the backend console `title` accepts no timings), so
#     an assertion on a custom value would assert a number the harness itself
#     chose. titleTimings row is pinned to the packet DEFAULTS (10/70/20) and
#     says so; it proves pass-through, not configurability.
#   * Which renderer produced an action bar. Java records both `systemChat` and
#     the raw `action_bar` packet for the same bar (data.source), so a
#     count-based duplicate assertion would count one logical bar twice. The
#     content assertion asks for at least one matching row and does not claim a
#     count.
#   * A locale-switched client. The bar text is locale-dependent
#     (chat.status.current_bar: 'Current channel: {0} ({1})' in en_US,
#     '当前频道：{0}（{1}）' in zh_CN). Every content assertion below is anchored
#     on the CHANNEL ID, which is the same substring in both locales, and the
#     English label appears only as a stronger optional form selected by
#     -ActionBarLabel. A zh_CN client is not a false failure.

# The scenario file loads its own helpers so it can be exercised standalone.
if (-not (Get-Command New-JointAssertion -ErrorAction SilentlyContinue)) {
  . (Join-Path $PSScriptRoot 'helpers.ps1')
}

function Invoke-EffectScenarios {
  <#
  .SYNOPSIS
    Mention / title / action-bar effect assertions for the joint E2E harness
    (coverage plan areas 2, 3 and 4).

  .DESCRIPTION
    Returns an array of assertion objects in the harness's existing shape:
        [PSCustomObject]@{ id; token; pass; evidence; kind }
    `kind = 'diagnostic'` reports but never gates (run-joint.ps1:4176, :4570).
    Every other kind gates.

    NEVER runs anything and NEVER claims a pass it did not derive from a
    recording. Three call modes:

    1. STAGED / NOT WIRED (safe default). Omit every -XxxRes parameter. Every
       row comes back `diagnostic` with evidence starting 'NOT EXECUTED: not
       wired'. Wiring the call cannot turn a green round red.

    2. WIRED. Pass the parsed results.json objects
       (Read-BotResult). Rows are computed from the named receiver's
       recording and gate -- except the sound row, which is diagnostic by
       design (see the file header) and the rows this file documents as
       topology facts.

    3. PARTIAL. Some results passed, others missing. Rows that need a missing
       recording report 'NOT EXECUTED: <which recording is missing>' rather
       than a vacuous pass.

  .PARAMETER RoundIndex
    Round number; builds assertion ids ('round<N>/effect/...').

  .PARAMETER RunId
    The round's token prefix, i.e. "$($script:RunId)-r$($round.Index)" in
    run-joint.ps1. Tokens are "<RunId>-effect-<name>" and must match
    Get-EffectsPlanSteps -RunId.

  .PARAMETER MentionerName / MentionedName
    Bot usernames that the mention line names. The title carries the mentioner
    name (Velocity: Component.text(stripColorPrefix(highlight)), i.e. the bare
    name; Bukkit: formatter.translateColorCodes("&e" + name)). Matching is a
    substring on `text`, so a color-coded client still matches the bare name.

  .PARAMETER MentionerRes / MentionedRes / BystanderRes / OffChannelRes
    Parsed result objects.
      MentionerRes    the sender. Not used as mention evidence (a sender's own
                      notification is filtered by MentionNotifier); used only
                      for the self-notification negative.
      MentionedRes    the mentioned player -- the RECEIVER whose title is the
                      product's intended effect.
      BystanderRes    a THIRD player in the SAME channel. Must be a player who
                      provably has that channel active (see .PARAMETER
                      TwitterChannelAckToken-style parameters below) -- a player
                      whose active channel is unknown is pinged by the product
                      (fail-open) and would make the negative pass for the
                      wrong reason.
      OffChannelRes   a player whose active channel is a DIFFERENT channel, with
                      a positive control that their own channel still reaches
                      them.

  .PARAMETER MentionToken
    The correlation token embedded in the mention line. Used to locate the
    line that anchors every time window and to prove the bystander was in the
    fan-out (they must have received the same line: the line is channel-wide).

  .PARAMETER BystanderName / OffChannelName
    Usernames of the two observers, used as evidence labels so a reader can tell
    which recording a verdict came from. A mention title names the MENTIONER,
    not the mentioned player, so the negative rows match on MentionerName.

  .PARAMETER OffChannelOwnToken
    A token from a line the OFF-CHANNEL player spoke in their OWN channel, i.e.
    the positive control that their channel still delivers to them. Without it
    the only proof of life is the join acknowledgement, which proves membership
    but not that the recording's chat path works. Omit it and the control row
    falls back to the ack and says so.

  .PARAMETER BystanderChannelAck / OffChannelChannelAck
    The join acknowledgement substring each observer's OWN recording must carry
    before its negative row is allowed to gate, e.g. 'Joined channel local'.
    Defaults 'Joined channel' plus the respective channel id. Pass '' to
    disable the guard ONLY when you know the observer's channel from another
    recording -- the row then says so in its evidence.

  .PARAMETER ChannelA / ChannelB
    Channel ids. Defaults 'global' / 'local': lite declares both in
    [lite.channels] (Write-ProxyPluginConfig). Relay declares only 'global' in
    Write-BackendConfig, so ChannelB must be added there before relay can run
    the channel-scoped rows; without it those rows report the configuration gap.

  .PARAMETER ActionBarLabel
    The channel label the action bar uses in the client's locale, appended to
    the channel id for the STRONG form of the content assertion. Default
    'Current channel' (en_US chat.status.current_bar). Pass '' on a client whose
    locale is not English: the assertion then matches on the channel id alone,
    which is locale-independent, instead of reporting a false failure.

  .PARAMETER TitleText
    The text the `/nc title` scenario asked the authority to send. Required for
    the title-delivered row; empty makes it report the configuration gap.

  .PARAMETER TitleRes
    The in-channel receiver of the title. Separate from MentionedRes so a
    topology can use different players; defaults to MentionedRes when omitted.

  .EXAMPLE
    # staged
    $a = Invoke-EffectScenarios -RoundIndex 1 -RunId "$RunId-r1"

    # wired (relay: java mentions bedrock, mod is the off-channel observer)
    $a = Invoke-EffectScenarios -RoundIndex $round.Index -RunId $tokenPrefix `
           -MentionerRes $javaRes -MentionedRes $bedRes -BystanderRes $modRes `
           -MentionerName 'E2EJointJava' -MentionedName 'E2EJointBedrock' `
           -MentionToken "$tokenPrefix-effect-mention" -Topology 'relay'
  #>
  param(
    [int]$RoundIndex,
    [string]$RunId = '',
    [string]$MentionerName = '',
    [string]$MentionedName = '',
    [string]$BystanderName = '',
    [string]$OffChannelName = '',
    [string]$MentionToken = '',
    [string]$OwnChannelToken = '',
    [string]$OffChannelOwnToken = '',
    [string]$ChannelA = 'global',
    [string]$ChannelB = 'local',
    [string]$BystanderChannelAck = '',
    [string]$OffChannelChannelAck = '',
    [string]$ActionBarLabel = 'Current channel',
    [string]$TitleText = '',
    [string]$SubtitleText = '',
    [string]$Topology = 'lite',
    $MentionerRes = $null,
    $MentionedRes = $null,
    $BystanderRes = $null,
    $OffChannelRes = $null,
    $TitleRes = $null
  )

  $prefix = "round$RoundIndex/effect"
  $out = @()

  # Defaults that make the staged call self-consistent.
  if ([string]::IsNullOrEmpty($MentionToken) -and -not [string]::IsNullOrEmpty($RunId)) {
    $MentionToken = "$RunId-effect-mention"
  }
  if ([string]::IsNullOrEmpty($BystanderChannelAck) -and -not [string]::IsNullOrEmpty($ChannelA)) {
    $BystanderChannelAck = "Joined channel $ChannelA"
  }
  if ([string]::IsNullOrEmpty($OffChannelChannelAck) -and -not [string]::IsNullOrEmpty($ChannelB)) {
    $OffChannelChannelAck = "Joined channel $ChannelB"
  }
  if ($null -eq $TitleRes) { $TitleRes = $MentionedRes }

  $anyResult = ($null -ne $MentionerRes) -or ($null -ne $MentionedRes) -or
               ($null -ne $BystanderRes) -or ($null -ne $OffChannelRes)

  # ---------------------------------------------------------------------
  # 0. THE WIRING SENTINEL
  # ---------------------------------------------------------------------
  # Without bot results there is nothing to judge. Every row below still
  # appears, so the report shows what WILL be asserted, but each is
  # `diagnostic` with the same reason. A caller that forgot a result gets an
  # explicit NOT EXECUTED per row, never a green.
  # ---------------------------------------------------------------------
  if (-not $anyResult) {
    $rows = @(
      @{ Id = 'mention-title-delivered';   What = "the mentioned player receives a mention TITLE naming the mentioner ($MentionerName)" }
      @{ Id = 'mention-self-not-notified'; What = 'the mentioner is not notified of their own mention' }
      @{ Id = 'mention-line-reached-bystander'; What = 'CONTROL: a third in-channel player received the chat line carrying the mention (their effect stream was live)' }
      @{ Id = 'mention-third-party-absent'; What = 'the third in-channel player received NO mention title (the line reached them, the notification did not)' }
      @{ Id = 'mention-line-reached-offchannel'; What = 'CONTROL: a player in a DIFFERENT channel did not receive the line, and their own channel still reaches them' }
      @{ Id = 'mention-channel-scoped-absent'; What = 'a player whose active channel is different receives NO mention title' }
      @{ Id = 'mention-sound-observable'; What = 'DIAGNOSTIC: whether any sound packet (including the raw packet names) reached the mentioned player' }
      @{ Id = 'title-delivered-in-channel'; What = "the in-channel receiver gets the /nc title text '$TitleText' as a title effect" }
      @{ Id = 'title-subtitle-delivered';   What = 'the title subtitle arrives as its own title row' }
      @{ Id = 'title-timings-default'; What = 'the title carries the packet DEFAULT timings (10/70/20); custom timings are not expressible by any client' }
      @{ Id = 'title-absent-other-channel'; What = 'a player in another channel does not receive the title' }
      @{ Id = 'actionbar-content-matches-channel'; What = "the join action bar names the player's ACTUAL channel ($ChannelA)" }
      @{ Id = 'actionbar-not-on-peer'; What = 'the join action bar of one player does not appear on the other (the bar is not broadcast)' }
    )
    foreach ($r in $rows) {
      $out += New-JointAssertion -Id "$prefix/$($r.Id)" -Token $r.What -Pass $false `
        -Evidence 'NOT EXECUTED: not wired (no bot results passed to Invoke-EffectScenarios)' `
        -Kind 'diagnostic'
    }
    return $out
  }

  # ---------------------------------------------------------------------
  # 1. THE ANCHOR: did the mention line reach the mentioned player at all?
  # ---------------------------------------------------------------------
  # Every window below is anchored on THIS row inside the MENTIONED player's
  # recording. Without it there is no time origin and no proof the feature
  # ran, so the dependent rows report that instead of searching a window that
  # starts nowhere.
  $mentionAnchor = $null
  $mentionAnchorMs = $null
  if ($MentionedRes -and $MentionToken -ne '') {
    $cands = @(Get-JointBotRows -BotResult $MentionedRes -Kind 'messagestr' -TextContains $MentionToken)
    if ($cands.Count -eq 0) {
      $cands = @(Get-JointBotRows -BotResult $MentionedRes -TextContains $MentionToken)
    }
    if ($cands.Count -gt 0) {
      $mentionAnchor = $cands[0]
      $mentionAnchorMs = Get-JointBotRowAtMs $mentionAnchor
    }
  }

  # A mention title names the MENTIONER. Both platforms build it that way:
  # VelocityProxyPlatform.notifyMention title = stripColorPrefix(highlight) =
  # "&e"+mentioner stripped -> the bare name; Bukkit NetworkClient.handleMention
  # title = translateColorCodes("&e" + packet.getMentionerName()).
  $titleRowsForMentioned = @()
  if ($MentionedRes -and $MentionerName -ne '') {
    $titleRowsForMentioned = @(Get-JointBotRows -BotResult $MentionedRes -Kind 'title' -TextContains $MentionerName)
  }

  if ($null -eq $MentionedRes) {
    $out += New-JointAssertion -Id "$prefix/mention-title-delivered" -Token $MentionerName `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: the mentioned player''s recording was not passed (-MentionedRes)'
  } elseif ($MentionerName -eq '') {
    $out += New-JointAssertion -Id "$prefix/mention-title-delivered" -Token '(mentioner name)' `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: -MentionerName is empty, so the mention title cannot be identified'
  } else {
    $pass = ($titleRowsForMentioned.Count -gt 0)
    $ev = if ($pass) {
      'title effect naming the mentioner: ' + (Format-JointBotRow $titleRowsForMentioned[0] $mentionAnchorMs)
    } else {
      $kinds = @(Get-JointBotRows -BotResult $MentionedRes -Kind 'title' | ForEach-Object { [string]$_.text })
      $seen = if ($kinds.Count -gt 0) { "title rows seen: $($kinds -join ' | ')" } else { 'no title effect of any kind' }
      "the mentioned player recorded no title naming '$MentionerName'; $seen"
    }
    # GATING: the product's stated effect for a mention is a title on the
    # mentioned player. A mention that renders only as a highlighted chat line
    # (i.e. the notification path died) is exactly the regression this catches.
    $out += New-JointAssertion -Id "$prefix/mention-title-delivered" -Token $MentionerName `
      -Pass $pass -Evidence $ev -Kind 'effect-mention'
  }

  # ---------------------------------------------------------------------
  # 2. Self-notification is already covered by the lite plan
  #    (alice/alice-no-self-mention-title); re-stated here per topology so a
  #    relay round (which has no such plan step) is not silently uncovered.
  # ---------------------------------------------------------------------
  if ($null -eq $MentionerRes) {
    $out += New-JointAssertion -Id "$prefix/mention-self-not-notified" -Token $MentionerName `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: the mentioner''s recording was not passed (-MentionerRes)'
  } elseif ($MentionerName -eq '') {
    $out += New-JointAssertion -Id "$prefix/mention-self-not-notified" -Token '(mentioner name)' `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: -MentionerName is empty'
  } else {
    # Window-scoped to the mention: the same player may legitimately receive a
    # title naming themselves from a MUTE/KICK notice elsewhere in the run
    # (measured: relay java recorded 'You have been kicked from the channel'
    # titles). Only a title in the mention's own window counts.
    $selfTitles = @()
    if ($null -ne $mentionAnchorMs) {
      $selfTitles = @(Get-JointBotRows -BotResult $MentionerRes -Kind 'title' `
        -TextContains $MentionerName -SinceMs ($mentionAnchorMs - 500) -UntilMs ($mentionAnchorMs + 5000))
    } else {
      # No anchor: fall back to a whole-recording search, and say so. A whole
      # recording may contain unrelated titles, so this row is reported as a
      # diagnostic in that case rather than gating on a weaker claim.
      $selfTitles = @(Get-JointBotRows -BotResult $MentionerRes -Kind 'title' -TextContains $MentionerName)
    }
    $anchorKnown = ($null -ne $mentionAnchorMs)
    $pass = ($selfTitles.Count -eq 0)
    $ev = if (-not $pass) {
      'the mentioner was notified of their own mention: ' + (Format-JointBotRow $selfTitles[0])
    } elseif (-not $anchorKnown) {
      'no title naming the mentioner anywhere in their own recording; NOTE: the mention line was not found in the mentioned player''s recording, so this is a whole-recording absence, not a windowed one'
    } else {
      'no title naming the mentioner in the 5 s after the mention'
    }
    $out += New-JointAssertion -Id "$prefix/mention-self-not-notified" -Token $MentionerName `
      -Pass $pass -Evidence $ev -Kind $(if ($anchorKnown) { 'effect-mention' } else { 'diagnostic' })
  }

  # ---------------------------------------------------------------------
  # 3. BYSTANDER: a third player in the SAME channel.
  # ---------------------------------------------------------------------
  # Two rows, and the order matters:
  #   a) CONTROL (gating): the bystander received the chat line that carried the
  #      mention. The mention line is channel-wide, so a bystander who did not
  #      receive it was not in the fan-out and their silence proves nothing.
  #   b) NEGATIVE (gating): no title naming the mentioner in the window.
  # ---------------------------------------------------------------------
  $bystanderLineRows = @()
  if ($BystanderRes -and $MentionToken -ne '') {
    $bystanderLineRows = @(Get-JointBotRows -BotResult $BystanderRes -TextContains $MentionToken)
  }
  $bystanderAnchorMs = $null
  if ($bystanderLineRows.Count -gt 0) { $bystanderAnchorMs = Get-JointBotRowAtMs $bystanderLineRows[0] }

  # The bystander's OWN line, the proof their recording renders traffic at all.
  # Reported alongside the control because a bystander that received the mention
  # line but rendered nothing else is a different situation from one whose
  # channel was fully live.
  $bystanderOwnRows = @()
  if ($BystanderRes -and $OwnChannelToken -ne '') {
    $bystanderOwnRows = @(Get-JointBotRows -BotResult $BystanderRes -TextContains $OwnChannelToken)
  }

  if ($null -eq $BystanderRes) {
    $out += New-JointAssertion -Id "$prefix/mention-line-reached-bystander" -Token $MentionToken `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: no third in-channel observer was passed (-BystanderRes)'
    $out += New-JointAssertion -Id "$prefix/mention-third-party-absent" -Token $MentionerName `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: no third in-channel observer was passed (-BystanderRes)'
  } else {
    $ctlOk = ($bystanderLineRows.Count -gt 0)
    $ctlEv = if ($ctlOk) {
      $base = 'the bystander received the mention line: ' + (Format-JointBotRow $bystanderLineRows[0])
      if ($bystanderOwnRows.Count -gt 0) { $base + '; its own line also came back: ' + (Format-JointBotRow $bystanderOwnRows[0]) }
      else { $base }
    } elseif (-not (Test-JointBotRecording $BystanderRes)) {
      'the bystander produced no recording at all (no inbound rows beyond its lifecycle markers)'
    } else {
      "the bystander recorded no line carrying '$MentionToken', so they were not in the fan-out and their silence would prove nothing"
    }
    $out += New-JointAssertion -Id "$prefix/mention-line-reached-bystander" -Token $MentionToken `
      -Pass $ctlOk -Evidence $ctlEv -Kind 'effect-control'

    # The negative is only allowed to gate when the control held AND the window
    # has a real origin in this recording.
    if ($MentionerName -eq '') {
      $out += New-JointAssertion -Id "$prefix/mention-third-party-absent" -Token '(mentioner name)' `
        -Pass $false -Kind 'diagnostic' -Evidence 'NOT EXECUTED: -MentionerName is empty'
    } elseif (-not $ctlOk -or $null -eq $bystanderAnchorMs) {
      $out += New-JointAssertion -Id "$prefix/mention-third-party-absent" -Token $MentionerName `
        -Pass $false -Kind 'diagnostic' `
        -Evidence ('NOT EXECUTED: no window origin in the bystander''s recording -- ' +
          $(if ($ctlOk) { 'the mention line has no timestamp' } else { 'the bystander never received the mention line' }))
    } else {
      $leak = @(Get-JointBotRows -BotResult $BystanderRes -Kind 'title' -TextContains $MentionerName `
        -SinceMs ($bystanderAnchorMs - 1000) -UntilMs ($bystanderAnchorMs + 6000))
      # A title naming the mentioner that PREDATES the window is reported in the
      # evidence: it tells an author the window was scoped wrongly instead of
      # letting an earlier, unrelated title look like a leak.
      $earlier = @(Get-JointBotRows -BotResult $BystanderRes -Kind 'title' -TextContains $MentionerName `
        -UntilMs ($bystanderAnchorMs - 1001))
      $pass = ($leak.Count -eq 0)
      $ev = if (-not $pass) {
        'the bystander WAS notified: ' + (Format-JointBotRow $leak[0] $bystanderAnchorMs)
      } else {
        $base = 'the bystander received the line but no title naming the mentioner in the 6 s after it'
        if ($earlier.Count -gt 0) { $base + " (an earlier title predates the window: $(Format-JointBotRow $earlier[0]))" } else { $base }
      }
      # Gating: "only the mentioned player is notified" is a stated product
      # contract, and the sender-side filter (MentionNotifier uuid.equals
      # (mentionerId)) is the only exclusion today -- it does not exclude other
      # recipients. A fan-out bug that sent every player's packet to every
      # player, or a proxy that rendered the packet for a whole channel, is
      # exactly this failure.
      $out += New-JointAssertion -Id "$prefix/mention-third-party-absent" -Token $MentionerName `
        -Pass $pass -Evidence $ev -Kind 'effect-privacy'
    }
  }

  # ---------------------------------------------------------------------
  # 4. OFF-CHANNEL: a player whose ACTIVE channel is a different one.
  # ---------------------------------------------------------------------
  # The pre-condition this scenario is worthless without:
  #   the observed filters FAIL OPEN on an unknown active channel
  #   (MentionResolver.isInChannel and ProxyChatCore.isInChannel both treat
  #   null/blank as "in channel"). So the observer must be PROVEN to sit in
  #   another channel -- read from their own recording's join acknowledgement,
  #   not from the harness's intention. If the ack is absent the row reports
  #   NOT EXECUTED rather than a green that means nothing.
  $offAckOk = $false
  $offAckRow = $null
  if ($OffChannelRes -and $OffChannelChannelAck -ne '') {
    $acks = @(Get-JointBotRows -BotResult $OffChannelRes -TextContains $OffChannelChannelAck)
    if ($acks.Count -gt 0) { $offAckOk = $true; $offAckRow = $acks[0] }
  }
  # Positive control for "their channel still works": a line the off-channel
  # player spoke in their OWN channel and saw come back. The join ack only
  # proves MEMBERSHIP; it does not prove this recording's chat path renders
  # anything. Without this token the control is the weaker ack-only form, and
  # the row's evidence says which form was used -- so a green is never read as
  # "their channel was verified by traffic" when it was not.
  $offOwnChannelOk = ($null -ne $offAckRow)
  $offOwnTokenOk = $false
  $offOwnRow = $null
  if ($OffChannelRes -and $OffChannelOwnToken -ne '') {
    $ownRows = @(Get-JointBotRows -BotResult $OffChannelRes -TextContains $OffChannelOwnToken)
    if ($ownRows.Count -gt 0) { $offOwnTokenOk = $true; $offOwnRow = $ownRows[0] }
  }

  if ($null -eq $OffChannelRes) {
    $out += New-JointAssertion -Id "$prefix/mention-line-reached-offchannel" -Token $MentionToken `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: no off-channel observer was passed (-OffChannelRes)'
    $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token $MentionerName `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: no off-channel observer was passed (-OffChannelRes)'
  } elseif (-not $offAckOk) {
    $why = if ($OffChannelChannelAck -eq '') {
      'no channel acknowledgement was configured (-OffChannelChannelAck is empty), so the observer''s active channel is unknown -- and the product FAILS OPEN for an unknown active channel, which would make the negative pass for the wrong reason'
    } else {
      "the observer's own recording carries no '$OffChannelChannelAck', so there is no evidence they joined the other channel; the product fails open on an unknown active channel, so a pass here would prove nothing"
    }
    $out += New-JointAssertion -Id "$prefix/mention-line-reached-offchannel" -Token $MentionToken `
      -Pass $false -Kind 'diagnostic' -Evidence "NOT EXECUTED: $why"
    $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token $MentionerName `
      -Pass $false -Kind 'diagnostic' -Evidence "NOT EXECUTED: $why"
  } else {
    # CONTROL 1 (gating): the off-channel player must NOT have received the
    # mention LINE either -- the line is the thing the mention is attached to,
    # and both renderers gate the line on the active channel. If they received
    # the line, the channel separation itself is broken and the title row below
    # cannot be attributed to the mention filter.
    $offLine = if ($MentionToken -ne '') {
      @(Get-JointBotRows -BotResult $OffChannelRes -TextContains $MentionToken)
    } else { @() }
    # CONTROL 2 (gating): their own channel still DELIVERS. The token form is
    # required when one was configured -- a membership ack proves the observer
    # sits in the other channel, but not that this recording renders traffic,
    # and an absence on a recording that renders nothing is vacuous.
    $ctlOk = $offOwnChannelOk -and ($offLine.Count -eq 0)
    if ($OffChannelOwnToken -ne '') { $ctlOk = $ctlOk -and $offOwnTokenOk }
    $ctlEv = if (-not $offOwnChannelOk) {
      'the observer''s channel state is unproven (no join acknowledgement in its own recording)'
    } elseif ($offLine.Count -gt 0) {
      'the off-channel observer received the mention LINE: ' + (Format-JointBotRow $offLine[0]) +
        ' -- the channel separation is broken before the mention filter is even reached'
    } elseif ($OffChannelOwnToken -ne '' -and -not $offOwnTokenOk) {
      "the observer is proven in the other channel (" + (Format-JointBotRow $offAckRow) +
        ") but recorded no line carrying its own-channel token '$OffChannelOwnToken', so its absence of a mention title proves nothing"
    } else {
      $base = 'the observer is proven in the other channel (' + (Format-JointBotRow $offAckRow) + ') and did not receive the mention line'
      if ($offOwnTokenOk) { $base + '; its own channel still delivers: ' + (Format-JointBotRow $offOwnRow) }
      else { $base + '; NOTE: no own-channel token was configured, so "their channel works" rests on the join ack alone' }
    }
    $out += New-JointAssertion -Id "$prefix/mention-line-reached-offchannel" -Token $MentionToken `
      -Pass $ctlOk -Evidence $ctlEv -Kind 'effect-control'

    if (-not $ctlOk) {
      $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token $MentionerName `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: the off-channel control failed, so a title absence here cannot be attributed to the mention filter'
    } elseif ($MentionerName -eq '') {
      $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token '(mentioner name)' `
        -Pass $false -Kind 'diagnostic' -Evidence 'NOT EXECUTED: -MentionerName is empty'
    } elseif ($null -eq $mentionAnchorMs) {
      $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token $MentionerName `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: the mention line was not found in the mentioned player''s recording, so there is no window to search in'
    } else {
      # The window is anchored on the MENTIONED player's row (the trigger), then
      # applied to the off-channel recording. Both recordings come from the
      # same run and the bots' clocks share the host, so an epoch-ms window is
      # meaningful; the tolerance absorbs dispatch jitter, not clock skew.
      $leak = @(Get-JointBotRows -BotResult $OffChannelRes -Kind 'title' -TextContains $MentionerName `
        -SinceMs ($mentionAnchorMs - 2000) -UntilMs ($mentionAnchorMs + 8000))
      $pass = ($leak.Count -eq 0)
      $ev = if (-not $pass) {
        'the off-channel player WAS notified: ' + (Format-JointBotRow $leak[0] $mentionAnchorMs)
      } else {
        "the off-channel player received no title naming '$MentionerName' within 8 s of the mention at +0ms"
      }
      # Gating, and expected RED against the pre-fix tree: the filters are
      # channel-scoped now, so this is a real regression guard. A RED here that
      # names a channel leak is the point of the harness.
      $out += New-JointAssertion -Id "$prefix/mention-channel-scoped-absent" -Token $MentionerName `
        -Pass $pass -Evidence $ev -Kind 'effect-privacy'
    }
  }

  # ---------------------------------------------------------------------
  # 5. SOUND: diagnostic, with the observed packet names in the evidence.
  # ---------------------------------------------------------------------
  # See the file header for the bytecode proof and the measured runs. This row
  # is written to become gating the moment a packet IS observed: it reports the
  # sound rows AND the raw packet names that arrived, so "the server sent
  # nothing" is distinguishable from "the server sent one the library does not
  # surface" (joint-bot.js instruments soundEffectHeard, soundId,
  # entity_sound_effect and a raw /sound/i probe).
  if ($null -eq $MentionedRes) {
    $out += New-JointAssertion -Id "$prefix/mention-sound-observable" -Token 'experience_orb' `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: the mentioned player''s recording was not passed (-MentionedRes)'
  } else {
    $soundRows = @()
    $rawRows = @()
    $sinceMs = [double]::NegativeInfinity
    $untilMs = [double]::PositiveInfinity
    if ($null -ne $mentionAnchorMs) {
      $sinceMs = $mentionAnchorMs - 1000
      $untilMs = $mentionAnchorMs + 6000
    }
    $soundRows = @(Get-JointBotRows -BotResult $MentionedRes -Kind 'sound' -SinceMs $sinceMs -UntilMs $untilMs)
    $soundRows += @(Get-JointBotRows -BotResult $MentionedRes -Kind 'soundId' -SinceMs $sinceMs -UntilMs $untilMs)
    $rawRows = @(Get-JointBotRows -BotResult $MentionedRes -Kind 'rawSoundPacket' -SinceMs $sinceMs -UntilMs $untilMs)
    $orbRows = @($soundRows | Where-Object { ([string]$_.text).Contains('experience_orb') })

    if ($orbRows.Count -gt 0) {
      $out += New-JointAssertion -Id "$prefix/mention-sound-observable" -Token 'experience_orb' `
        -Pass $true -Kind 'diagnostic' `
        -Evidence ('MEASURED OBSERVABLE -- promote this row to gating: ' + (Format-JointBotRow $orbRows[0] $mentionAnchorMs))
    } else {
      $names = @($rawRows | ForEach-Object { [string]$_.text } | Sort-Object -Unique)
      $what = if ($rawRows.Count -gt 0) {
        "sound-carrying packets DID arrive in the window: $($names -join ', '); none is the mention sound (no experience_orb)"
      } elseif ($soundRows.Count -gt 0) {
        "sound effects arrived in the window but none is the mention sound: $((@($soundRows | ForEach-Object { [string]$_.text }) -join ' | '))"
      } else {
        'NO sound-carrying packet of any kind arrived in the window'
      }
      $out += New-JointAssertion -Id "$prefix/mention-sound-observable" -Token 'experience_orb' `
        -Pass $false -Kind 'diagnostic' `
        -Evidence ("${what}. Expected on this topology: Velocity ConnectedPlayer#playSound writes only ClientboundSoundEntityPacket (entity_sound_effect) and returns early unless the player has a connected backend server entity, which a proxy-connected bot does not; Bukkit's own playSound path only runs for a player whose MentionPacket arrived on the sub-server (direct-connect), and in relay the proxy has already notified that player and passes them in `excluded`.")
    }
  }

  # ---------------------------------------------------------------------
  # 6. TITLE (`/nc title <channel> <text>`), coverage plan area 3.
  # ---------------------------------------------------------------------
  # GATED ROWS: 1, 2 (delivery), 11 (defaults), 12 (other channel).
  # DOCUMENTED GAP: custom fade timings. No client sets the AdminActionPacket
  # extras: TitleCommand sets only title/subtitle, and the backend console
  # `title` accepts no timing arguments. A custom-timing assertion would be
  # asserting a value the harness itself invented, so the row is pinned to the
  # packet DEFAULTS (TitlePacket constructor: fadeIn=10, stay=70, fadeOut=20)
  # and the evidence says it proves pass-through, not configurability.
  #
  # WHAT PRODUCES IT: on lite NOTHING can (ProxyCommandCore.SUBCOMMANDS has no
  # `title`, so a proxy player's /nc title is read as a message to a channel
  # named "title"; a direct-connect player's /nc title stops at
  # checkConnection()). On relay, the backend CONSOLE is the working path:
  # ConsoleCommandHandler.handleTitle builds the packet with the console
  # sentinel and no session check, unlike AdminActionHandler.handleTitle which
  # is gated on permissionManager.hasSuperAdminSession. It needs a stdin writer
  # in run-joint.ps1 (Send-BackendConsole), which this file does not own.
  if (-not $anyResult -or $null -eq $TitleRes) {
    # reachable only when the caller passed other results but not this one
    foreach ($id in @('title-delivered-in-channel', 'title-subtitle-delivered', 'title-timings-default', 'title-absent-other-channel')) {
      $out += New-JointAssertion -Id "$prefix/$id" -Token $TitleText `
        -Pass $false -Kind 'diagnostic' `
        -Evidence "NOT EXECUTED: the in-channel title receiver's recording was not passed (-TitleRes)"
    }
  } elseif ($TitleText -eq '') {
    # The receiver's recording exists but the plan never declared WHAT was sent.
    # Every title row is emitted with the same reason, so the report is complete
    # rather than silently dropping two of the four rows.
    $why = 'NOT EXECUTED: -TitleText is empty; the plan did not declare what the authority was asked to send'
    foreach ($id in @('title-delivered-in-channel', 'title-subtitle-delivered', 'title-timings-default', 'title-absent-other-channel')) {
      $out += New-JointAssertion -Id "$prefix/$id" -Token '(title text)' -Pass $false -Kind 'diagnostic' -Evidence $why
    }
  } else {
    # Row 6.1/6.2: the title text and a subtitle.
    # Both renderers prefix the payload: Java's set_title_text is recorded as
    # 'title: <text>' (joint-bot.js:609) and Bedrock's set_title as
    # 'title: <text>' too (joint-bot.js:846), so the bare text is the correct
    # substring in either kind.
    $titleRows = @(Get-JointBotRows -BotResult $TitleRes -Kind 'title' -TextContains $TitleText)
    # Prefer a row that is a TITLE rather than a SUBTITLE: a subtitle carrying
    # the same text would otherwise satisfy the delivery row.
    $titleLine = $null
    foreach ($r in $titleRows) {
      if (([string]$r.text).StartsWith('title: ')) { $titleLine = $r; break }
    }
    if ($null -eq $titleLine -and $titleRows.Count -gt 0) { $titleLine = $titleRows[0] }

    $titlePass = ($null -ne $titleLine)
    $titleEv = if ($titlePass) {
      'title effect: ' + (Format-JointBotRow $titleLine)
    } elseif ($titleRows.Count -gt 0) {
      'the text arrived but never as a title row: ' + (Format-JointBotRow $titleRows[0])
    } else {
      $allTitles = @(Get-JointBotRows -BotResult $TitleRes -Kind 'title' | ForEach-Object { [string]$_.text })
      if ($allTitles.Count -gt 0) { "no title carrying '$TitleText'; title rows seen: $($allTitles -join ' | ')" }
      else { "no title effect of any kind in the receiver's recording" }
    }
    # GATING: this is the product's whole /nc title effect. A packet that is
    # produced but not rendered (wrong channel scope, dropped at the adapter)
    # is invisible to every chat assertion in the harness.
    $out += New-JointAssertion -Id "$prefix/title-delivered-in-channel" -Token $TitleText `
      -Pass $titlePass -Evidence $titleEv -Kind 'effect-title'

    # Subtitle: a SEPARATE text, not a copy of the title. The title's own window
    # anchors the search so an unrelated subtitle elsewhere in the run (a
    # mute/kick notice also uses titles) cannot satisfy this. SubtitleText is
    # optional: /nc title <channel> <title> [subtitle] makes it optional, so an
    # absent subtitle is correct behaviour when the plan sent none, and the row
    # is then DIAGNOSTIC with that stated.
    $titleAnchorMs0 = Get-JointBotRowAtMs $titleLine
    $subRows = @()
    if ($null -ne $titleAnchorMs0) {
      $subRows = @(Get-JointBotRows -BotResult $TitleRes -Kind 'title' -TextContains 'subtitle: ' `
        -SinceMs ($titleAnchorMs0 - 2000) -UntilMs ($titleAnchorMs0 + 3000))
    } else {
      $subRows = @(Get-JointBotRows -BotResult $TitleRes -Kind 'title' -TextContains 'subtitle: ')
    }
    if ($SubtitleText -ne '') {
      $subRows = @($subRows | Where-Object { ([string]$_.text).Contains($SubtitleText) })
    }
    if ($subRows.Count -gt 0) {
      $out += New-JointAssertion -Id "$prefix/title-subtitle-delivered" -Token $SubtitleText `
        -Pass $true -Kind 'effect-title' `
        -Evidence ('subtitle effect: ' + (Format-JointBotRow $subRows[0] $titleAnchorMs0))
    } elseif ($SubtitleText -ne '') {
      # The plan declared a subtitle and it did not arrive: that IS a defect,
      # because both renderers carry it in the same packet pair.
      $out += New-JointAssertion -Id "$prefix/title-subtitle-delivered" -Token $SubtitleText `
        -Pass $false -Kind 'effect-title' `
        -Evidence "the plan declared subtitle '$SubtitleText' but no 'subtitle: ' row carrying it arrived within 3 s of the title"
    } else {
      $out += New-JointAssertion -Id "$prefix/title-subtitle-delivered" -Token '(subtitle)' `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT APPLICABLE: -SubtitleText is empty, i.e. the plan sent a title with no subtitle (the console command third argument is optional), so an absent subtitle is correct'
    }

    # Row 6.3: default timings, as their own row. The plan must read titleTimes
    # as a row, not infer it from correlation -- joint-bot.js attaches
    # pendingTimes to the next text row, which is a convenience, not the
    # contract.
    $timesRows = @(Get-JointBotRows -BotResult $TitleRes -Kind 'titleTimes' -TextContains 'fadeIn=10 stay=70 fadeOut=20')
    if ($timesRows.Count -eq 0 -and $titleRows.Count -gt 0) {
      # Fall back to the `times` field the bot attached to the text row: on Java
      # it is the most recent title_times; on Bedrock it comes from
      # set_durations (which folds durations into set_title). Both are the
      # packet's own values, so this is still evidence, not an inference.
      foreach ($r in $titleRows) {
        $tn = $null
        try { $tn = $r.data.times } catch { $tn = $null }
        if ($null -ne $tn -and "$($tn.fadeIn)" -eq '10' -and "$($tn.stay)" -eq '70' -and "$($tn.fadeOut)" -eq '20') {
          $timesRows = @($r); break
        }
      }
    }
    $timesPass = ($timesRows.Count -gt 0)
    $timesEv = if ($timesPass) {
      'timings carried by the packet: ' + (Format-JointBotRow $timesRows[0])
    } else {
      $observed = @(Get-JointBotRows -BotResult $TitleRes -Kind 'titleTimes' | ForEach-Object { [string]$_.text })
      if ($observed.Count -gt 0) { "title timings seen in the recording but not 10/70/20: $($observed -join ' | ')" }
      else { 'no titleTimes row and no times field carrying 10/70/20 on a title row' }
    }
    # GATING: 70 is the DISCRIMINATOR. Mention titles are stay=40, so a stay=70
    # row proves the title came from a TitlePacket and not from a mention path.
    # It also proves the four-layer pass-through (command -> AdminActionPacket
    # extras -> TitlePacket fields -> platform sendTitle/showTitle); any layer
    # that dropped the extras falls back to these defaults SILENTLY, and only a
    # timing row notices.
    # DOCUMENTED GAP: custom values are NOT assertable today; see the header.
    $out += New-JointAssertion -Id "$prefix/title-timings-default" -Token 'fadeIn=10 stay=70 fadeOut=20' `
      -Pass $timesPass -Evidence $timesEv -Kind 'effect-title'

    # Row 6.4: no bleed into another channel. Bukkit filters by
    # state.getActiveChannel() (NetworkClient.handleTitle) and the proxy by the
    # same test (ProxyChatCore.handleTitle), but both are `state != null`-
    # guarded, i.e. a player with NO state is excluded by accident rather than
    # by the channel test, which is why the off-channel positive control
    # (offAckOk) must hold before this row may gate.
    if ($null -eq $OffChannelRes) {
      $out += New-JointAssertion -Id "$prefix/title-absent-other-channel" -Token $TitleText `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: no off-channel observer was passed (-OffChannelRes)'
    } elseif (-not $offAckOk) {
      $out += New-JointAssertion -Id "$prefix/title-absent-other-channel" -Token $TitleText `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: the off-channel observer channel is unproven (see mention-line-reached-offchannel)'
    } elseif ($null -eq $titleLine) {
      $out += New-JointAssertion -Id "$prefix/title-absent-other-channel" -Token $TitleText `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: the title never reached its intended channel, so its absence elsewhere is not evidence of filtering'
    } else {
      $titleAnchorMs = Get-JointBotRowAtMs $titleLine
      $bleed = @()
      if ($null -ne $titleAnchorMs) {
        $bleed = @(Get-JointBotRows -BotResult $OffChannelRes -Kind 'title' -TextContains $TitleText `
          -SinceMs ($titleAnchorMs - 2000) -UntilMs ($titleAnchorMs + 8000))
      } else {
        $bleed = @(Get-JointBotRows -BotResult $OffChannelRes -Kind 'title' -TextContains $TitleText)
      }
      $bleedPass = ($bleed.Count -eq 0)
      $bleedEv = if (-not $bleedPass) {
        'the off-channel player received it: ' + (Format-JointBotRow $bleed[0] $titleAnchorMs)
      } elseif ($null -eq $titleAnchorMs) {
        'no matching title anywhere in the off-channel recording (the delivered title had no timestamp, so this is a whole-recording absence)'
      } else {
        'the off-channel player received no title carrying the text within 8 s of the in-channel delivery'
      }
      $out += New-JointAssertion -Id "$prefix/title-absent-other-channel" -Token $TitleText `
        -Pass $bleedPass -Evidence $bleedEv -Kind 'effect-privacy'
    }
  }

  # ---------------------------------------------------------------------
  # 7. ACTION BAR content, coverage plan area 4.
  # ---------------------------------------------------------------------
  # Lite already asserts that SOME bar appears on join
  # (alice/expect-join-actionbar, bob/expect-join-actionbar, PASS). That is
  # satisfied by any bar-shaped string. What it cannot catch is a bar naming
  # the WRONG channel -- the leave path resolves state.getActiveChannel() AFTER
  # the optimistic local mutation (PlayerChannelState.leaveChannel), so a
  # fallback-order change makes the player be told a channel the authority does
  # not agree with.
  #
  # LOCALE: PlayerMessages.currentChannelBar renders in the PLAYER's locale
  # (chat.status.current_bar: 'Current channel: {0} ({1})' en_US /
  # '当前频道：{0}（{1}）' zh_CN). Measured: a Bedrock bot recorded
  # '§7当前频道：§bglobal §7（Channel mode）' while a Java bot recorded
  # 'Current channel: global (Channel mode)'. Matching the English label on a
  # zh_CN client is a FALSE FAILURE, so the assertion is anchored on the channel
  # id and the label is used only when -ActionBarLabel is non-empty AND the row
  # actually contains it.
  $barRes = $MentionedRes
  if ($null -eq $barRes) {
    $out += New-JointAssertion -Id "$prefix/actionbar-content-matches-channel" -Token $ChannelA `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: the in-channel player''s recording was not passed (-MentionedRes)'
  } elseif ($ChannelA -eq '') {
    $out += New-JointAssertion -Id "$prefix/actionbar-content-matches-channel" -Token '(channel)' `
      -Pass $false -Kind 'diagnostic' -Evidence 'NOT EXECUTED: -ChannelA is empty'
  } else {
    $bars = @(Get-JointBotRows -BotResult $barRes -Kind 'actionBar')
    $chanBars = @($bars | Where-Object { ([string]$_.text).Contains($ChannelA) })
    $pass = ($chanBars.Count -gt 0)
    $ev = if ($pass) {
      $strong = $false
      if ($ActionBarLabel -ne '') {
        $strong = @($chanBars | Where-Object { ([string]$_.text).Contains($ActionBarLabel) }).Count -gt 0
      }
      $row = Format-JointBotRow $chanBars[0]
      if ($ActionBarLabel -eq '') { "bar names the channel id: $row (label check disabled: -ActionBarLabel empty, i.e. non-English locale)" }
      elseif ($strong) { "bar names the channel id and the locale label: $row" }
      else { "bar names the channel id but not the label '$ActionBarLabel' (check the client locale before treating this as a defect): $row" }
    } elseif ($bars.Count -gt 0) {
      $seen = @($bars | ForEach-Object { [string]$_.text }) -join ' | '
      "an action bar appeared but none names '$ChannelA': $seen"
    } else {
      'no actionBar effect of any kind in the recording'
    }
    # GATING on the channel id: that substring is locale-independent, so the
    # row cannot be satisfied by "some bar" and cannot fail for i18n reasons.
    # What it catches: a join/leave bar for the wrong channel, or a bar that
    # lost the channel id from the message template.
    $out += New-JointAssertion -Id "$prefix/actionbar-content-matches-channel" -Token $ChannelA `
      -Pass $pass -Evidence $ev -Kind 'effect-actionbar'
  }

  # 7b. The bar is not broadcast: the join's bar must not appear on a peer.
  # VelocityProxyPlatform.showChannelStatusBar is a one-shot sendActionBar to
  # ONE player, so this holds today; the assertion exists because the leave
  # path takes a UUID and resolves the state from it (sendLeaveChannelStatusBar
  # (UUID)), which is exactly the shape a copy-paste turns into "flash the
  # requester's bar on the wrong player".
  if ($null -eq $BystanderRes -or $null -eq $barRes) {
    $out += New-JointAssertion -Id "$prefix/actionbar-not-on-peer" -Token $ChannelA `
      -Pass $false -Kind 'diagnostic' `
      -Evidence 'NOT EXECUTED: both the joining player''s and a peer''s recording are required (-MentionedRes, -BystanderRes)'
  } else {
    # Anchor on the joining player's own bar, then ask whether the peer recorded
    # a bar in the same window. Only the channel-id form is searched: the peer
    # may legitimately receive its OWN bar for its own join, which is a
    # different channel and therefore a different string.
    $mine = @(Get-JointBotRows -BotResult $barRes -Kind 'actionBar' -TextContains $ChannelA)
    $anchorMs = $null
    if ($mine.Count -gt 0) { $anchorMs = Get-JointBotRowAtMs $mine[0] }
    if ($mine.Count -eq 0 -or $null -eq $anchorMs) {
      $out += New-JointAssertion -Id "$prefix/actionbar-not-on-peer" -Token $ChannelA `
        -Pass $false -Kind 'diagnostic' `
        -Evidence 'NOT EXECUTED: the joining player recorded no timestamped bar naming its channel, so there is no anchor to search the peer against'
    } else {
      $peerBars = @(Get-JointBotRows -BotResult $BystanderRes -Kind 'actionBar' -TextContains $ChannelA `
        -SinceMs ($anchorMs - 1500) -UntilMs ($anchorMs + 4000))
      $pass = ($peerBars.Count -eq 0)
      $ev = if ($pass) { "the peer recorded no bar naming '$ChannelA' in the 4 s after the join bar" }
              else { 'the peer recorded the same channel bar: ' + (Format-JointBotRow $peerBars[0] $anchorMs) }
      $out += New-JointAssertion -Id "$prefix/actionbar-not-on-peer" -Token $ChannelA `
        -Pass $pass -Evidence $ev -Kind 'effect-actionbar'
    }
  }

  return $out
}

function Get-EffectsPlanSteps {
  <#
  .SYNOPSIS
    Bot-plan step fragments that produce the effects Invoke-EffectScenarios
    asserts on.

  .DESCRIPTION
    Kept separate from Invoke-EffectScenarios so a wiring mistake in either half
    is visible on its own: this half can be inspected for shape without running
    a round, and the assertion half can be exercised with synthetic results.

    Returns: @{ Mentioner = @(...); Mentioned = @(...); Bystander = @(...);
                OffChannel = @(...); Tokens = @{...}; Handoffs = @(...) }

    These fragments are APPENDED to the existing role plans. They assume the
    role has already joined ChannelA (mentions in lite only fire for players the
    authority knows) and that `expect` arms precede the lines they expect.

    ORDERING RULES ENCODED HERE, each of which has bitten a previous run:

      * `expectEvent` searches the WHOLE effect history, so an effect that
        arrived during an earlier wait still matches; an `expect` armed too late
        can never match. Positive effects are therefore armed EARLY and the
        chat tokens immediately before the speak step.
      * `expectEventAbsent` is non-blocking and computes its verdict over a
        WINDOW that starts when the step runs. It must be armed BEFORE the
        effect occurs, otherwise the window opens after the leak and the
        negative passes vacuously. Every one below is armed ahead of the
        handoff that releases the mentioner.
      * A negative needs a POSITIVE control. The bystander and off-channel
        plans each send/receive a line in their OWN channel, and the assertion
        half requires that control before its negative row is allowed to gate.
      * The mention dedup window is 3 s per (mentioned, mentioner) pair
        (MentionNotifier.DEDUP_INTERVAL_MS). The fragments below mention a given
        player ONCE per round; nothing here relies on a repeat being delivered.

  .PARAMETER RunId
    The round token prefix; must match Invoke-EffectScenarios -RunId.

  .PARAMETER MentionerName
    The bot that speaks the mention line.

  .PARAMETER MentionedName
    The bot the mention line NAMES. Required: a line that names nobody produces
    no MentionPacket, and every negative in Invoke-EffectScenarios would then
    pass for the wrong reason. Blank yields a single marker step.

  .PARAMETER ChannelA / ChannelB
    ChannelA is where the mention is spoken. ChannelB is the off-channel
    observer's channel; it must be a channel BOTH the authority and the client
    know (lite: `local` already declared; relay: add it to global_channels).

  .PARAMETER BystanderJoinsChannelA
    When true the bystander is given an explicit `/nc join $ChannelA` plus the
    ack expect first. When false (default) the caller asserts the bystander is
    already in ChannelA, and only the absence arm and its line are added -- that
    is the case for a plan that joined the channel in an earlier phase.

  .PARAMETER TitleText
    The text the (external) title producer will send. This half never ISSUES a
    title: the working path is the backend console, which needs a stdin writer
    in run-joint.ps1 (Send-BackendConsole), and lite has no title path at all.
    The fragment therefore only ARMS the receiver's expectations, so the console
    writer can drive the trigger once it exists. Empty produces no title steps.

  .EXAMPLE
    $steps = Get-EffectsPlanSteps -RunId $tokenPrefix -MentionerName 'E2EJointJava' `
               -ChannelA 'global' -ChannelB 'local' -TitleText $tTitle
  #>
  param(
    [string]$RunId,
    [string]$MentionerName = '',
    [string]$MentionedName = '',
    [string]$BystanderName = '',
    [string]$ChannelA = 'global',
    [string]$ChannelB = 'local',
    [switch]$BystanderJoinsChannelA,
    [string]$TitleText = ''
  )

  $tMention = "$RunId-effect-mention"
  $tOwnA    = "$RunId-effect-ownA"
  $tOwnB    = "$RunId-effect-ownB"

  # MENTIONER: speak the mention once, gated by the observers' readiness.
  #
  # The line names THE MENTIONED PLAYER. `-MentionedName` is therefore required
  # for this fragment to produce anything meaningful; without it the plan would
  # send a line naming nobody, no MentionPacket would be built, and every
  # negative below would pass vacuously. The fragment refuses to build a ladder
  # in that case and says why in its own step id.
  $mentioner = @()
  if ($MentionerName -eq '' -or $MentionedName -eq '') {
    # Deliberately NOT a step that silently does nothing: a `wait` with a
    # diagnostic id leaves a marker in the plan file and in the bot's stdout,
    # so a reader sees why no mention was spoken instead of inferring it from
    # an empty effects stream.
    $mentioner = @(
      @{ id = 'eff-NOT-CONFIGURED-need-mentioner-and-mentioned'; action = 'wait'; ms = 1 }
    )
  } else {
    # NOTE ON "ARM THEN SPEAK": `expect` BLOCKS (`await expectToken(...)` in
    # joint-bot.js runPlan) until the token arrives or its timeout expires. An
    # `expect` for a line this bot is about to send therefore deadlocks. The
    # mentioner declares nothing and derives its own echo from `received` on the
    # orchestrator side instead, which is also the evidence rule: the recorded
    # line, not the sender's claim.
    $mentioner = @(
      # Wait until BOTH observers have armed their negatives. Two distinct keys
      # on purpose: a single key cannot express "the second one is ready too",
      # and a fixed sleep is what turns a slow bot into a flaky negative.
      @{ id = 'eff-wait-bystander';  action = 'handoffGet'; key = 'eff-bystander-ready'; timeoutMs = 200000 },
      @{ id = 'eff-wait-offchannel'; action = 'handoffGet'; key = 'eff-offchannel-ready'; timeoutMs = 200000 },
      # Slack AFTER both arms are live: each arm is a blocking
      # `expectEventAbsent` whose window starts when the step runs, so the
      # mention must land INSIDE those windows. The arms start within
      # milliseconds of their readiness keys; this wait guarantees the mention
      # is at least 2 s into the window rather than racing its start.
      @{ id = 'eff-settle';          action = 'wait'; ms = 2000 },
      @{ id = 'eff-send-mention';    action = 'chat'; text = "@$MentionedName $tMention" },
      # Release the off-channel phase: it may only speak in ChannelB AFTER the
      # mention, otherwise its own-channel control line would share the window.
      @{ id = 'eff-mention-sent';    action = 'handoffSet'; key = 'eff-mention-sent'; pattern = [regex]::Escape($tMention) }
    )
  }

  # MENTIONED: the receiver whose title is the product's intended effect.
  $mentioned = @(
    # expectEvent searches history, so arming it late is harmless; arming it
    # early is free. The match is the MENTIONER's name because that is what the
    # title carries on every renderer that emits one.
    @{ id = 'eff-expect-mention-title'; action = 'expectEvent'; kind = 'title'; match = $MentionerName; timeoutMs = 120000 },
    @{ id = 'eff-expect-mention-line';  action = 'expect'; token = $tMention; timeoutMs = 120000 },
    # Sound: DIAGNOSTIC. It reports whether anything sound-shaped arrived; see
    # Invoke-EffectScenarios for why it cannot gate today.
    @{ id = 'eff-expect-mention-sound'; action = 'expectEvent'; kind = 'sound'; match = 'experience_orb'; timeoutMs = 8000; diagnostic = $true }
  )

  # BYSTANDER: in ChannelA, must receive the line and no notification.
  #
  # WHY THERE IS NO `expect` FOR THE MENTION LINE HERE. `expect` BLOCKS until the
  # token arrives, and `expectEventAbsent` also blocks for its whole window. Two
  # blocking steps cannot both cover the instant of the mention, and whichever
  # runs second opens its window too late. More importantly, `expectedTokens`
  # only gates the bot's OWN expect machinery: joint-bot.js records every
  # inbound `messagestr` into `received[]` unconditionally (the record() call in
  # the messagestr handler is not gated). The orchestrator therefore reads the
  # mention line out of `received[]` regardless of what the plan declared, which
  # is what mention-line-reached-bystander does.
  #
  # What the plan DOES need is the neck of the arm-open window to be inside the
  # mention, so the ONLY blocking step is the absence arm, and readiness is
  # published immediately before it.
  $bystander = @()
  if ($BystanderJoinsChannelA) {
    $bystander = @(
      @{ id = 'eff-join-a-ack'; action = 'expect'; token = "Joined channel $ChannelA"; timeoutMs = 30000 },
      @{ id = 'eff-join-a';     action = 'cmd';    text = "/nc join $ChannelA" },
      @{ id = 'eff-join-a-settle'; action = 'wait'; ms = 1500 }
    )
  }
  $bystander += @(
    # Own-channel control, sent BEFORE the window opens so it can never be
    # mistaken for a mention effect. The line is also recorded unconditionally,
    # so no `expect` is needed (and a blocking `expect` here would delay the arm).
    @{ id = 'eff-bystander-own-line'; action = 'chat'; text = "effect bystander $tOwnA" },
    @{ id = 'eff-bystander-own-settle'; action = 'wait'; ms = 500 },
    # READINESS, then ARM. The mentioner waits for this key and then for 2 s of
    # slack, so the window below is provably open before the mention can land.
    @{ id = 'eff-bystander-ready'; action = 'handoffSet'; key = 'eff-bystander-ready'; pattern = 'spawned' },
    # ARM BEFORE THE MENTION, and the only blocking step: 12 s of window covering
    # the mention that the released mentioner sends 2 s from now.
    @{ id = 'eff-absent-mention-title'; action = 'expectEventAbsent'; kind = 'title'; match = $MentionerName; withinMs = 12000 }
  )

  # OFF-CHANNEL: joins ChannelB, proves it with an ack, exercises its OWN channel
  # as the positive control, then arms the negative and blocks out the window.
  #
  # ORDER IS LOAD-BEARING. `expect` blocks, so the own-channel control must be
  # COMPLETED before the negative window opens -- otherwise the control line's
  # own echo would be racing the window. And the negative arm must be running
  # before the mentioner's release handoff, or its window opens after the leak.
  $offChannel = @(
    @{ id = 'eff-join-b-ack'; action = 'expect'; token = "Joined channel $ChannelB"; timeoutMs = 30000 },
    @{ id = 'eff-join-b';     action = 'cmd';    text = "/nc join $ChannelB" },
    # The ack must be RECORDED, not merely awaited: the assertion half reads it
    # out of this bot's own recording to prove the observer really sits in
    # another channel (the product fails open when the active channel is
    # unknown, so an unproven channel makes the negative worthless).
    @{ id = 'eff-join-b-settle'; action = 'wait'; ms = 1500 },
    # POSITIVE CONTROL: this bot speaks in its OWN channel. NO `expect` here --
    # `expect` BLOCKS (`await expectToken(...)`), so an expect for a line this
    # bot has not sent yet would wait out its timeout and the send would never
    # run. `received[]` records every inbound row unconditionally, so the
    # orchestrator reads this line back without needing a declared token; the
    # declared-token gate only affects the bot's own expect machinery.
    @{ id = 'eff-send-own-b'; action = 'chat'; text = "effect own-channel $tOwnB" },
    @{ id = 'eff-own-b-settle'; action = 'wait'; ms = 500 },
    # ARM THE NEGATIVE. This blocks for its whole window (14 s) while the
    # mentioner, released by the handoff below, speaks inside it.
    @{ id = 'eff-offchannel-ready'; action = 'handoffSet'; key = 'eff-offchannel-ready'; pattern = 'spawned' },
    @{ id = 'eff-absent-mention-title-b'; action = 'expectEventAbsent'; kind = 'title'; match = $MentionerName; withinMs = 14000 },
    @{ id = 'eff-wait-mention'; action = 'handoffGet'; key = 'eff-mention-sent'; timeoutMs = 200000 }
  )

  # TITLE RECEIVER: arms only. The producer is the backend console (relay) and
  # is a run-joint.ps1 change; on lite no path exists at all (see the file
  # header). `match` carries the 'title: ' prefix because expectEvent matches the
  # row's TEXT, and joint-bot.js records Java's set_title_text as
  # 'title: <text>' and Bedrock's set_title the same way -- a bare text match
  # would also be satisfied by the SUBTITLE row, which is a different packet.
  $titleReceiver = @()
  if ($TitleText -ne '') {
    $titleReceiver = @(
      @{ id = 'eff-expect-title';    action = 'expectEvent'; kind = 'title'; match = "title: $TitleText"; timeoutMs = 120000 },
      @{ id = 'eff-expect-title-times'; action = 'expectEvent'; kind = 'titleTimes'; match = 'fadeIn=10 stay=70 fadeOut=20'; timeoutMs = 120000 }
    )
  }

  return @{
    Mentioner     = $mentioner
    Mentioned     = $mentioned
    Bystander     = $bystander
    OffChannel    = $offChannel
    TitleReceiver = $titleReceiver
    Tokens        = @{
      Mention   = $tMention
      OwnA      = $tOwnA
      OwnB      = $tOwnB
      Title     = $TitleText
    }
    Handoffs      = @('eff-bystander-ready', 'eff-offchannel-ready', 'eff-mention-sent')
  }
}
