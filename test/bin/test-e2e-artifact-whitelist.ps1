$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$workflow = Get-Content (Join-Path $root '.github/workflows/e2e.yml') -Raw
$match = [regex]::Match($workflow, '(?ms)^          \$binaryExt = .*?^          \$candidates = @\(.*?^            \}\)')
if (-not $match.Success) { throw 'artifact whitelist not found' }
$whitelist = [scriptblock]::Create(($match.Value -replace '(?m)^          ', ''))
$srcRoot = Join-Path ([IO.Path]::GetTempPath()) ('novalink-artifacts-' + [guid]::NewGuid().ToString('N'))

try {
    $files = @(
        'runs/folia/bot-results.json', 'runs/folia/bot-results.json.route-peer.jsonl',
        'runs/folia/plugin-artifact.txt', 'classpath-build.log', 'runs/folia/build.out.log',
        'runs/folia/unrelated.jsonl', 'runs/folia/unrelated.txt', 'runs/folia/plugin.jar',
        'dist/bot-results.json.route-peer.jsonl', 'runs/folia/site-packages/plugin-artifact.txt'
    )
    foreach ($file in $files) {
        $path = Join-Path $srcRoot $file
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        Set-Content -LiteralPath $path -Value 'fixture'
    }
    . $whitelist
    $actual = @($candidates | ForEach-Object { $_.FullName.Substring($srcRoot.Length).TrimStart('\', '/').Replace('\', '/') } | Sort-Object)
    $expected = @($files | Select-Object -First 5 | Sort-Object)
    if (Compare-Object $expected $actual) { throw "unexpected artifact whitelist: $($actual -join ', ')" }
    Write-Output 'E2E artifact whitelist checks passed'
} finally {
    if (Test-Path -LiteralPath $srcRoot) {
        $resolved = (Resolve-Path -LiteralPath $srcRoot).Path
        $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        if ((Split-Path -Parent $resolved) -ne $parent -or (Split-Path -Leaf $resolved) -notmatch '^novalink-artifacts-[0-9a-f]{32}$') {
            throw 'Refusing unexpected test cleanup path'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
