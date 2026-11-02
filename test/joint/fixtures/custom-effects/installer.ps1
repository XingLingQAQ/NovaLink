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
