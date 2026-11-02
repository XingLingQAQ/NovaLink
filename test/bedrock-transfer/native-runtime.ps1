function Protect-BedrockErrorText {
    param([string]$Text, [string[]]$Secrets = @($env:BT_BACKEND_SECRET, $env:BT_RCON_PASSWORD))

    foreach ($secret in @($Secrets | Where-Object { -not [string]::IsNullOrEmpty($_) } | Sort-Object -Property Length -Descending)) {
        $Text = $Text.Replace($secret, '[REDACTED]')
    }
    return $Text
}

function Protect-BedrockPublicLogs {
    param([string]$RunRoot, [string[]]$Secrets)

    foreach ($directory in @($RunRoot, (Join-Path $RunRoot 'backend'),
        (Join-Path $RunRoot 'a/pnx'), (Join-Path $RunRoot 'b/pnx'))) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.log' -File) {
            $raw = [IO.File]::ReadAllText($file.FullName)
            $public = Protect-BedrockErrorText -Text $raw -Secrets $Secrets
            if ($public -cne $raw) { [IO.File]::WriteAllText($file.FullName, $public, [Text.UTF8Encoding]::new($false)) }
        }
    }
}

function Get-PnxLoopbackConfig([int]$Port, [string]$Node) {
    return @"
settings:
  ip: '127.0.0.1'
  port: $Port
  maxPlayers: 5
  defaultLevelName: 'world'
  language: 'eng'
  motd: 'Native Bedrock Transfer $Node'
  xboxAuth: false
  autoSave: false
gameplay-settings:
  viewDistance: 4
  gamemode: 1
  forceGamemode: true
  difficulty: 0
  pvp: false
misc-settings:
  installSpark: false
  enableMetrics: false
level-settings:
  levelWorkerThreads: 2
chunk-settings:
  spawnThreshold: 16
network-settings:
  enableQuery: false
  nethernet:
    signalingMode: builtin
    signalingPort: $Port
    udpPort: $Port
    ice-servers: []
    stun-servers: []
    advertise-addresses: ['127.0.0.1']
config:
  version: '3.0.2'
"@
}

function Start-HeldConsoleProcess {
    param([string]$Executable, [string]$Arguments, [string]$Directory, [string]$Stdout, [string]$Stderr)

    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $Executable
    $start.Arguments = $Arguments
    $start.WorkingDirectory = $Directory
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $start
    $streams = @()
    $started = $false
    try {
        foreach ($file in @($Stdout, $Stderr)) {
            $streams += [IO.FileStream]::new($file, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write,
                [IO.FileShare]::ReadWrite, 1, [IO.FileOptions]::Asynchronous)
        }
        if (-not $process.Start()) { throw "Could not start $Executable" }
        $started = $true
        $process.StandardInput.AutoFlush = $true
        $pumps = @($process.StandardOutput.BaseStream.CopyToAsync($streams[0]),
            $process.StandardError.BaseStream.CopyToAsync($streams[1]))
        return [pscustomobject]@{ Process = $process; Streams = $streams; Pumps = $pumps }
    } catch {
        if ($started -and -not $process.HasExited) { $process.Kill() }
        foreach ($stream in $streams) { $stream.Dispose() }
        throw
    }
}

function Close-HeldConsoleStreams($Runtime) {
    try { $Runtime.Process.StandardInput.Close() } catch { }
    foreach ($pump in $Runtime.Pumps) {
        try { $null = $pump.Wait(5000) } catch { }
    }
    foreach ($stream in $Runtime.Streams) { $stream.Dispose() }
}

function Read-PnxPlainLog([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
    $ansi = [string][char]27 + '\[[0-?]*[ -/]*[@-~]'
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
    $reader = New-Object IO.StreamReader($stream)
    try { return [regex]::Replace($reader.ReadToEnd(), $ansi, '') } finally { $reader.Dispose() }
}

function Get-PnxReadyEvidence([string]$Log, [int]$Port) {
    $patterns = [ordered]@{
        runtime = 'This server is running PowerNukkitX version 3\.0\.6(?:-SNAPSHOT)?\b.*\(API 3\.0\.6\)'
        plugin = 'NovaChat-PNX enabled successfully!'
        fixture = 'Enabling BedrockTransferFixture v1\.0\b'
        signaling = 'NetherNet signalling listening on tcp/' + $Port + '\b'
        started = 'Done \([0-9.]+s\)! For help, type "help" or "\?"'
    }
    $evidence = [ordered]@{}
    foreach ($key in $patterns.Keys) {
        $match = [regex]::Match($Log, $patterns[$key])
        if (-not $match.Success) { return $null }
        $evidence[$key] = $match.Value
    }
    return $evidence
}

function Wait-PnxReady($Runtime, [string]$LogPath, [int]$Port) {
    $deadline = (Get-Date).AddSeconds(240)
    while ((Get-Date) -lt $deadline) {
        if ($Runtime.Process.HasExited) { throw "PNX exited before ready: $LogPath" }
        if (@($Runtime.Pumps | Where-Object { $_.IsFaulted }).Count) { throw "PNX log capture failed: $LogPath" }
        $evidence = Get-PnxReadyEvidence -Log (Read-PnxPlainLog $LogPath) -Port $Port
        if ($evidence) { return $evidence }
        Start-Sleep -Milliseconds 250
    }
    throw "PNX did not finish native startup: $LogPath"
}

function Send-PnxConsoleRequests {
    param([string]$Directory, [string]$RunId, $Runtimes, $Handled, [string[]]$AllowedNames)

    foreach ($file in Get-ChildItem -LiteralPath $Directory -Filter '*.request.json' -File) {
        if ($Handled.Contains($file.Name)) { continue }
        $request = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
        if ($request.runId -cne $RunId -or $request.id -cnotmatch '^[0-9a-f-]{36}$' -or
            $file.Name -cne ($request.id + '.request.json') -or -not $Runtimes.ContainsKey([string]$request.node)) {
            throw "Invalid native console request: $($file.Name)"
        }
        $match = [regex]::Match([string]$request.command, '^(bttransfer|btprobe|btstate) ([A-Za-z0-9_]+) ([A-Za-z0-9_-]+)$')
        if (-not $match.Success -or $match.Groups[2].Value -cnotin $AllowedNames -or
            -not $match.Groups[3].Value.StartsWith($RunId + '-', [StringComparison]::Ordinal)) {
            throw "Native console command is outside this run: $($file.Name)"
        }
        $runtime = $Runtimes[[string]$request.node]
        if ($runtime.Process.HasExited) { throw "PNX $($request.node) exited before console command." }
        $runtime.Process.StandardInput.WriteLine([string]$request.command)
        $runtime.Process.StandardInput.Flush()
        $reply = @{ runId = $RunId; id = $request.id; node = $request.node; submitted = $true }
        $destination = Join-Path $Directory ($request.id + '.reply.json')
        $temporary = $destination + '.new'
        [IO.File]::WriteAllText($temporary, ($reply | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        [IO.File]::Move($temporary, $destination)
        $null = $Handled.Add($file.Name)
    }
}
