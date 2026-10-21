# --- social scenarios: ignore, /nc who, /nc list ------------------------------
#
# Coverage plan areas 7 and 10.3. This file only ASSERTS; it starts nothing and
# drives nothing. The caller (run-joint.ps1) owns the bot plans that produce the
# recordings, because a token is only recorded by a bot whose plan declared it
# (joint-bot.js `noteInbound` / `expectedTokens`) -- an assertion cannot arm one.
#
# ============================================================================
# WHAT IGNORE ACTUALLY PROMISES (read from the code, not assumed)
# ============================================================================
# Ignore is a CLIENT-SIDE FILTER. There is no backend enforcement on the chat
# fan-out at all:
#
#   * `StarLink/core/.../channel/MessagePipeline.java` contains NO ignore
#     reference. `calculateRecipients` selects by channel membership only.
#   * The filter runs per RENDERER, against the ignore list held by THAT
#     process:
#       - proxy players  -> ProxyChatCore.handleIncomingMessage:301-304
#                           (list written by ProxyCommandCore.handleIgnore:475)
#       - bukkit/folia   -> ChatInterceptor.handleIncomingMessage:305-309
#       - nukkit          -> ChatInterceptor:174 (and :466, :506 for mentions)
#       - pnx             -> ChatInterceptor.sendToPlayersFiltered:283-291
#       - sponge          -> ChatListener:183 (and :485)
#       - MOD/fabric      -> ChatInterceptor:178 (and :393)
#   * `/nc ignore` writes only to the invoking player's process-local
#     IgnoreListService (ProxyCommandCore.handleIgnore -> IgnoreCommandService);
#     it sends no packet.
#
# CONSEQUENCE, and it shapes this whole group: the assertions are only
# meaningful when the IGNORER's chat is rendered by the SAME process that holds
# their ignore list. In this harness that means the ignorer must be
# PROXY-CONNECTED (lite: bob; relay: the java bot). A direct-connect player's
# `/nc ignore` populates the sub-server's list, which the proxy never consults;
# conversely a proxy player's list is invisible to every sub-server. Asserting
# "ignore hides a line" for a direct-connect ignorer would therefore fail for a
# topology reason that is NOT a bug, so this group does not do it -- the finding
# is reported instead (see the final `ignore-is-client-side` row).
#
# Ignore is also DIRECTIONAL and the DM path has a separate privacy split
# (LocalAuthority.handlePrivateMessage:1229-1236, PrivateMessageHandler:387-406).
# Neither is asserted here: the coverage plan puts DMs in area 1 and
# directionality in 7.5, and duplicating them would make a failure ambiguous.
#
# ============================================================================
# WHAT THIS GROUP ASSERTS, AND WHAT EACH ROW CATCHES
# ============================================================================
# Ignore (all gating, all evidence from a RECEIVER's recording):
#   ignore-control-before   bob received an alice line BEFORE the ignore.
#                           Without it, "no line after the ignore" is equally
#                           explained by a broken channel. Catches: a channel
#                           outage credited to ignore.
#   ignore-receipt          bob's own recording shows the `Ignored player X`
#                           receipt. Catches: `/nc ignore` silently failing so
#                           the later silence proves nothing.
#   ignore-suppressed       bob's plan-armed `expectAbsent` for the suppressed
#                           token PASSED. Catches: a renderer that stopped
#                           calling isIgnored (one forgotten call re-exposes
#                           every ignored player, and every existing delivery
#                           assertion still passes).
#   ignore-suppressed-control-delivered
#                           the THIRD, direct-connect bot DID receive the same
#                           suppressed token. This is the half that makes
#                           `ignore-suppressed` non-vacuous: the line was
#                           carried and rendered somewhere, only the ignorer's
#                           renderer dropped it. Catches: a sender whose line
#                           never left (silence everywhere would otherwise look
#                           like a working ignore).
#   ignore-list-shows-name  `/nc ignore list` renders the ignored name.
#                           Catches: the list handler diverging from the filter,
#                           and a receipt that adds a name the list never shows.
#   unignore-restores       after `/nc unignore` bob receives a fresh token.
#                           Catches: an unignore that removes the receipt but
#                           not the filter entry (one-way ignore).
#
# /nc who (gating):
#   who-<id>-exact-set      the member body is EXACTLY the expected set. An
#                           absence-only check would pass on an empty body.
#   who-<id>-count-matches  the header's `(N)` equals the number of names in the
#                           body. `handleWho` derives both from one TreeSet, so
#                           a divergence means the count came from elsewhere.
#   who-<id>-includes       the just-joined player is in the body.
#   who-<id>-excludes       the player who left is NOT in the body.
#
# /nc list (gating) -- THE REGRESSION TEST for the phantom-membership bug:
#   list-hides-refused-join a refused `/nc join <absent channel>` must not leave
#                           that channel in `/nc list`. The bug's shape:
#                           ChannelCommandService.join:198 sets the active
#                           channel optimistically and PlayerChannelState
#                           .setActiveChannel:73-79 also adds it to
#                           `joinedChannels`; the refusal path only RESTORED the
#                           previous active channel, so the attempted one stayed
#                           in the joined set, and
#                           ListCommandService.formatChannelList:57-65 appends
#                           every joined-but-unknown channel as `joined`.
#                           PlayerChannelState.rollbackJoin:128-144 is the fix
#                           (it REMOVES the attempted channel). Any regression in
#                           any of those three links puts the refused id back
#                           into the list block, which is what this row reads.
#
# DELIBERATELY NOT DUPLICATED:
#   * "a kicked player is gone from /nc who" -- Assert-ModerationScenarios
#     already asserts exactly that (`kick-removed-membership`, run-joint.ps1
#     line ~3454-3470, scraping the same header+body shape). The row emitted
#     here is a `diagnostic` note recording the skip, never a second verdict.
#   * the ignore-list formatting as a standalone area-10 item: the coverage plan
#     (area 7, "Unobservable today") suggests folding it into area 10, but area
#     10's owner covers the CHANNEL list, not the ignore list. It stays here
#     where the matching filter assertion lives.
#
# ============================================================================
# WIRING CONTRACT (the caller must supply these bot steps)
# ============================================================================
# Tokens must be distinct and none may be a substring of another; this function
# checks that and refuses to assert otherwise.
#
# ignorer (bob / java), in this order:
#   { id = 'social-expect-control'; action = 'expect'; token = $controlBeforeToken }
#   { id = 'social-ignore';         action = 'cmd';    text = '/nc ignore <sender>' }
#   { id = 'social-ignore-receipt'; action = 'expect'; token = 'Ignored player' }
#   { id = 'social-absent-suppressed'; action = 'expectAbsent'; token = $suppressedToken }
#         ^ BEFORE the sender's suppressed line; the verdict is computed at exit
#   { id = 'social-ignore-list';    action = 'cmd';    text = '/nc ignore list' }
#   { id = 'social-unignore';       action = 'cmd';    text = '/nc unignore <sender>' }
#   { id = 'social-unignore-receipt'; action = 'expect'; token = 'Unignored player' }
#   { id = 'social-expect-restored';  action = 'expect'; token = $restoredToken }
#
# control observer (downstream / mod):
#   { id = 'social-control-sees-suppressed'; action = 'expect'; token = $suppressedToken }
#
# sender (alice / bedrock):
#   { id = 'social-send-control';    action = 'chat'; text = "<prefix> $controlBeforeToken" }
#   { id = 'social-wait-ignored';    action = 'handoffGet'; key = 'socialignored' }
#   { id = 'social-send-suppressed'; action = 'chat'; text = "<prefix> $suppressedToken" }
#   { id = 'social-wait-unignored';  action = 'handoffGet'; key = 'socialunignored' }
#   { id = 'social-send-restored';   action = 'chat'; text = "<prefix> $restoredToken" }
#
# The ignorer publishes the two handoffs with `handoffSet` right after each
# receipt, so the sender never races the arm:
#   { id = 'social-mark-ignored';   action = 'handoffSet'; key = 'socialignored';   pattern = '<the ignore receipt text>' }
#   { id = 'social-mark-unignored'; action = 'handoffSet'; key = 'socialunignored'; pattern = '<the unignore receipt text>' }
#
# who/list (the requester is any proxy-connected bot; `$WhoRes` / `$ListRes`):
#   a `/nc join <absent channel>` attempt, then a `/nc list`.
#   Each $WhoChecks entry is @{ Id; Channel; DisplayName; Occurrence; Expected; Includes; Excludes }.
#   `Occurrence` is 1-based and picks the Nth `online members` block for that
#   channel, so a join-then-leave sequence can be checked with two entries.
#
# TOPOLOGY NOTE for the expected `who` set: lite's LocalAuthority.who:516-542
# intersects the channel's member set with `platform.getOnlinePlayers()`, i.e.
# the PROXY roster. A direct-connect member is silently omitted, so the lite
# expected set is the proxy-connected members and nothing else. Relay's backend
# `handleWho` answers from its own roster. The caller supplies the set; this
# function never guesses it.
#
# CALLING IT (run-joint.ps1, after Wait-Bots and after the recordings are read;
# the file is dot-sourced once near the other helpers):
#
#   $socialChecks = @(
#     @{ Id = 'after-join';  Channel = 'global'; DisplayName = 'Global'; Occurrence = 1
#        Expected = @('E2ELiteAlice','E2ELiteBob')
#        Includes = 'E2ELiteBob'; Excludes = 'E2ELiteDownstream' }
#   )
#   $assertions += Invoke-SocialScenarios -RoundIndex $round.Index -Topology 'lite' `
#     -IgnorerName 'bob' -SenderName 'E2ELiteAlice' `
#     -ControlBeforeToken $socialControlToken -SuppressedToken $socialSuppressedToken `
#     -RestoredToken $socialRestoredToken `
#     -IgnorerRes $bobRes -ControlRes $downRes `
#     -WhoChecks $socialChecks -WhoRes $bobRes -ListRes $bobRes `
#     -RefusedChannel 'nosuchchannel' -ListKnownChannel 'global'
#
# `$WhoRes` / `$ListRes` are the recordings of the bot that RAN those commands;
# this function never issues one. A parameter left empty makes the affected
# group report `diagnostic` / `NOT EXECUTED` -- it never invents a pass.
function Invoke-SocialScenarios {
  param(
    [int]$RoundIndex,
    [string]$Topology = 'lite',

    # ignore half
    [string]$IgnorerName = '',
    [string]$SenderName = '',
    [string]$IgnoredListName = '',
    [string]$ControlBeforeToken = '',
    [string]$SuppressedToken = '',
    [string]$RestoredToken = '',
    $IgnorerRes,
    $ControlRes,

    # who / list half
    [object[]]$WhoChecks = @(),
    $WhoRes,
    $ListRes,
    [string]$RefusedChannel = 'nosuchchannel',
    [string]$RefusalCode = 'NC-404',
    [string]$ListKnownChannel = 'global'
  )

  $prefix = "round$RoundIndex/social"
  $out = @()

  # ---------------------------------------------------------------- helpers
  # Every helper reads `received[]` and keeps ONLY `kind -eq 'messagestr'`.
  # A system line is recorded twice on Velocity (once as messagestr, once as a
  # `chat` row whose sender is the first word), so scanning all rows would
  # double-count and, worse, would parse `<...>`-shaped text as a channel id.

  $textLines = {
    param($res)
    $lines = @()
    if ($res -and $res.received) {
      foreach ($e in $res.received) {
        if ([string]$e.kind -ne 'messagestr') { continue }
        $lines += [string]$e.text
      }
    }
    return $lines
  }

  $indexOfFirst = {
    param([string[]]$lines, [int]$from, [string]$pattern)
    if (-not $lines -or $lines.Count -eq 0) { return -1 }
    for ($i = [Math]::Max(0, $from); $i -lt $lines.Count; $i++) {
      if ($lines[$i] -match $pattern) { return $i }
    }
    return -1
  }

  $indexOfLast = {
    param([string[]]$lines, [string]$pattern)
    if (-not $lines -or $lines.Count -eq 0) { return -1 }
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
      if ($lines[$i] -match $pattern) { return $i }
    }
    return -1
  }

  # A bot verdict, reported under the scenario id and with the recording it came
  # from. Same contract as Assert-ModerationScenarios' `$verdict`: a missing
  # verdict is a failure, never a silent pass, because an assertion whose
  # recorder never produced a verdict proves nothing.
  $verdict = {
    param($res, [string]$role, [string]$botId, [string]$assertionId, [string]$token, [string]$kind)
    $hit = $null
    if ($res) {
      # expectAbsent verdicts live in `absentChecks`, every other bot verdict in
      # `assertions` (joint-bot.js writeResults). Searching only one of the two
      # reports "recorded no verdict" for a verdict that exists.
      foreach ($bucket in @($res.assertions, $res.absentChecks)) {
        if (-not $bucket) { continue }
        foreach ($a in $bucket) { if ([string]$a.id -eq $botId) { $hit = $a; break } }
        if ($hit) { break }
      }
    }
    return [PSCustomObject]@{
      id = "$prefix/$assertionId"
      token = $token
      pass = [bool]($hit -and $hit.pass)
      evidence = if ($hit) { "[$role] " + [string]$hit.evidence }
                 else { "$role recorded no '$botId' verdict" }
      kind = $kind
    }
  }

  if (-not $IgnoredListName) { $IgnoredListName = $SenderName }

  # ------------------------------------------------------- not wired at all?
  # An un-wired call must say so, once, per group -- never fabricate a pass.
  $ignoreWired = [bool]($IgnorerRes -and $ControlRes -and $ControlBeforeToken -and
                         $SuppressedToken -and $RestoredToken)
  $whoWired = [bool]($WhoRes -and $WhoChecks -and @($WhoChecks).Count -gt 0)
  $listWired = [bool]($ListRes -and $RefusedChannel)

  # Token ambiguity would make a negative pass for the wrong reason: `expect` /
  # `expectAbsent` match by SUBSTRING (joint-bot.js noteInbound uses
  # String.includes), so a suppressed token that is a prefix of the restored one
  # could be satisfied by the restored line. The check is one-directional on
  # purpose: a COMMON prefix shared by all three tokens is harmless (it is in
  # every line), while a token contained in a DIFFERENT token is not.
  $tokenProblem = ''
  $tokens = @(@($ControlBeforeToken, $SuppressedToken, $RestoredToken) | Where-Object { $_ })
  for ($i = 0; $i -lt $tokens.Count; $i++) {
    for ($j = 0; $j -lt $tokens.Count; $j++) {
      if ($i -eq $j) { continue }
      # [bool], not [string]: a non-empty string is truthy for -not, so a
      # string cast would make this branch unreachable.
      if (-not [bool]$tokens[$j].Contains([string]$tokens[$i])) { continue }
      # Shared by every token -> no ambiguity, they all contain it by design.
      $shared = $true
      foreach ($t in $tokens) {
        if (-not [bool]$t.Contains([string]$tokens[$i])) { $shared = $false; break }
      }
      if ($shared) { continue }
      $tokenProblem = "token '$($tokens[$i])' is contained in token '$($tokens[$j])'; expect/expectAbsent match by substring, so one line could satisfy the other's assertion"
      break
    }
    if ($tokenProblem) { break }
  }
  if ($tokenProblem) { $ignoreWired = $false }

  # =========================================================== ignore group
  if (-not $ignoreWired) {
    $reason = if ($tokenProblem) { "NOT EXECUTED: $tokenProblem" }
      elseif (-not $IgnorerRes) { 'NOT EXECUTED: not wired - no ignorer recording supplied' }
      elseif (-not $ControlRes) { 'NOT EXECUTED: not wired - no control-observer recording, so a silence cannot be distinguished from a broken chat path' }
      else { 'NOT EXECUTED: not wired - the token set is incomplete (control / suppressed / restored)' }
    foreach ($id in @('ignore-control-before', 'ignore-receipt', 'ignore-suppressed',
                      'ignore-suppressed-control-delivered', 'ignore-list-shows-name',
                      'unignore-restores')) {
      $out += [PSCustomObject]@{
        id = "$prefix/$id"; token = 'ignore'; pass = $false
        evidence = $reason; kind = 'diagnostic'
      }
    }
  } else {
    # 1. Positive control: BEFORE the ignore, the ignorer received the sender's
    #    line. Everything below depends on this having been true.
    $out += & $verdict $IgnorerRes $IgnorerName 'social-expect-control' `
      'ignore-control-before' $ControlBeforeToken 'social-ignore'

    # 2. The `/nc ignore` receipt in the ignorer's own recording: the command
    #    really ran and really applied. Without it, "nothing arrived" is also
    #    what a failed command looks like.
    $ignoreReceipt = & $verdict $IgnorerRes $IgnorerName 'social-ignore-receipt' `
      'ignore-receipt' 'Ignored player' 'social-ignore'
    if (-not $ignoreReceipt.pass) {
      $ignoreReceipt.evidence = ('[ignorer] ' + $ignoreReceipt.evidence +
        ' -- no `Ignored player <name>` receipt, so a silence after this point cannot be attributed to ignore')
    }
    $out += $ignoreReceipt

    # 3. The third bot, on a DIFFERENT renderer, received the suppressed token.
    #    This is the half that makes row 4 non-vacuous.
    $controlSaw = & $verdict $ControlRes 'control-observer' 'social-control-sees-suppressed' `
      'ignore-suppressed-control-delivered' $SuppressedToken 'social-ignore'
    $out += $controlSaw

    # 4. The ignorer did NOT receive it. Gated on the control: if the line was
    #    never carried anywhere, the absence is not evidence about ignore, so
    #    the row fails and its evidence names the broken premise.
    $suppressed = & $verdict $IgnorerRes $IgnorerName 'social-absent-suppressed' `
      'ignore-suppressed' $SuppressedToken 'social-ignore'
    if (-not $suppressed.pass -and $suppressed.evidence -match 'recorded no') {
      $suppressed.evidence = ('[ignorer] ' + $suppressed.evidence +
        " -- the ignorer's plan never armed expectAbsent for the suppressed token, so the negative is unproven")
    } elseif (-not $suppressed.pass) {
      $suppressed.evidence = ('[ignorer] RECEIVED the suppressed line: ' + $suppressed.evidence)
    }
    if (-not $controlSaw.pass) {
      $suppressed.pass = $false
      $suppressed.evidence = ('[ignorer] premise failed: the control observer did not receive the suppressed token either, so the line was never carried -- this is not evidence about ignore. ' + $suppressed.evidence)
    }
    $out += $suppressed

    # 5. `/nc ignore list` names the ignored player. Scoped to the item lines
    #    after the header, so the separate `Ignored player X` receipt (row 2)
    #    cannot satisfy it: that would make the assertion vacuous.
    $lines = @(& $textLines $IgnorerRes)
    $headerIdx = & $indexOfLast $lines 'Ignored players|\u5df2\u5c4f\u853d\u73a9\u5bb6'
    $listHit = ''
    if ($headerIdx -ge 0) {
      $limit = [Math]::Min($headerIdx + 6, $lines.Count - 1)
      for ($i = $headerIdx + 1; $i -le $limit; $i++) {
        if ($lines[$i] -match '^\s*-\s*' -and $lines[$i].Contains($IgnoredListName)) {
          $listHit = $lines[$i]; break
        }
      }
    }
    $out += [PSCustomObject]@{
      id = "$prefix/ignore-list-shows-name"
      token = $IgnoredListName
      pass = [bool]$listHit
      evidence = if ($listHit) { "[$IgnorerName] $($lines[$headerIdx]) | $listHit" }
                 elseif ($headerIdx -ge 0) { "[$IgnorerName] ignore-list header rendered but no '- $IgnoredListName' item line follows it: $($lines[$headerIdx])" }
                 else { "[$IgnorerName] recorded no ignore-list header ('Ignored players'); /nc ignore list produced nothing" }
      kind = 'social-ignore'
    }

    # 6. `/nc unignore` restores delivery.
    $out += & $verdict $IgnorerRes $IgnorerName 'social-expect-restored' `
      'unignore-restores' $RestoredToken 'social-ignore'
  }

  # ============================================================== who group
  if (-not $whoWired) {
    $reason = if (-not $WhoRes) { 'NOT EXECUTED: not wired - no /nc who recording supplied' }
              else { 'NOT EXECUTED: not wired - no $WhoChecks supplied, so there is no expected member set' }
    foreach ($id in @('who-exact-set', 'who-count-matches', 'who-after-kick')) {
      $out += [PSCustomObject]@{
        id = "$prefix/$id"; token = 'who'; pass = $false
        evidence = $reason; kind = 'diagnostic'
      }
    }
  } else {
    $lines = @(& $textLines $WhoRes)

    # Collect every `online members` block, keyed by the channel the caller
    # asked about. The header carries the DISPLAY name ("Channel Global online
    # members (2):"), so the match accepts either the id or the display name.
    #
    # The header must carry the COUNT. That is what separates the answer from
    # the `Fetching online members of channel X...` interim prompt, which also
    # contains the phrase and would otherwise be paired with the answer's own
    # header as its "body".
    $blocks = @()
    for ($i = 0; $i -lt $lines.Count; $i++) {
      if ($lines[$i] -notmatch 'online members|\u5728\u7ebf\u6210\u5458') { continue }
      if ($lines[$i] -notmatch '\(\d+\)|\uff08\d+\uff09') { continue }
      $body = if ($i + 1 -lt $lines.Count) { $lines[$i + 1] } else { '' }
      $blocks += [PSCustomObject]@{ Index = $i; Header = $lines[$i]; Body = $body }
    }

    foreach ($check in @($WhoChecks)) {
      $checkId = [string]$check.Id
      $channel = [string]$check.Channel
      if (-not $checkId) { $checkId = [string]$channel }
      $displayName = [string]$check.DisplayName
      if (-not $displayName) { $displayName = $channel }
      $occurrence = 1
      if ($check.Occurrence) { $occurrence = [int]$check.Occurrence }

      # Only blocks whose header names this channel, in arrival order.
      $mine = @()
      foreach ($b in $blocks) {
        if ($b.Header.Contains($channel) -or $b.Header.Contains($displayName)) { $mine += $b }
      }
      $block = $null
      if ($occurrence -ge 1 -and $occurrence -le $mine.Count) { $block = $mine[$occurrence - 1] }

      if (-not $block) {
        $why = "/nc who $channel occurrence $occurrence was never answered: the recording has $($mine.Count) 'online members' block(s) for it"
        foreach ($suffix in @('exact-set', 'count-matches', 'includes', 'excludes')) {
          $out += [PSCustomObject]@{
            id = "$prefix/who-$checkId-$suffix"; token = $channel; pass = $false
            evidence = "[$($WhoRes.role)] $why"; kind = 'social-who'
          }
        }
        continue
      }

      # Body -> names. `formatMemberList` substitutes chat.who.list_empty for a
      # zero-member answer, so that line means "no names", not one name.
      $bodyNames = @()
      if ($block.Body -and $block.Body -notmatch 'No members online|\u6682\u65e0\u5728\u7ebf\u6210\u5458') {
        $bodyNames = @($block.Body -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
      }
      $expected = @(@($check.Expected) | Where-Object { $_ } | ForEach-Object { [string]$_ })

      # 1. EXACT set. An absence-only check passes on an empty body; this does
      #    not.
      $expectedSorted = @($expected | Sort-Object)
      $actualSorted = @($bodyNames | Sort-Object)
      $sameSet = (($expectedSorted -join '|') -eq ($actualSorted -join '|'))
      $out += [PSCustomObject]@{
        id = "$prefix/who-$checkId-exact-set"; token = $channel
        pass = $sameSet
        evidence = if ($sameSet) { "[$($WhoRes.role)] $($block.Header) | $($block.Body)" }
                   else { "[$($WhoRes.role)] expected [$($expectedSorted -join ', ')] but the body was [$($actualSorted -join ', ')] (header: $($block.Header))" }
        kind = 'social-who'
      }

      # 2. The header's count is the same number as the body's names.
      #    handleWho builds both from one TreeSet, so a mismatch means the count
      #    came from a different source than the body.
      $declared = -1
      if ($block.Header -match '\((\d+)\)') { $declared = [int]$Matches[1] }
      $out += [PSCustomObject]@{
        id = "$prefix/who-$checkId-count-matches"; token = $channel
        pass = [bool]($declared -ge 0 -and $declared -eq $bodyNames.Count)
        evidence = if ($declared -lt 0) { "[$($WhoRes.role)] header carries no member count: $($block.Header)" }
                   else { "[$($WhoRes.role)] header says $declared, body lists $($bodyNames.Count) name(s): $($block.Body)" }
        kind = 'social-who'
      }

      # 3/4. The two halves the scenario exists for: a just-joined player is
      #      listed, a player who left is not.
      if ($check.Includes) {
        $want = [string]$check.Includes
        $hit = $bodyNames -contains $want
        $out += [PSCustomObject]@{
          id = "$prefix/who-$checkId-includes"; token = $want
          pass = [bool]$hit
          evidence = if ($hit) { "[$($WhoRes.role)] $($block.Body)" }
                     else { "[$($WhoRes.role)] '$want' (joined) is missing from: $($block.Body)" }
          kind = 'social-who'
        }
      }
      if ($check.Excludes) {
        $gone = [string]$check.Excludes
        $hit = $bodyNames -contains $gone
        $out += [PSCustomObject]@{
          id = "$prefix/who-$checkId-excludes"; token = $gone
          pass = [bool](-not $hit)
          evidence = if (-not $hit) { "[$($WhoRes.role)] $($block.Body)" }
                     else { "[$($WhoRes.role)] '$gone' (left) is still listed in: $($block.Body)" }
          kind = 'social-who'
        }
      }
    }

    # Skipped ON PURPOSE: the moderation group already asserts it, scraping the
    # same header+body shape. A second verdict here would make one defect look
    # like two, and the coverage plan explicitly forbids the duplicate.
    $out += [PSCustomObject]@{
      id = "$prefix/who-after-kick"; token = 'skipped'
      pass = $false; kind = 'diagnostic'
      evidence = ('SKIPPED: Assert-ModerationScenarios already asserts `kick-removed-membership` by scraping this same /nc who header+body after the kick (run-joint.ps1, the `whoBody` block). Re-asserting it here would report one defect twice and would need a second kick, which the moderation drive already owns.')
    }
  }

  # ============================================== /nc list regression test
  # THE REGRESSION TEST. Spelled out:
  #   1. the bot attempted `/nc join <RefusedChannel>` and was refused
  #      (`Joining channel <id>` receipt, then `[NC-404]`), and
  #   2. it then ran `/nc list`, and
  #   3. NO line inside that list block mentions the refused id.
  # The bug's shape is a JOINED marker on a channel the authority refused
  # (PlayerChannelState.setActiveChannel adds it to joinedChannels; the refusal
  # only restored the active channel; ListCommandService appends every joined-
  # but-unknown channel). Any of those three links regressing puts the id back
  # inside the block, so "no line in the block contains it" is the exact test.
  #
  # Non-vacuity, both halves required:
  #   * the refusal happened (otherwise the list has nothing to hide), and
  #   * the block was actually rendered (title + at least one channel line),
  #     otherwise "no refused id" is true of an empty recording.
  if (-not $listWired) {
    $out += [PSCustomObject]@{
      id = "$prefix/list-hides-refused-join"; token = $RefusedChannel; pass = $false
      evidence = 'NOT EXECUTED: not wired - no recording that ran the refused join and then /nc list'
      kind = 'diagnostic'
    }
  } else {
    $lines = @(& $textLines $ListRes)
    $attemptPattern = 'Joining channel ' + [regex]::Escape($RefusedChannel)
    $attemptIdx = & $indexOfLast $lines $attemptPattern
    $refusalIdx = -1
    if ($attemptIdx -ge 0) { $refusalIdx = & $indexOfFirst $lines ($attemptIdx + 1) ([regex]::Escape($RefusalCode)) }

    # The list title is locale-dependent (en: "=== NovaChat Channels ===",
    # zh: "=== NovaChat \u9891\u9053\u5217\u8868 ==="), so it is matched by the product name
    # only. The tail is a run of '=' that carries no locale.
    $titleIdx = -1
    if ($refusalIdx -ge 0) { $titleIdx = & $indexOfFirst $lines ($refusalIdx + 1) 'NovaChat Channels|NovaChat \u9891\u9053\u5217\u8868' }

    $blockLines = @()
    $tailIdx = -1
    if ($titleIdx -ge 0) {
      $tailIdx = & $indexOfFirst $lines ($titleIdx + 1) '^=+$'
      if ($tailIdx -gt $titleIdx) {
        for ($i = $titleIdx + 1; $i -lt $tailIdx; $i++) { $blockLines += $lines[$i] }
      }
    }

    $controlListed = $false
    foreach ($l in $blockLines) { if ($l.Contains($ListKnownChannel)) { $controlListed = $true } }
    $phantom = ''
    foreach ($l in $blockLines) { if ($l.Contains($RefusedChannel)) { $phantom = $l; break } }

    $pass = [bool]($attemptIdx -ge 0 -and $refusalIdx -ge 0 -and $titleIdx -ge 0 -and
                   $tailIdx -gt $titleIdx -and $controlListed -and -not $phantom)

    $evidence = ''
    if ($attemptIdx -lt 0) {
      $evidence = "[$($ListRes.role)] the recording contains no `/nc join $RefusedChannel` attempt, so there is no refusal to check"
    } elseif ($refusalIdx -lt 0) {
      # A join that was NOT refused is a different (also interesting) failure:
      # it would make the phantom entry legitimate. Say which happened.
      $evidence = "[$($ListRes.role)] `/nc join $RefusedChannel` produced no $RefusalCode; the join may have succeeded, so 'list must not show it' does not apply"
    } elseif ($titleIdx -lt 0) {
      $evidence = "[$($ListRes.role)] NO EVIDENCE: the recording has no /nc list block after the $RefusalCode refusal. A list taken before the join could not show the phantom entry, so this is reported as a failure rather than a vacuous pass -- the bot plan is missing the /nc list step that follows the refused join."
    } elseif ($tailIdx -le $titleIdx) {
      $evidence = "[$($ListRes.role)] the /nc list block has no closing '===' line, so it cannot be delimited"
    } elseif (-not $controlListed) {
      $evidence = "[$($ListRes.role)] the /nc list block names no known channel ('$ListKnownChannel' missing), so the block was not really rendered"
    } elseif ($phantom) {
      $evidence = "[$($ListRes.role)] REGRESSION: the refused channel is listed as joined: '$phantom' (block: $($blockLines -join ' | '))"
    } else {
      $evidence = "[$($ListRes.role)] refused with $RefusalCode, then list block: $($blockLines -join ' | ')"
    }

    $out += [PSCustomObject]@{
      id = "$prefix/list-hides-refused-join"
      token = $RefusedChannel
      pass = $pass
      evidence = $evidence
      kind = 'social-list'
    }
  }

  # =================================== the shape of ignore, recorded once
  # Gating is deliberately NOT claimed here. It records the product decision
  # that made this group's topology requirement necessary; the assertions
  # above are the behavioural half.
  # Which process actually rendered the ignorer's lines on THIS topology. The
  # assertion set above is only sound when that process is the one that ran
  # `/nc ignore`; naming it makes a future re-wiring (e.g. moving the ignorer to
  # a direct-connect leg) fail loudly here instead of silently turning the
  # negatives into statements about nothing.
  $renderer = switch ($Topology) {
    'lite'  { 'the Velocity proxy (ProxyChatCore.handleIncomingMessage:301-304); the lite authority is in-process, so one ignore list covers every proxy player' }
    'relay' { 'the Velocity proxy for a PROXY-CONNECTED ignorer (ProxyChatCore.handleIncomingMessage:301-304); a direct-connect ignorer would be rendered by its sub-server instead (bukkit ChatInterceptor:305-309) and its /nc ignore would write a list the proxy never reads' }
    default { "UNKNOWN topology '$Topology' -- this group's soundness is unverified for it" }
  }
  $out += [PSCustomObject]@{
    id = "$prefix/ignore-is-client-side"
    token = 'renderer-local filter'
    pass = $false
    kind = 'diagnostic'
    evidence = ('Ignore is enforced per RENDERER against a process-local list; the backend has no ignore check on the chat fan-out (MessagePipeline contains no ignore reference). Filter call sites: ProxyChatCore.handleIncomingMessage:301-304, bukkit ChatInterceptor:305-309, nukkit ChatInterceptor:174, pnx ChatInterceptor.sendToPlayersFiltered:283-291, sponge ChatListener:183, MOD ChatInterceptor:178. `/nc ignore` (ProxyCommandCore.handleIgnore:475) writes only to the invoking process. On this topology (' +
      $Topology + ') the lines the ignorer receives are rendered by ' + $renderer +
      '. Asserting the cross-renderer case would fail for a topology reason, not a defect -- reported, not asserted.')
  }

  return $out
}
