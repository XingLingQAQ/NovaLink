# Parse-check every PowerShell script under test/ with the PS 5.1 parser.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File test/joint/verify-joint-syntax.ps1
#        If -Path is omitted, uses the repo root derived from this script's
#        location (test/joint/ -> repo root is two levels up).
[CmdletBinding()]
param(
  [string]$RepoRoot = "",
  [string]$Path = ""
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) { $RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot) }
if (-not $Path) { $Path = $RepoRoot }

$files = @()
if (Test-Path -LiteralPath $Path -PathType Leaf) {
  $files = @(Get-Item -LiteralPath $Path)
} else {
  $files = @(Get-ChildItem -LiteralPath $Path -Recurse -File -Filter "*.ps1" -ErrorAction SilentlyContinue)
}

$failed = 0
foreach ($f in $files) {
  $tokens = $null
  $errors = $null
  [void][System.Management.Automation.Language.Parser]::ParseFile($f.FullName, [ref]$tokens, [ref]$errors)
  if ($errors -and $errors.Count -gt 0) {
    $failed++
    Write-Host ("FAIL {0}" -f $f.FullName)
    foreach ($e in $errors) {
      Write-Host ("  line {0}: {1}" -f $e.Extent.StartLineNumber, $e.Message)
    }
  } else {
    Write-Host ("OK   {0}" -f $f.FullName)
  }
}

Write-Host ("--- {0} file(s), {1} failed ---" -f $files.Count, $failed)
if ($failed -gt 0) { exit 1 }
exit 0
