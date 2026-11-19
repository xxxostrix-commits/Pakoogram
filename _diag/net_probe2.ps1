$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe2.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'xray (13732) established connections'
(Get-NetTCPConnection -OwningProcess 13732 -ErrorAction SilentlyContinue |
    Select-Object State,LocalAddress,LocalPort,RemoteAddress,RemotePort |
    Sort-Object State | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'sing-box (8416) established connections'
(Get-NetTCPConnection -OwningProcess 8416 -ErrorAction SilentlyContinue |
    Select-Object State,LocalAddress,LocalPort,RemoteAddress,RemotePort |
    Sort-Object State | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'happd (12088) established connections'
(Get-NetTCPConnection -OwningProcess 12088 -ErrorAction SilentlyContinue |
    Select-Object State,LocalAddress,LocalPort,RemoteAddress,RemotePort |
    Sort-Object State | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'any connection to 172.104.157.114'
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $_.RemoteAddress -eq '172.104.157.114' } |
    Select-Object State,LocalAddress,LocalPort,RemoteAddress,RemotePort,OwningProcess |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'geo: default route'
(curl.exe -s --max-time 20 'http://ip-api.com/line/?fields=query,country,regionName,city,isp,org,as,timezone' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'geo: bound to Ethernet 2'
(curl.exe -s --max-time 20 --interface 192.168.0.176 'http://ip-api.com/line/?fields=query,country,regionName,city,isp,org,as,timezone' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'geo: via socks 10808'
(curl.exe -s --max-time 20 --socks5-hostname 127.0.0.1:10808 'http://ip-api.com/line/?fields=query,country,regionName,city,isp,org,as,timezone' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'geo: via socks 10809 (http inbound)'
(curl.exe -s --max-time 20 --proxy http://127.0.0.1:10809 'http://ip-api.com/line/?fields=query,country,isp' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'geo: VPN server 172.104.157.114'
(curl.exe -s --max-time 20 --interface 192.168.0.176 'http://ip-api.com/line/172.104.157.114?fields=query,country,city,isp,org,as' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'happd.log last 40 lines'
(Get-Content -LiteralPath 'C:\Users\Орел\AppData\Local\Happ\logs\happd.log' -Tail 40 -ErrorAction SilentlyContinue |
    Out-String -Width 400) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
