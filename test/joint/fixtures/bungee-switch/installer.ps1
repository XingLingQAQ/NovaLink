function Read-JointBungeeJarEntry {
  param([string]$JarPath, [string]$EntryName)
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $archive = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
  try {
    $entry = $archive.GetEntry($EntryName)
    if (-not $entry) { throw "Required entry '$EntryName' is missing from $JarPath" }
    $reader = [System.IO.StreamReader]::new($entry.Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
  } finally { $archive.Dispose() }
}

function Install-JointBungeeServerCommand {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ModuleJar,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ServerDirectory
  )

  . (Join-Path $PSScriptRoot '../../../versions.lock.ps1')
  $module = Get-LockedServer -Name 'waterfall-cmd-server'
  $proxy = Get-LockedServer -Name 'waterfall'
  if (-not $module -or -not $proxy -or $module.Proxy -cne $proxy.Name -or
      $module.Build -cne $proxy.Build -or $module.MC -cne $proxy.MC) {
    throw 'Bungee player-command requires matching Waterfall and waterfall-cmd-server pins in test/versions.lock.ps1.'
  }
  $paths = @{}
  foreach ($pair in @(@{ Entry = $proxy; Path = $ServerJar }, @{ Entry = $module; Path = $ModuleJar })) {
    $entry = $pair.Entry
    if ($entry.Sha256 -notmatch '\A[0-9a-fA-F]{64}\z' -or $entry.Sha256 -match '\A([0-9a-fA-F])\1{63}\z') {
      throw "Bungee player-command requires a pinned SHA-256 for $($entry.Name)."
    }
    if (-not (Test-Path -LiteralPath $pair.Path -PathType Leaf)) {
      throw "Bungee player-command prerequisite missing: $($pair.Path). Run test/bin/fetch-server.ps1 -Name $($entry.Name) -DistDir <cache> in pinned mode; no API fallback is allowed."
    }
    $path = (Resolve-Path -LiteralPath $pair.Path -ErrorAction Stop).ProviderPath
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256 -ErrorAction Stop).Hash -ine $entry.Sha256) {
      throw "Bungee player-command SHA-256 mismatch for $($entry.Name): $path. Expected $($entry.Sha256); refusing API fallback."
    }
    $paths[$entry.Name] = $path
  }
  $manifest = (Read-JointBungeeJarEntry $paths[$proxy.Name] 'META-INF/MANIFEST.MF') -replace '\r?\n ', ''
  if ($manifest -cnotmatch ('(?m)^Implementation-Version: ' + [regex]::Escape($module.ProxyVersion) + '\r?$')) {
    throw "Waterfall implementation version must be $($module.ProxyVersion)."
  }
  $plugin = Read-JointBungeeJarEntry $paths[$module.Name] 'plugin.yml'
  foreach ($line in @('name: cmd_server', 'main: net.md_5.bungee.module.cmd.server.PluginServer', "version: $($module.ModuleVersion)")) {
    if ($plugin -cnotmatch ('(?m)^' + [regex]::Escape($line) + '\r?$')) { throw "Upstream cmd_server metadata mismatch: $line" }
  }

  $directory = [System.IO.Path]::GetFullPath($ServerDirectory)
  $modulesFile = Join-Path $directory 'modules.yml'
  $modulesConfig = "version: 2`nmodules:`n  - jenkins://cmd_server`n"
  if (Test-Path -LiteralPath $modulesFile) {
    if (([System.IO.File]::ReadAllText($modulesFile) -replace '\r\n', "`n") -cne $modulesConfig) {
      throw "Bungee player-command requires a fresh scenario directory or this exact modules.yml: $modulesConfig Existing configuration at $modulesFile may trigger unpinned module updates."
    }
  }
  $modulesDirectory = Join-Path $directory 'modules'
  $null = New-Item -ItemType Directory -Path $modulesDirectory -Force -ErrorAction Stop
  $destination = Join-Path $modulesDirectory 'cmd_server.jar'
  Copy-Item -LiteralPath $paths[$module.Name] -Destination $destination -Force -ErrorAction Stop
  [System.IO.File]::WriteAllText($modulesFile, $modulesConfig, [System.Text.UTF8Encoding]::new($false))
  return $destination
}

