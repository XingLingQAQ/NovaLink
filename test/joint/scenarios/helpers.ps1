# --- shared helpers for test/joint/scenarios/*.ps1 ---------------------------
#
# Dot-sourced by the scenario files (effects.ps1 dot-sources it itself when it
# has not been loaded yet). Defines FUNCTIONS ONLY: no global state, no side
# effects, no param() block, PS 5.1 syntax. Safe to dot-source twice.
#
# WHY THESE EXIST
# ---------------
# Every scenario file needs the same four things, and each was re-implementing
# them slightly differently:
#
#   * "give me this bot's rows of kind K that contain token T, optionally
#     inside a time window" -- the recording is the evidence and the ONLY
#     evidence (see COVERAGE-PLAN.md section 0.1);
#   * "give me this bot's verdict for step id X" -- verdicts live in TWO
#     buckets (assertions[] and absentChecks[]), and searching one bucket
#     reports "recorded no verdict" for a verdict that exists;
#   * "is this recording alive at all" -- an absence assertion against a null
#     or crashed recording passes vacuously, which is the failure mode the
#     coverage plan calls out repeatedly;
#   * "build an assertion object in the harness shape" -- every producer must
#     return the same { id; token; pass; evidence; kind } object.
#
# CONVENTIONS THIS FILE ENCODES
# -----------------------------
#   * Only `text` is ever searched, never `raw`. For effect rows `raw` is the
#     JSON payload (joint-bot.js record()), so a structured field could satisfy
#     a check the player never actually saw.
#   * Text matching is ORDINAL and case-sensitive by default. Callers that need
#     case folding pass -IgnoreCase explicitly, so an accidental "global"
#     vs "Global" match is a decision and not a default.
#   * A row without a timestamp is EXCLUDED from a windowed query rather than
#     silently treated as "inside". A row that cannot be placed in time must
#     not satisfy a window.
#   * Timestamps come from `atMs` (epoch ms, written by the bot) and fall back
#     to parsing `t` (ISO-8601 UTC). Nothing here compares times across bots:
#     a window is always anchored on a row from the SAME recording.

# Resolve a row's timestamp in epoch milliseconds, or $null when it has none.
function Get-JointBotRowAtMs {
  param($Row)
  if ($null -eq $Row) { return $null }

  $at = $null
  try { $at = $Row.atMs } catch { $at = $null }
  if ($null -ne $at -and "$at" -ne '') {
    try { return [double]$at } catch { }
  }

  $t = $null
  try { $t = [string]$Row.t } catch { $t = '' }
  if ([string]::IsNullOrEmpty($t)) { return $null }
  try {
    $dto = [System.DateTimeOffset]::Parse($t, [System.Globalization.CultureInfo]::InvariantCulture)
    return [double]$dto.ToUnixTimeMilliseconds()
  } catch {
    return $null
  }
}

# Rows from a bot's recording, filtered by kind / text / time window.
#
#   -BotResult    parsed results.json (Read-BotResult). $null yields nothing.
#   -Kind         exact effect/record kind ('title', 'actionBar', 'messagestr',
#                 'sound', 'playerJoined', ...). '' matches every kind.
#   -TextContains ordinal substring of the row's `text`. '' matches everything.
#   -IgnoreCase   case-fold -TextContains (off by default, see header).
#   -SinceMs / -UntilMs  inclusive epoch-ms bounds. A row with no timestamp is
#                 dropped when either bound is finite.
function Get-JointBotRows {
  param(
    $BotResult,
    [string]$Kind = '',
    [string]$TextContains = '',
    [switch]$IgnoreCase,
    [double]$SinceMs = [double]::NegativeInfinity,
    [double]$UntilMs = [double]::PositiveInfinity
  )

  if ($null -eq $BotResult) { return @() }
  $rows = $null
  try { $rows = $BotResult.received } catch { $rows = $null }
  if ($null -eq $rows) { return @() }

  $windowing = ($SinceMs -ne [double]::NegativeInfinity) -or ($UntilMs -ne [double]::PositiveInfinity)
  $comparison = if ($IgnoreCase) {
    [System.StringComparison]::OrdinalIgnoreCase
  } else {
    [System.StringComparison]::Ordinal
  }

  $out = New-Object System.Collections.Generic.List[object]
  foreach ($r in @($rows)) {
    if ($null -eq $r) { continue }
    if ($Kind -ne '' -and [string]$r.kind -ne $Kind) { continue }

    $txt = ''
    try { $txt = [string]$r.text } catch { $txt = '' }
    if ($TextContains -ne '' -and $txt.IndexOf($TextContains, $comparison) -lt 0) { continue }

    if ($windowing) {
      $at = Get-JointBotRowAtMs $r
      if ($null -eq $at) { continue }
      if ($at -lt $SinceMs -or $at -gt $UntilMs) { continue }
    }
    $out.Add($r)
  }
  return $out.ToArray()
}

