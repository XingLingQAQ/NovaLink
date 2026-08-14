$ports = Get-Content "D:\Project\NovaLink\.e2e\artifacts\runs\ports.json" | ConvertFrom-Json
$pw = "e2e-secret-password"
$sha = [System.Security.Cryptography.SHA256]::Create()
$pwHash = -join ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($pw)) | ForEach-Object { $_.ToString("x2") })

function WV([System.IO.MemoryStream]$s, [int]$value){ while($true){ if(($value -band (-bnot 0x7F)) -eq 0){ $s.WriteByte([byte]$value); return }; $s.WriteByte([byte](($value -band 0x7F) -bor 0x80)); $value = $value -shr 7 } }
function WS([System.IO.MemoryStream]$s, [string]$str){ $b=[System.Text.Encoding]::UTF8.GetBytes($str); WV $s $b.Length; $s.Write($b,0,$b.Length) }

$payload = New-Object System.IO.MemoryStream
WV $payload 1
WS $payload "E2E_Client"
WS $payload $pwHash
$payload.WriteByte([byte]0)
$reqId = [System.Guid]::NewGuid().ToByteArray()
$body = New-Object System.IO.MemoryStream
$body.WriteByte([byte]0x01)
$body.Write($reqId,0,16)
$pb = $payload.ToArray(); $body.Write($pb,0,$pb.Length)
$frame = New-Object System.IO.MemoryStream
$bb = $body.ToArray(); WV $frame $bb.Length; $frame.Write($bb,0,$bb.Length)
$fb = $frame.ToArray()

$c = New-Object System.Net.Sockets.TcpClient; $c.Connect("127.0.0.1", $ports.tcp)
$st = $c.GetStream(); $st.Write($fb,0,$fb.Length)

function ReadVInt($s){ $v=0;$p=0; while($true){ $b=$s.ReadByte(); if($b -lt 0){throw "EOF"}; $v = $v -bor (($b -band 0x7F) -shl $p); if(($b -band 0x80) -eq 0){return $v}; $p+=7 } }
$len = ReadVInt $st
$buf = New-Object 'byte[]' $len; $r=0; while($r -lt $len){ $x=$st.Read($buf,$r,$len-$r); if($x -le 0){break}; $r+=$x }
$c.Close()
Write-Output ("resp len=" + $len)
$hex = ($buf | ForEach-Object { $_.ToString("x2") }) -join ' '
Write-Output ("hex: " + $hex)
Write-Output ("packetId=0x{0:x2}" -f $buf[0])
Write-Output ("success byte=" + $buf[17])
