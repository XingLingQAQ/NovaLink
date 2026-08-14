# Minimal NovaProtocol handshake client over raw TCP.
# Frame: [Len VarInt][PacketID byte][RequestID 16 bytes][VarInt ver][str clientId][str pwHash][byte platform]
# Response: [Len VarInt][PacketID byte=0x02][RequestID 16 bytes][bool success][str errorCode][str message]
param(
  [int]$TcpPort = 27905,
  [string]$ClientId = "E2E_Client",
  [string]$Password = "e2e-secret-password",
  [int]$ProtocolVersion = 1,
  [int]$PlatformId = 0  # BUKKIT
)

function Write-VarInt([System.IO.MemoryStream]$s, [int]$value) {
  while ($true) {
    if (($value -band (-bnot 0x7F)) -eq 0) { $s.WriteByte([byte]$value); return }
    $s.WriteByte([byte](($value -band 0x7F) -bor 0x80))
    $value = $value -shr 7
  }
}

function Write-String([System.IO.MemoryStream]$s, [string]$str) {
  $bytes = [System.Text.Encoding]::UTF8.GetBytes($str)
  Write-VarInt $s $bytes.Length
  $s.Write($bytes, 0, $bytes.Length)
}

function Read-VarIntFromStream([System.IO.Stream]$s) {
  $value = 0; $pos = 0
  while ($true) {
    $b = $s.ReadByte()
    if ($b -lt 0) { throw "EOF reading VarInt" }
    $value = $value -bor (($b -band 0x7F) -shl $pos)
    if (($b -band 0x80) -eq 0) { return $value }
    $pos += 7
    if ($pos -ge 32) { throw "VarInt too big" }
  }
}

function Read-StringFromStream([System.IO.Stream]$s) {
  $len = Read-VarIntFromStream $s
  $buf = New-Object byte[] $len
  $read = 0
  while ($read -lt $len) {
    $r = $s.Read($buf, $read, $len - $read)
    if ($r -le 0) { throw "EOF reading string" }
    $read += $r
  }
  return [System.Text.Encoding]::UTF8.GetString($buf)
}

# Compute SHA-256 of password
$sha = [System.Security.Cryptography.SHA256]::Create()
$hashBytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($Password))
$pwHash = -join ($hashBytes | ForEach-Object { $_.ToString("x2") })

# Build payload
$payload = New-Object System.IO.MemoryStream
Write-VarInt $payload $ProtocolVersion
Write-String $payload $ClientId
Write-String $payload $pwHash
$payload.WriteByte([byte]$PlatformId)

# Build packet body: PacketID(1) + RequestID(16) + payload
$requestId = [System.Guid]::NewGuid().ToByteArray()  # 16 bytes
$body = New-Object System.IO.MemoryStream
$body.WriteByte([byte]0x01)  # HANDSHAKE
$body.Write($requestId, 0, 16)
$payloadBytes = $payload.ToArray()
$body.Write($payloadBytes, 0, $payloadBytes.Length)

# Frame: length VarInt + body
$frame = New-Object System.IO.MemoryStream
$bodyBytes = $body.ToArray()
Write-VarInt $frame $bodyBytes.Length
$frame.Write($bodyBytes, 0, $bodyBytes.Length)
$frameBytes = $frame.ToArray()

Write-Output ("Connecting to 127.0.0.1:" + $TcpPort + " ...")
$client = New-Object System.Net.Sockets.TcpClient
$client.Connect("127.0.0.1", $TcpPort)
Write-Output ("Connected=" + $client.Connected)
$stream = $client.GetStream()
$stream.Write($frameBytes, 0, $frameBytes.Length)
Write-Output ("Sent handshake frame, " + $frameBytes.Length + " bytes")
Write-Output ("clientId=" + $ClientId + " pwHash=" + $pwHash.Substring(0,16) + "...")

# Read response frame
$respLen = Read-VarIntFromStream $stream
Write-Output ("Response frame length=" + $respLen)
$respBuf = New-Object byte[] $respLen
$read = 0
while ($read -lt $respLen) {
  $r = $stream.Read($respBuf, $read, $respLen - $read)
  if ($r -le 0) { throw "EOF reading response body" }
  $read += $r
}
$ms = New-Object System.IO.MemoryStream -ArgumentList @(,$respBuf)
$reader = New-Object System.IO.BinaryReader $ms
$packetId = $reader.ReadByte()
$respReqId = $reader.ReadBytes(16)
$success = $reader.ReadByte()
$errCode = Read-VarIntFromStream $ms
$errCodeStr = Read-StringFromStream $ms
$msg = Read-StringFromStream $ms

Write-Output ("--- HandshakeResponse ---")
Write-Output ("packetId=0x{0:x2}" -f $packetId)
Write-Output ("success=" + $success)
Write-Output ("errorCode=" + $errCodeStr)
Write-Output ("message=" + $msg)
Write-Output ("respReqId=" + ([System.Guid]::new($respReqId)).ToString())

$client.Close()
