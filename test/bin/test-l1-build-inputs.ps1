$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'e2e-assertions.ps1')
$parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$root = Join-Path $parent ('novalink-l1-build-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $root '.e2e-artifacts'), (Join-Path $root 'logs') | Out-Null
    $cp = Join-Path $root '.e2e-artifacts/novalink-core.classpath.txt'
    [IO.File]::WriteAllText($cp, 'pre-existing classpath')
    $wrapper = Join-Path $root 'gradlew.bat'
    [IO.File]::WriteAllText($wrapper, "@echo called>>`"$root\calls.txt`"`r`n@exit /b 1`r`n")
    $failed = $false
    try { $null = Export-L1BackendClasspath -RepoRoot $root -RunsDir (Join-Path $root 'logs') } catch { $failed = $true }
    if (-not $failed) { throw 'A failed current build reused an old classpath' }
    [IO.File]::WriteAllText($wrapper, "@echo called>>`"$root\calls.txt`"`r`n@exit /b 0`r`n")
    $result = Export-L1BackendClasspath -RepoRoot $root -RunsDir (Join-Path $root 'logs')
    if ($result -ne $cp -or @(Get-Content (Join-Path $root 'calls.txt')).Count -ne 2) { throw 'Existing classpath bypassed a build' }
    Write-Output '[SYNTHETIC] Current-build control flow passed; no server evidence produced.'
} finally {
    if (Test-Path -LiteralPath $root) {
        $resolved = (Resolve-Path -LiteralPath $root).Path
        if ((Split-Path -Parent $resolved) -ne $parent.TrimEnd('\') -or (Split-Path -Leaf $resolved) -notmatch '^novalink-l1-build-[0-9a-f]{32}$') {
            throw 'Refusing unexpected test cleanup path'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
