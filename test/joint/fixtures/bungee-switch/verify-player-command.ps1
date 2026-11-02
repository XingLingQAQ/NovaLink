[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$ServerJar,
  [Parameter(Mandatory = $true)][string]$ModuleJar
)
$ErrorActionPreference = 'Stop'
$scenarioPath = Join-Path $PSScriptRoot '../../scenarios/proxy-switch.ps1'
$installerPath = Join-Path $PSScriptRoot 'installer.ps1'
foreach ($path in @($PSCommandPath, $installerPath, $scenarioPath, (Join-Path $PSScriptRoot '../../../versions.lock.ps1'))) {
  $tokens = $null
  $errors = $null
  $null = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$tokens, [ref]$errors)
  if ($errors.Count) { throw ($errors | Out-String) }
}
. $installerPath
. $scenarioPath
. (Join-Path $PSScriptRoot '../../../versions.lock.ps1')

function Assert-CommandContract {
  param([bool]$Pass, [string]$Label)
  if (-not $Pass) { throw "Bungee command offline contract failed: $Label" }
}

$prefix = Join-Path ([System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())) 'novalink-bungee-command-check-'
$work = $prefix + [guid]::NewGuid().ToString('N')
try {
  $destination = Install-JointBungeeServerCommand -ServerJar $ServerJar -ModuleJar $ModuleJar -ServerDirectory (Join-Path $work 'proxy')
  $pin = Get-LockedServer -Name 'waterfall-cmd-server'
  Assert-CommandContract ((Get-FileHash -LiteralPath $destination).Hash -ceq $pin.Sha256) 'installed archive is byte-identical to the official pin'
  Assert-CommandContract (([IO.File]::ReadAllText((Join-Path $work 'proxy/modules.yml'))) -ceq "version: 2`nmodules:`n  - jenkins://cmd_server`n") 'only the matching upstream module is configured'
  $corrupt = Join-Path $work 'corrupt.jar'
  [IO.File]::WriteAllText($corrupt, 'not the upstream module')
  $rejected = $false
  try { $null = Install-JointBungeeServerCommand -ServerJar $ServerJar -ModuleJar $corrupt -ServerDirectory (Join-Path $work 'rejected') }
  catch { $rejected = $_.Exception.Message -like '*SHA-256 mismatch*' }
  Assert-CommandContract ($rejected -and -not (Test-Path -LiteralPath (Join-Path $work 'rejected'))) 'corrupt module fails before installation without API fallback'
  $missingRejected = $false
  try { $null = Install-JointBungeeServerCommand -ServerJar $ServerJar -ModuleJar (Join-Path $work 'missing.jar') -ServerDirectory (Join-Path $work 'missing') }
  catch { $missingRejected = $_.Exception.Message -like '*prerequisite missing*' -and $_.Exception.Message -like '*fetch-server.ps1*' }
  Assert-CommandContract $missingRejected 'missing module explains the pinned download prerequisite'

  $plans = Get-JointProxySwitchPlans 'offline-command' -PrimaryServer 'bukkit' -SecondaryServer 'bukkit2' -SwitchMode player-command
  $apiPlans = Get-JointProxySwitchPlans 'offline-api' -SwitchMode callback
  $ids = @(Get-ProxySwitchRequiredAssertionIds -RoundIndex 1 -SwitchMode player-command)
  Assert-CommandContract ($ids.Count -eq 26 -and @(Get-ProxySwitchRequiredAssertionIds -RoundIndex 1 -SwitchMode callback).Count -eq 23) 'command and API contracts remain distinct'
  foreach ($leg in @(
    @{ Phase = 'right'; Role = 'Right'; Server = 'bukkit2'; Token = 'offline-command-placed-right' },
    @{ Phase = 'move'; Role = 'Mover'; Server = 'bukkit2'; Token = 'offline-command-leg-moved' },
    @{ Phase = 'return'; Role = 'Mover'; Server = 'bukkit'; Token = 'offline-command-leg-return' }
  )) {
    $steps = $plans[$leg.Role]
    $confirmation = @($steps | Where-Object { $_.id -ceq "switch-$($leg.Phase)-server-confirmed" })
    Assert-CommandContract ($confirmation.Count -eq 1 -and $confirmation[0].required -and $confirmation[0].sinceCheckpoint -ceq "switch-$($leg.Phase)-command") "required target confirmation for $($leg.Phase)"
    Assert-CommandContract ($ids -ccontains "round1/proxy-switch/player-command-$($leg.Phase)") "mandatory command ID for $($leg.Phase)"
    Assert-CommandContract (@($apiPlans[$leg.Role] | Where-Object { $_.action -eq 'cmd' -and $_.text -like '/server *' }).Count -eq 0) 'API plans never emit /server'
    $result = [pscustomobject]@{
      kind = 'java'; runId = 'offline-command'
      sent = @([pscustomobject]@{ text = "/server $($leg.Server)"; atMs = 101 })
      received = @(
        [pscustomobject]@{ kind = 'checkpoint'; key = "switch-$($leg.Phase)-command"; atMs = 100 },
        [pscustomobject]@{ kind = 'messagestr'; text = $leg.Token; atMs = 102 }
      )
      assertions = @([pscustomobject]@{ id = "switch-$($leg.Phase)-server-confirmed"; pass = $true; kind = 'chat-pattern' })
    }
    Assert-CommandContract (Test-JointProxyPlayerCommandReceipt $result 'offline-command' $leg.Phase $leg.Server) 'synthetic command with fresh target receipt is accepted'
    $result.sent[0].text = '/server wrong-target'
    Assert-CommandContract (-not (Test-JointProxyPlayerCommandReceipt $result 'offline-command' $leg.Phase $leg.Server)) 'wrong target command is rejected'
    $result.sent[0].text = "/server $($leg.Server)"
    $result.received[1].atMs = 99
    Assert-CommandContract (-not (Test-JointProxyPlayerCommandReceipt $result 'offline-command' $leg.Phase $leg.Server)) 'stale target receipt is rejected'
    $result.received[1].atMs = 102
    $result.sent = @()
    Assert-CommandContract (-not (Test-JointProxyPlayerCommandReceipt $result 'offline-command' $leg.Phase $leg.Server)) 'API-only target receipt cannot satisfy command evidence'
  }
  Write-Host 'PASS: pinned upstream archives, installer prerequisites, PowerShell syntax and synthetic command contracts; no real /server or proxy-switch E2E was run.'
} finally {
  $resolved = [System.IO.Path]::GetFullPath($work)
  if (-not $resolved.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw "Cleanup path escaped its temporary prefix: $resolved" }
  if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force }
}
