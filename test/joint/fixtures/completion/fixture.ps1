. (Join-Path $PSScriptRoot '../../scenarios/completion.ps1')

function Install-JointCompletionFixture {
  param([Parameter(Mandatory = $true)][ValidateSet('velocity', 'bungee')][string]$Proxy,
    [Parameter(Mandatory = $true)][string]$PluginsDir,
    [Parameter(Mandatory = $true)][string]$RunId,
    [string]$FixtureJar = '')

  if (-not $FixtureJar) {
    $FixtureJar = Join-Path $PSScriptRoot "../../../../NovaChat/Proxy/$Proxy/build/libs/joint-completion-$Proxy-fixture.jar"
  }
  $source = (Resolve-Path -LiteralPath $FixtureJar -ErrorAction Stop).ProviderPath
  $plugins = [IO.Path]::GetFullPath($PluginsDir)
  $directory = Join-Path $plugins 'joint-completion-fixture'
  $jar = Join-Path $plugins "joint-completion-$Proxy-fixture.jar"
  $config = Join-Path $directory 'players.properties'
  $plans = Get-JointCompletionPlans -RunId $RunId -Proxy $Proxy
  New-Item -ItemType Directory -Path $directory -Force | Out-Null
  Copy-Item -LiteralPath $source -Destination $jar -Force -ErrorAction Stop
  [IO.File]::WriteAllText($config, "requester=$($plans.Names.Requester)`ntarget=$($plans.Names.Target)`n", (New-Object Text.UTF8Encoding $false))
  [PSCustomObject]@{ Proxy = $Proxy; RunId = $RunId; Names = $plans.Names; Jar = $jar; Config = $config;
    ReadyMarker = "JOINT_COMPLETION_READY $Proxy"; ConsoleCommand = 'jointcompletion' }
}

function New-JointCompletionPermissionSetter {
  param([Parameter(Mandatory = $true)][scriptblock]$ConsoleCommand,
    [Parameter(Mandatory = $true)][string]$ProxyLogPath,
    [ValidateRange(1, 60)][int]$TimeoutSec = 20)

  $completionConsole = $ConsoleCommand
  $completionLogPath = [IO.Path]::GetFullPath($ProxyLogPath)
  $completionReceiptTimeoutMs = $TimeoutSec * 1000
  return {
    param([string]$Username, [string]$Permission, [bool]$Allowed)
    if ($Permission -cne 'novachat.feature.mention' -or $Username -cnotmatch '^[A-Za-z0-9_]{1,16}$') {
      throw 'Completion permission callback accepts one mention node and a valid player name.'
    }
    $nonce = [guid]::NewGuid().ToString('N')
    $verb = if ($Allowed) { 'grant' } else { 'revoke' }
    $command = "jointcompletion permission $Username $verb $nonce"
    $receiptClock = [Diagnostics.Stopwatch]::StartNew()
    $null = & $completionConsole $command
    do {
      $logText = ''
      if ([IO.File]::Exists($completionLogPath)) {
        try {
          $stream = [IO.FileStream]::new($completionLogPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
          try {
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8)
            try { $logText = $reader.ReadToEnd() } finally { $reader.Dispose() }
          } finally { $stream.Dispose() }
        } catch [IO.IOException] { }
      }
      if ($logText) {
        foreach ($line in ($logText -split '\r?\n')) {
          $match = [regex]::Match($line, 'JOINT_COMPLETION_PERMISSION (\{[^\r\n]*\})')
          if (-not $match.Success) { continue }
          $receipt = $null
          try { $receipt = $match.Groups[1].Value | ConvertFrom-Json -ErrorAction Stop } catch { continue }
          if ($receipt.nonce -cne $nonce) { continue }
          if ($receipt.player -cne $Username -or $receipt.node -cne $Permission -or $receipt.allowed -ne $Allowed -or
              $receipt.online -isnot [bool] -or $receipt.ok -ne $true -or
              (-not $Allowed -and -not $receipt.online) -or ($receipt.online -eq $true -and $receipt.effective -ne $Allowed)) {
            throw "Completion fixture did not apply the requested permission: $line"
          }
          return [PSCustomObject]@{ Evidence = $line; Receipt = $receipt; Command = $command; LocalTime = (Get-Date).ToString('o') }
        }
      }
      Start-Sleep -Milliseconds 100
    } while ($receiptClock.ElapsedMilliseconds -lt $completionReceiptTimeoutMs)
    throw "Missing live completion fixture receipt for $Username/$nonce in $completionLogPath after $($receiptClock.ElapsedMilliseconds)ms"
  }.GetNewClosure()
}
