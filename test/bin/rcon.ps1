# Minimal Source RCON protocol client (https://wiki.vg/RCON).
# Usage: rcon.ps1 -Command "list"
param(
  [string]$Host_ = "127.0.0.1",
  [int]$Port = 54648,
  [string]$Password = "e2e-rcon-secret",
  [Parameter(Mandatory=$true)][string]$Command
)

function Write-LE([System.IO.MemoryStream]$s, [int]$v) { $b=[BitConverter]::GetBytes([int]$v); $s.Write($b,0,4) }
function Read-LE([System.IO.BinaryReader]$r) { $r.ReadUInt32() }

$client = New-Object System.Net.Sockets.TcpClient
$client.Connect($Host_, $Port)
$stream = $client.GetStream()
$reader = New-Object System.IO.BinaryReader $stream

# Auth packet: id=3, type=3, payload=password
function Send-Packet([int]$id, [int]$type, [string]$payload) {
  $body = New-Object System.IO.MemoryStream
  Write-LE $body $id
  Write-LE $body $type
  $b = [System.Text.Encoding]::UTF8.GetBytes($payload)
  $body.Write($b,0,$b.Length); $body.WriteByte(0); $body.WriteByte(0)
  $payloadBytes = $body.ToArray()
  $frame = New-Object System.IO.MemoryStream
  Write-LE $frame $payloadBytes.Length
  $frame.Write($payloadBytes,0,$payloadBytes.Length)
  $frameBytes = $frame.ToArray()
  $stream.Write($frameBytes,0,$frameBytes.Length)
}

function Read-Response {
  $len = $reader.ReadUInt32()
  $buf = $reader.ReadBytes($len)
  $ms = New-Object System.IO.MemoryStream -ArgumentList @(,$buf)
  $br = New-Object System.IO.BinaryReader $ms
  $id = $br.ReadUInt32()
  $type = $br.ReadUInt32()
  # rest is payload string + null + null
  $payloadLen = $len - 10
  $payload = if ($payloadLen -gt 0) { [System.Text.Encoding]::UTF8.GetString($buf, 8, $payloadLen).TrimEnd([char]0) } else { "" }
  return @{ id=$id; type=$type; payload=$payload }
}

# Auth
Send-Packet 3 3 $Password
$resp = Read-Response
if ($resp.id -ne 3 -or $resp.type -ne 2) { "AUTH FAILED id=$($resp.id) type=$($resp.type) payload=$($resp.payload)"; $client.Close(); return }

# Command (id=10, type=2 = exec)
Send-Packet 10 2 $Command
$resp = Read-Response
"RESP id=$($resp.id) type=$($resp.type)"
$resp.payload
$client.Close()
