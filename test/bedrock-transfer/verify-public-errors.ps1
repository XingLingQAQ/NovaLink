[CmdletBinding()]
param([string]$OutputRoot = '')

$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
. (Join-Path $PSScriptRoot 'native-runtime.ps1')
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('.e2e-artifacts/bedrock-transfer-public-errors-' + [guid]::NewGuid().ToString('N')) }
if (Test-Path -LiteralPath $OutputRoot) { throw 'Use a new isolated public error check directory.' }
$null = New-Item -ItemType Directory -Path $OutputRoot
foreach ($file in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File) {
    $tokens = $null
    $parseErrors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile($file.FullName, [ref]$tokens, [ref]$parseErrors)
    if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
}
foreach ($file in @('run-bot.js', 'assert-evidence.js')) {
    & node --check (Join-Path $PSScriptRoot $file)
    if ($LASTEXITCODE -ne 0) { throw "JavaScript parse failed: $file" }
}
$secrets = @('backend.$[1]"secret', "client'\secret")
$raw = "Command failed: powershell -Password $($secrets[1])`nbackend=$($secrets[0])"
$public = Protect-BedrockErrorText -Text $raw -Secrets $secrets
foreach ($secret in $secrets) {
    if ($public.Contains($secret)) { throw 'PowerShell public error retained a secret.' }
}
$utf8 = [Text.UTF8Encoding]::new($false)
$backend = Join-Path $OutputRoot 'backend'
$null = New-Item -ItemType Directory -Path $backend
$logPath = Join-Path $backend 'stderr.log'
$marker = 'BT_TRANSFER_CALLED:a:run-outbound-transfer:BTTraveler:/127.0.0.1:19363'
[IO.File]::WriteAllText($logPath, ($raw + "`n" + $marker), $utf8)
Protect-BedrockPublicLogs -RunRoot $OutputRoot -Secrets $secrets
$published = [IO.File]::ReadAllText($logPath)
foreach ($secret in $secrets) {
    if ($published.Contains($secret)) { throw 'Public process log retained a secret.' }
}
if (-not $published.Contains($marker)) { throw 'Public log redaction changed command evidence.' }
$json = @{ error = $public; pass = $false } | ConvertTo-Json
$parsed = $json | ConvertFrom-Json
if ($parsed.error -cne $public) { throw 'Public error did not survive JSON serialization.' }
& node --test (Join-Path $PSScriptRoot 'public-errors.test.js') *> (Join-Path $OutputRoot 'public-errors-contract.log')
if ($LASTEXITCODE -ne 0) { throw "Public error contract failed: $OutputRoot/public-errors-contract.log" }
$summary = @{ pass = $true; scope = 'exact secret redaction in errors, JSON and public logs; normal evidence preserved; no servers'; outputRoot = $OutputRoot }
[IO.File]::WriteAllText((Join-Path $OutputRoot 'public-errors-summary.json'), ($summary | ConvertTo-Json), $utf8)
Write-Host "PASS public error evidence check (no servers): $OutputRoot"
