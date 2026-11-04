function Install-CustomEffectsBukkitBridge {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$FixtureJar,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ServerDirectory
  )
  $source = (Resolve-Path -LiteralPath $FixtureJar -ErrorAction Stop).ProviderPath
  if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Custom effects fixture jar is not a file: $source" }
  $plugins = Join-Path ([IO.Path]::GetFullPath($ServerDirectory)) 'plugins'
  $null = New-Item -ItemType Directory -Path $plugins -Force -ErrorAction Stop
  $destination = Join-Path $plugins 'JointCustomEffectsBridge.jar'
  Copy-Item -LiteralPath $source -Destination $destination -Force -ErrorAction Stop
  return $destination
}

function New-RenderEffectsRconCallback {
  param([int]$RconPort, [string]$RconPassword)
  if ($RconPort -le 0 -or -not $RconPassword) { throw 'Render effects requires primary Bukkit RCON credentials.' }
  $sendRcon = (Get-Command Send-Rcon -CommandType Function -ErrorAction Stop).ScriptBlock
  {
    param([string]$Username, [string]$ItemToken)
    if ($Username -cne 'E2ERenderSource' -or $ItemToken -cnotmatch '^[A-Za-z0-9_.-]{1,160}$') {
      throw 'Invalid render effects fixture player or item token.'
    }
    $response = & $sendRcon -Port $RconPort -Password $RconPassword -Command "jointcustomeffects equip $Username $ItemToken"
    if (-not $response -or -not $response.Contains('JointCustomEffectsBridge queued equip')) {
      throw 'Render effects fixture did not confirm sender permissions and held item.'
    }
    return $response
  }.GetNewClosure()
}
