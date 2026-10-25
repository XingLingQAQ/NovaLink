# ============================================================================
# hold-stdin-run.ps1 -- run a child with its stdin pipe HELD OPEN.
#
# Why this exists
# ---------------
# Endstone's console reader treats stdin EOF as a shutdown request
# (src/endstone/runtime/bedrock_hooks/console_input_reader.cpp):
#
#     auto line = EndstoneConsole::getInstance().readLine("> ");
#     if (line.has_value()) {
#         if (!line->empty()) { console_input_.try_enqueue(line.value()); ... }
#     }
#     else {
#         // Ctrl + C, EOF
#         console_input_.try_enqueue("stop");
#         break;
#     }
#
# readLine() reports EOF in BOTH of its branches (src/endstone/core/console/
# console.cpp): replxx's input() returning nullptr when
# ENDSTONE_USE_INTERACTIVE_CONSOLE is truthy, and std::getline followed by
# !std::cin.good() otherwise. So the replxx console is NOT the trigger and
# --no-interactive does NOT avoid it. EndstoneServer::setLevel() calls
# startEndstone() unconditionally, so this thread runs on every startup.
#
# Start-Process cannot express "stdin open but unused". It either lets the child
# inherit the parent's stdin -- already at EOF in CI -- or redirects from a FILE,
# and a file reads EOF at its end. Either way the child sees EOF within
# milliseconds of starting, enqueues "stop", and shuts down right after
# "Server started.":
#
#   [04:29:08 INFO]: Server started.
#   [04:29:08 INFO]: [Novachat] Enabling novachat v1.0.0
#   [04:29:08 INFO]: Server stop requested.
#
# That is CI run 40161731209 (and 40151105460, and 40106019993 where the plugin
# was rejected outright and never ran), so the stop never depended on our plugin.
#
# This wrapper starts the child through .NET's Process class with
# RedirectStandardInput = $true and the write end deliberately never written to
# and never closed. readLine() then blocks, no "stop" is enqueued, and the server
# keeps running until the caller kills it.
#
# stdout/stderr are deliberately NOT redirected here, so the child inherits this
# process's handles and the caller's -RedirectStandardOutput/-RedirectStandardError
# still receive the server log.
#
# Configuration comes from environment variables, not parameters. Start-Process
# joins -ArgumentList elements with spaces and quotes none of them, so a
# parameter whose value contains a space (a working directory, a path, or the
# child's own argv) would silently split into several tokens. Environment
# variables carry arbitrary text through that layer intact. The wrapper is
# therefore always invoked with a fixed, space-free argument list.
#
#   NOVACHAT_HOLD_STDIN_EXE      (required) executable to run
#   NOVACHAT_HOLD_STDIN_ARGS     (required) its command line, as one string
#   NOVACHAT_HOLD_STDIN_CWD      (optional) working directory
#   NOVACHAT_HOLD_STDIN_PIDFILE  (optional) file to receive the CHILD's pid
# ============================================================================
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

$exe = [Environment]::GetEnvironmentVariable("NOVACHAT_HOLD_STDIN_EXE")
if ([string]::IsNullOrEmpty($exe)) {
    throw "NOVACHAT_HOLD_STDIN_EXE is not set; it must name the executable to run."
}
$argumentLine = [Environment]::GetEnvironmentVariable("NOVACHAT_HOLD_STDIN_ARGS")
if ($null -eq $argumentLine) {
    throw "NOVACHAT_HOLD_STDIN_ARGS is not set; it must carry the child's command line."
}
$cwd = [Environment]::GetEnvironmentVariable("NOVACHAT_HOLD_STDIN_CWD")
$pidFile = [Environment]::GetEnvironmentVariable("NOVACHAT_HOLD_STDIN_PIDFILE")

$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $exe
$psi.Arguments = $argumentLine
$psi.UseShellExecute = $false
# The entire point of this wrapper: the child must never see EOF on stdin.
$psi.RedirectStandardInput = $true
$psi.CreateNoWindow = $true
if ($cwd) { $psi.WorkingDirectory = $cwd }

$proc = New-Object System.Diagnostics.Process
$proc.StartInfo = $psi
$null = $proc.Start()

# Publish the child pid before blocking, so a caller that times out can still
# find and stop the real server instead of orphaning it.
if ($pidFile) {
    try { Set-Content -Path $pidFile -Value $proc.Id -Encoding ASCII } catch { }
}

# Hold the write end for the child's whole lifetime: never write to it, never
# close it. Closing it here is precisely what would hand the child the EOF that
# stops the server.
$stdinHandle = $proc.StandardInput

try {
    $proc.WaitForExit()
}
finally {
    if ($null -ne $stdinHandle) {
        try { $stdinHandle.Dispose() } catch { }
    }
}

exit $proc.ExitCode
