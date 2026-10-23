# Wakes a TV from standby with a Wake-on-LAN magic packet.
# Needs network_wol=1 on the TV (settings put global network_wol 1).
param([Parameter(Mandatory)][string]$Mac, [string]$Broadcast = '255.255.255.255', [string]$Ip = '')
$bytes = $Mac -split '[:-]' | ForEach-Object { [Convert]::ToByte($_, 16) }
$packet = [byte[]](@(0xFF) * 6 + ($bytes * 16))
$udp = New-Object Net.Sockets.UdpClient
$udp.EnableBroadcast = $true
foreach ($port in 7, 9) {
    [void]$udp.Send($packet, $packet.Length, $Broadcast, $port)
    if ($Ip) { [void]$udp.Send($packet, $packet.Length, $Ip, $port) }
}
$udp.Close()
"Magic packet sent to $Mac"
