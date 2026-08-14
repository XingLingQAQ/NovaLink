# ============================================================================
# start-backend.ps1 -- portable NovaLink backend launcher for the E2E matrix.
#
# CI-friendly port of .e2e/bin/start-backend.ps1 (gitignored, machine-specific).
# Takes -RepoRoot + -RunsDir as parameters; resolves the classpath file written
# by write-classpath.init.gradle; generates a novalink.yml from the shared
# template; starts the backend JVM in the background.
#
# The backend build (gradle :StarLink:core:jar + :StarLink:core:writeRuntimeClasspath)
# must have already run -- this script only consumes the classpath file. The
# per-platform orchestrator (run-<platform>-e2e.ps1) runs the gradle build before
# calling this script.
#
# Exit codes:
#   0 = backend started, PID written to $PidFile
#   1 = prereq error (classpath/config/java missing)
# ============================================================================
[CmdletBinding()]
param(
    [string]$RepoRoot  = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)))),
    [string]$RunsDir   = "",     # per-platform run workspace; e.g. <repo>/.e2e-artifacts/runs/folia
    [string]$ClassPathFile = "", # defaults to <repo>/.e2e-artifacts/novalink-core.classpath.txt
    [string]$JdkHome   = $env:JAVA_HOME,
    [int]$NovaPort     = 27905,
    [int]$NovaWsPort   = 34573,
    [string]$SecretKey = "",
    [string]$ClientPassword = ""
)

$ErrorActionPreference = "Stop"
if (-not $RunsDir) { $RunsDir = Join-Path $RepoRoot ".e2e-artifacts/runs/backend" }
if (-not $ClassPathFile) { $ClassPathFile = Join-Path $RepoRoot ".e2e-artifacts/novalink-core.classpath.txt" }
$binDir  = Join-Path $RepoRoot "test/bin"
$confDir = Join-Path $RepoRoot "test/conf"

function Log([string]$m) { Write-Host ("[{0}] {1}" -f (Get-Date -Format "HH:mm:ss"), $m) }

# --- resolve JDK -------------------------------------------------------------
if (-not $JdkHome) {
    Log "ERROR: -JdkHome not set and JAVA_HOME is empty"
    exit 1
}
$isWin = $IsWindows -or $env:OS -eq "Windows_NT"
$javaExe = if ($isWin) { "java.exe" } else { "java" }
$java = Join-Path $JdkHome "bin/$javaExe"
if (-not (Test-Path $java)) {
    Log "ERROR: java not found at $java (JdkHome=$JdkHome)"
    exit 1
}

# --- verify classpath --------------------------------------------------------
if (-not (Test-Path $ClassPathFile)) {
    Log "ERROR: classpath file not found: $ClassPathFile"
    Log "  Run the gradle build first: .\gradlew :StarLink:core:jar :StarLink:core:writeRuntimeClasspath --init-script test/bin/write-classpath.init.gradle"
    exit 1
}

# --- generate novalink.yml from template -------------------------------------
$novaDir = Join-Path $RunsDir "novalink"
New-Item -ItemType Directory -Force -Path $novaDir | Out-Null
if (-not $SecretKey) { $SecretKey = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32)) }
if (-not $ClientPassword) { $ClientPassword = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16)) }

$novaYml = Join-Path $novaDir "novalink.yml"
$templatePath = Join-Path $confDir "novalink.template.yml"
if (-not (Test-Path -LiteralPath $templatePath -PathType Leaf)) {
    Log "ERROR: required backend config template not found: $templatePath"
    exit 1
}
$tpl = Get-Content -LiteralPath $templatePath -Raw
$tpl = $tpl.Replace('{{NOVA_PORT}}', $NovaPort).Replace('{{NOVA_WS_PORT}}', $NovaWsPort).Replace('{{SECRET_KEY}}', $SecretKey).Replace('{{CLIENT_PASSWORD}}', $ClientPassword)
$tpl | Set-Content -Path $novaYml -NoNewline

# --- start backend -----------------------------------------------------------
$cp = (Get-Content $ClassPathFile -Raw).Trim()
$stdout = Join-Path $novaDir "stdout.log"
$stderr = Join-Path $novaDir "stderr.log"
$pidFile = Join-Path $novaDir "backend.pid"
Remove-Item $stdout, $stderr -ErrorAction SilentlyContinue

$proc = Start-Process -FilePath $java -ArgumentList @("-cp", $cp, "com.nova.link.NovaLinkMain", $novaYml) `
    -WorkingDirectory $novaDir -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru -NoNewWindow
$proc.Id | Set-Content -Encoding ascii $pidFile
Log "backend started (pid=$($proc.Id), port=$NovaPort, ws=$NovaWsPort)"
Log "STDOUT: $stdout"
Log "PID file: $pidFile"
Write-Output ("BACKEND_PID=" + $proc.Id)
