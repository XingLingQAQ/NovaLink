. (Join-Path $PSScriptRoot 'scenarios/helpers.ps1')

function Assert-Delivered([string]$assertionId, [string]$token, $receiverResult) {
  $hit = $null
  if ($receiverResult -and $receiverResult.received) {
    foreach ($entry in $receiverResult.received) {
      if ($entry.text -and $entry.text.Contains($token)) { $hit = $entry; break }
    }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = ($null -ne $hit)
    evidence = if ($hit) { $hit.text } else { '' }
    kind = 'delivery'
  }
}

function Assert-ProxyPluginLoaded([string]$assertionId, [string]$proxyName, [string]$logText) {
  $pattern = 'NovaChat (Velocity|BungeeCord) plugin enabled|NovaChat plugin enabled'
  $text = $logText
  $pass = [bool]($text -and ($text -match $pattern))
  $evidence = ''
  if ($pass) {
    $line = ($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1)
    $evidence = if ($line) { $line.Trim() } else { '' }
  } else {
    $evidence = "no plugin init line in the supplied log"
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $proxyName
    pass = $pass
    evidence = $evidence
    kind = 'proxy-plugin'
  }
}

function Assert-LogContains([string]$assertionId, [string]$token, [string]$logText,
                            [string]$pattern) {
  $text = [regex]::Replace(($logText), ([string][char]27 + '\[[0-?]*[ -/]*[@-~]'), '')
  $pass = [bool]($text -and ($text -match $pattern))
  $evidence = ''
  if ($pass) {
    $line = ($text -split "`n" | Where-Object { $_ -match $pattern } | Select-Object -First 1)
    $evidence = if ($line) { $line.Trim() } else { '' }
  } else {
    $evidence = "pattern not found in the supplied log: $pattern"
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = $token
    pass = $pass
    evidence = $evidence
    kind = 'proxy-log'
  }
}

function Get-BackendAuthIdentities([string]$logText) {
  $text = $logText
  $ids = @()
  $lines = @()
  if ($text) {
    foreach ($line in ($text -split "`n")) {
      $m = [regex]::Match($line, 'Client authenticated:\s*(\S+)\s+from\s+(\S+)')
      if ($m.Success) {
        $ids += $m.Groups[1].Value
        $lines += $line.Trim()
      }
    }
  }
  return [PSCustomObject]@{ Identities = $ids; Lines = $lines }
}

function Assert-RelayDistinctIdentities([string]$assertionId, [string]$backendLogText,
                                        [string[]]$expectedIdentities,
                                        [string[]]$proxyOwnedIdentities) {
  $auth = Get-BackendAuthIdentities $backendLogText
  $unique = @($auth.Identities | Sort-Object -Unique)
  $missing = @($expectedIdentities | Where-Object { $unique -notcontains $_ })
  $unexpectedProxy = @($proxyOwnedIdentities | Where-Object { $unique -contains $_ })
  $pass = ($missing.Count -eq 0) -and ($unexpectedProxy.Count -eq 0) -and
          ($unique.Count -ge $expectedIdentities.Count)
  $evidenceLines = @()
  foreach ($line in $auth.Lines) {
    foreach ($id in $expectedIdentities) {
      if ($line -match "Client authenticated:\s*$([regex]::Escape($id))\s") { $evidenceLines += $line }
    }
  }
  $evidence = if ($pass) {
    ($evidenceLines -join ' | ')
  } else {
    $parts = @()
    if ($missing.Count -gt 0) { $parts += "missing identity(ies): $($missing -join ', ')" }
    if ($unexpectedProxy.Count -gt 0) { $parts += "proxy fallback identity(ies) also authenticated: $($unexpectedProxy -join ', ')" }
    $parts += "authenticated this run: $(if ($unique.Count -gt 0) { $unique -join ', ' } else { '<none>' })"
    ($parts -join '; ')
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = ($expectedIdentities -join ',')
    pass = $pass
    evidence = $evidence
    kind = 'relay-identity'
  }
}

function Assert-RelayLinksCreated([string]$assertionId, [string]$proxyLogText,
                                  [string[]]$expectedServers) {
  $text = $proxyLogText
  $line = ''
  if ($text) {
    $line = ($text -split "`n" | Where-Object { $_ -match 'Relay backend links:' } | Select-Object -First 1)
  }
  $line = if ($line) { $line.Trim() } else { '' }
  $pass = $false
  $detail = 'no "Relay backend links:" line in the proxy log'
  if ($line) {
    $m = [regex]::Match($line, 'Relay backend links:\s*(\d+)\s+connection\(s\)')
    if ($m.Success) {
      $count = [int]$m.Groups[1].Value
      $missingServers = @($expectedServers | Where-Object { $line -notmatch [regex]::Escape($_) })
      $mentionsShared = $line -match 'shared backend\.username link'
      $pass = ($count -eq $expectedServers.Count) -and ($missingServers.Count -eq 0) -and (-not $mentionsShared)
      $detail = if ($pass) { '' } else {
        "expected $($expectedServers.Count) dedicated connection(s) for $($expectedServers -join ', '); " +
        "line reports $count" + $(if ($mentionsShared) { ' and the shared fallback' } else { '' }) +
        $(if ($missingServers.Count -gt 0) { "; not named: $($missingServers -join ', ')" } else { '' })
      }
    } else {
      $detail = "could not parse a connection count from: $line"
    }
  }
  return [PSCustomObject]@{
    id = $assertionId
    token = ($expectedServers -join ',')
    pass = $pass
    evidence = if ($pass) { $line } else { $detail }
    kind = 'relay-identity'
  }
}

function Assert-BotExpectations([string]$role, $result) {
  $out = @()
  if (-not $result -or $result.error -or $result.timedOut) {
    $out += New-JointAssertion -Id "$role/completed" -Token 'results' -Pass $false `
      -Evidence "recording=$([bool]$result); error=$($result.error); timedOut=$($result.timedOut)" -Kind 'bot-completion'
  }
  if (-not $result) { return $out }
  if ($result.assertions) {
    foreach ($a in $result.assertions) {
      $botKind = if ($a.kind) { [string]$a.kind } else { 'expect' }
      $isDiagnostic = ($a.diagnostic -eq $true)
      $out += [PSCustomObject]@{
        id = "$role/$($a.id)"
        token = $a.token
        pass = [bool]$a.pass
        evidence = $a.evidence
        kind = if ($isDiagnostic) { 'diagnostic' } else {
          switch ($botKind) {
            'effect'         { 'in-bot-effect' }
            'effect-absent'  { 'in-bot-effect-absent' }
            'kicked'         { 'in-bot-kicked' }
            'state'          { 'in-bot-state' }
            'selftest'       { 'in-bot-selftest' }
            default          { 'in-bot-expect' }
          }
        }
      }
    }
  }
  if ($result.absentChecks) {
    foreach ($a in $result.absentChecks) {
      $out += [PSCustomObject]@{
        id = "$role/$($a.id)"
        token = $a.token
        pass = [bool]$a.pass
        evidence = $a.evidence
        kind = 'in-bot-absent'
      }
    }
  }
  return $out
}