# The first matching row, or $null. Thin wrapper so callers can ask for "the
# line that anchors this window" without juggling an array.
function Find-JointBotTextRow {
  param(
    $BotResult,
    [string]$Text,
    [string]$Kind = '',
    [switch]$IgnoreCase,
    [double]$SinceMs = [double]::NegativeInfinity,
    [double]$UntilMs = [double]::PositiveInfinity
  )
  $rows = @(Get-JointBotRows -BotResult $BotResult -Kind $Kind -TextContains $Text `
    -IgnoreCase:$IgnoreCase -SinceMs $SinceMs -UntilMs $UntilMs)
  if ($rows.Count -eq 0) { return $null }
  return $rows[0]
}

# Rows of a kind inside an inclusive window centred on one row of THIS
# recording. The anchor is the trigger (the chat line that carried the token,
# the join acknowledgement), never a wall clock shared with another process.
function Get-JointBotRowsNear {
  param(
    $BotResult,
    [double]$CenterMs,
    [string]$Kind = '',
    [double]$BeforeMs = 2000,
    [double]$AfterMs = 8000
  )
  return @(Get-JointBotRows -BotResult $BotResult -Kind $Kind `
    -SinceMs ($CenterMs - $BeforeMs) -UntilMs ($CenterMs + $AfterMs))
}

# A bot verdict by step id, searched in BOTH buckets the bot writes:
#   assertions[]    -- expect / expectEvent / expectKicked / expectState
#   absentChecks[]  -- expectAbsent, computed at bot exit
# Returns $null when the bot never produced that verdict, which callers must
# treat as "proves nothing" rather than "passed".
function Find-JointBotVerdict {
  param($BotResult, [string]$Id)
  if ($null -eq $BotResult -or [string]::IsNullOrEmpty($Id)) { return $null }

  foreach ($bucketName in @('assertions', 'absentChecks')) {
    $bucket = $null
    try { $bucket = $BotResult.$bucketName } catch { $bucket = $null }
    if ($null -eq $bucket) { continue }
    foreach ($a in @($bucket)) {
      if ($null -eq $a) { continue }
      if ([string]$a.id -eq $Id) { return $a }
    }
  }
  return $null
}

# Whether a recording carries any evidence at all: at least one inbound row
# that is not the bot's own lifecycle noise. An absence assertion computed
# against a dead recording is vacuous, so every negative row in the scenario
# files checks this first.
function Test-JointBotRecording {
  param($BotResult)
  if ($null -eq $BotResult) { return $false }
  $rows = $null
  try { $rows = $BotResult.received } catch { $rows = $null }
  if ($null -eq $rows) { return $false }
  foreach ($r in @($rows)) {
    if ($null -eq $r) { continue }
    $k = [string]$r.kind
    if ($k -eq 'spawn' -or $k -eq 'end' -or $k -eq 'error' -or $k -eq 'close') { continue }
    return $true
  }
  return $false
}

# Build the assertion object every scenario producer returns. `kind` is the
# GATING switch: the harness fails a round on `-not pass -and kind -ne
# 'diagnostic'` (run-joint.ps1:4176, :4570), so any row that reports without
# gating must say so here.
function New-JointAssertion {
  param(
    [string]$Id,
    [string]$Token,
    [bool]$Pass,
    [string]$Evidence,
    [string]$Kind = 'effect'
  )
  return [PSCustomObject]@{
    id       = $Id
    token    = $Token
    pass     = [bool]$Pass
    evidence = $Evidence
    kind     = $Kind
  }
}

# Short human-readable rendering of one effect/chat row for an evidence string,
# with its offset from an anchor when both have timestamps. The offset is what
# lets a reader tell "arrived 40 ms after the line" from "arrived four minutes
# later and happened to match".
function Format-JointBotRow {
  param($Row, [double]$AnchorMs = [double]::NegativeInfinity)
  if ($null -eq $Row) { return '' }
  $txt = ''
  try { $txt = [string]$Row.text } catch { $txt = '' }
  $kind = ''
  try { $kind = [string]$Row.kind } catch { $kind = '' }
  # Effect rows already carry their own label in `text` ('title: X',
  # 'subtitle: X', 'fadeIn=..'), so prefixing the kind again would render
  # 'title: title: X'. Prefix only when the text does not already say it.
  $needsPrefix = ($kind -ne '') -and (-not $txt.StartsWith("${kind}: ")) -and ($kind -ne 'messagestr')
  $label = if ($needsPrefix) { "${kind}: $txt" } else { $txt }
  if ($AnchorMs -ne [double]::NegativeInfinity) {
    $at = Get-JointBotRowAtMs $Row
    if ($null -ne $at) { $label = "$label (+$([int]($at - $AnchorMs))ms)" }
  }
  return $label
}
