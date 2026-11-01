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
