$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe.txt'
'' | Out-File -Encoding utf8 $out

function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'curl via default route (TUN)'
(curl.exe -s --max-time 20 https://api.ipify.org 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'curl bound to Ethernet 2 (bypass tunnel)'
(curl.exe -s --max-time 20 --interface 192.168.0.176 https://api.ipify.org 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'curl via socks5 127.0.0.1:10808'
(curl.exe -s --max-time 20 --socks5-hostname 127.0.0.1:10808 https://api.ipify.org 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'listening local proxy ports'
(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -in 1080,1081,10808,10809,7890,7891,8080,8388,2080,2081,9050 } |
    Select-Object LocalAddress,LocalPort,OwningProcess,
        @{ n = 'Proc'; e = { (Get-Process -Id $_.OwningProcess -ErrorAction SilentlyContinue).ProcessName } } |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'winws / winws2 / other dpi processes'
(Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -match 'winws|zapret|goodbye|ciadpi|byedpi|nfqws|antifilter|Proximity' } |
    Select-Object Id,ProcessName,Path | Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'resolve gemini.google.com'
(Resolve-DnsName gemini.google.com -Type A -ErrorAction SilentlyContinue |
    Select-Object Name,Type,IPAddress,NameHost | Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'whatsmyip via ip-api (geo)'
(curl.exe -s --max-time 20 'http://ip-api.com/line/?fields=query,country,isp' 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'dns servers in use'
(Get-DnsClientServerAddress -AddressFamily IPv4 |
    Select-Object InterfaceIndex,InterfaceAlias,ServerAddresses |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
