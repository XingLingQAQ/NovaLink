[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Destination)
$ErrorActionPreference = 'Stop'
$target = Join-Path $Destination 'test/joint'
New-Item -ItemType Directory -Force -Path $target, (Join-Path $target 'scenarios') | Out-Null
foreach ($name in @('assert-joint-ci.ps1', 'assert-joint-raw-evidence.ps1', 'evidence-checks.ps1')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $name) -Destination (Join-Path $target $name)
}
foreach ($source in @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'scenarios') -Filter '*.ps1' -File)) {
    Copy-Item -LiteralPath $source.FullName -Destination (Join-Path $target 'scenarios')
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../versions.lock.ps1') -Destination (Join-Path $Destination 'test/versions.lock.ps1')