function Install-JointBungeeSwitchFixture {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ServerJar,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$JdkHome,
    [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ServerDirectory
  )

  $serverJarPath = (Resolve-Path -LiteralPath $ServerJar -ErrorAction Stop).ProviderPath
  if (-not (Test-Path -LiteralPath $serverJarPath -PathType Leaf)) {
    throw "Waterfall fat jar is not a file: $serverJarPath"
  }
  . (Join-Path $PSScriptRoot '../../../versions.lock.ps1')
  $waterfall = Get-LockedServer -Name 'waterfall'
  if (-not $waterfall -or -not $waterfall.Sha256) { throw 'Waterfall has no pinned SHA-256.' }
  if ((Get-FileHash -LiteralPath $serverJarPath -Algorithm SHA256).Hash -ine $waterfall.Sha256) {
    throw "Waterfall fat jar does not match test/versions.lock.ps1: $serverJarPath"
  }

  $jdkRoot = (Resolve-Path -LiteralPath $JdkHome -ErrorAction Stop).ProviderPath
  $javac = Join-Path $jdkRoot 'bin/javac.exe'
  if (-not (Test-Path -LiteralPath $javac -PathType Leaf)) { $javac = Join-Path $jdkRoot 'bin/javac' }
  $jar = Join-Path $jdkRoot 'bin/jar.exe'
  if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { $jar = Join-Path $jdkRoot 'bin/jar' }
  foreach ($tool in @($javac, $jar)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "JDK tool is missing: $tool" }
  }

  $source = Join-Path $PSScriptRoot 'src/com/nova/joint/fixture/JointSwitchFixture.java'
  $metadata = Join-Path $PSScriptRoot 'bungee.yml'
  $buildPrefix = Join-Path ([System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())) 'novalink-joint-bungee-switch-'
  $buildRoot = $buildPrefix + [guid]::NewGuid().ToString('N')
  $savedEap = $ErrorActionPreference
  try {
    $classes = Join-Path $buildRoot 'classes'
    $null = New-Item -ItemType Directory -Path $classes -Force -ErrorAction Stop
    $ErrorActionPreference = 'Continue'
    & $javac -encoding UTF-8 --release 17 -proc:none -classpath $serverJarPath -d $classes $source
    $compileExit = $LASTEXITCODE
    $ErrorActionPreference = $savedEap
    if ($compileExit -ne 0) { throw "JointSwitchFixture javac failed with exit code $compileExit" }
    Copy-Item -LiteralPath $metadata -Destination (Join-Path $classes 'bungee.yml') -ErrorAction Stop
    $builtJar = Join-Path $buildRoot 'JointSwitchFixture.jar'
    $ErrorActionPreference = 'Continue'
    & $jar --create --file $builtJar -C $classes .
    $jarExit = $LASTEXITCODE
    $ErrorActionPreference = $savedEap
    if ($jarExit -ne 0) { throw "JointSwitchFixture jar failed with exit code $jarExit" }

    $plugins = Join-Path ([System.IO.Path]::GetFullPath($ServerDirectory)) 'plugins'
    $null = New-Item -ItemType Directory -Path $plugins -Force -ErrorAction Stop
    $destination = Join-Path $plugins 'JointSwitchFixture.jar'
    Copy-Item -LiteralPath $builtJar -Destination $destination -Force -ErrorAction Stop
    return $destination
  } finally {
    $ErrorActionPreference = $savedEap
    $resolvedBuild = [System.IO.Path]::GetFullPath($buildRoot)
    if (-not $resolvedBuild.StartsWith($buildPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
      throw "Fixture cleanup path is outside its temporary prefix: $resolvedBuild"
    }
    if (Test-Path -LiteralPath $resolvedBuild) {
      Remove-Item -LiteralPath $resolvedBuild -Recurse -Force -ErrorAction Stop
    }
  }
}
