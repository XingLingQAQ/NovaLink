function Resolve-JointScenarioGroups {
  param([AllowNull()][AllowEmptyString()][string]$Value = 'core')

  $allowed = @('core', 'business', 'lite-moderation', 'recovery', 'proxy-switch', 'channel-lifecycle', 'social-contract', 'custom-effects')
  $groups = New-Object System.Collections.Generic.List[string]
  $groups.Add('core')
  if ([string]::IsNullOrWhiteSpace($Value)) { return $groups.ToArray() }
  foreach ($part in $Value.Split(',')) {
    $group = $part.Trim().ToLowerInvariant()
    if ($group -notin $allowed) {
      throw "Unknown joint scenario group '$part'; allowed groups: $($allowed -join ', ')"
    }
    if (-not $groups.Contains($group)) { $groups.Add($group) }
  }
  return $groups.ToArray()
}

function Get-JointScenarioAssertionIds {
  param(
    [string[]]$ScenarioGroups = @('core'),
    [ValidateRange(1, 2147483647)][int]$RoundIndex,
    [Parameter(Mandatory = $true)][ValidateSet('relay', 'lite')][string]$Mode,
    [Parameter(Mandatory = $true)][ValidateSet('velocity', 'bungee')][string]$Proxy
  )

  $groups = @(Resolve-JointScenarioGroups -Value ($ScenarioGroups -join ','))
  if ('lite-moderation' -in $groups -and $Mode -ne 'lite') {
    throw "Scenario group 'lite-moderation' requires lite mode; requested $Mode"
  }
  if ('recovery' -in $groups -and $Mode -ne 'relay') {
    throw "Scenario group 'recovery' requires relay mode and a Bedrock leg; requested $Mode"
  }
  if ('proxy-switch' -in $groups -and $Mode -ne 'relay') {
    throw "Scenario group 'proxy-switch' requires relay mode, Bukkit primary and bukkit2 secondary; requested $Mode"
  }
  if ('channel-lifecycle' -in $groups -and $Mode -ne 'relay') {
    throw "Scenario group 'channel-lifecycle' requires relay mode; requested $Mode"
  }
  if ('custom-effects' -in $groups -and $Mode -ne 'relay') {
    throw "Scenario group 'custom-effects' requires relay mode; requested $Mode"
  }

  $prefix = "round$RoundIndex"
  $ids = @(
    "$prefix/iso/phase1-alice-line-accepted", "$prefix/iso/phase1-isolated-from-bob",
    "$prefix/iso/phase2-bob-line-accepted", "$prefix/iso/phase2-isolated-from-alice",
    "$prefix/iso/phase3-shared-channel-delivered", "$prefix/iso/join-b-ack-observed",
    "$prefix/iso/join-a-ack-after-phase2",
    "$prefix/business/alice-completed", "$prefix/business/bob-completed",
    "$prefix/business/reconnect-spawned", "$prefix/business/reconnect-membership",
    "$prefix/business/reconnect-outbound", "$prefix/business/reconnect-inbound"
  )
  if ($Mode -eq 'relay') {
    $ids += @(
      "$prefix/proxy-plugin-loaded", "$prefix/relay-links-created", "$prefix/relay-distinct-backend-identities"
    )
  } else {
    $ids += @("$prefix/lite-mode-active", "$prefix/downstream-upstream-roundtrip", "$prefix/proxy-to-downstream-chat")
    if ($Proxy -eq 'velocity') { $ids += 'bob/expect-mention-sound' }
  }
  foreach ($group in $groups) {
    switch ($group) {
      'business' {
        . (Join-Path $PSScriptRoot 'extended-business.ps1')
        $ids += @(Get-ExtendedBusinessRequiredAssertionIds -RoundIndex $RoundIndex)
      }
      'lite-moderation' {
        . (Join-Path $PSScriptRoot 'lite-moderation.ps1')
        $ids += @(Get-JointLiteModerationAssertionIds -RoundIndex $RoundIndex)
      }
      'recovery' {
        . (Join-Path $PSScriptRoot 'recovery.ps1')
        $ids += @(Get-RecoveryRequiredAssertionIds -RoundIndex $RoundIndex -Channel 'local')
      }
      'proxy-switch' {
        . (Join-Path $PSScriptRoot 'proxy-switch.ps1')
        $ids += @(Get-ProxySwitchRequiredAssertionIds -RoundIndex $RoundIndex -SwitchMode 'player-command')
      }
      'channel-lifecycle' {
        . (Join-Path $PSScriptRoot 'channel-lifecycle.ps1')
        $ids += @(Get-ChannelLifecycleRequiredAssertionIds -RoundIndex $RoundIndex)
      }
      'social-contract' {
        . (Join-Path $PSScriptRoot 'social-contract.ps1')
        $ids += @(Get-SocialContractRequiredAssertionIds -RoundIndex $RoundIndex)
      }
      'custom-effects' {
        . (Join-Path $PSScriptRoot 'custom-effects.ps1')
        $ids += @(Get-CustomEffectsRequiredAssertionIds -RoundIndex $RoundIndex)
      }
    }
  }
  return @($ids | Select-Object -Unique)
}
