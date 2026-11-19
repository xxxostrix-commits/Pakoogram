# verify.ps1 - leak re-check (read only). ASCII only on purpose.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1

$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_verify.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'default route'
(Get-NetRoute -DestinationPrefix '0.0.0.0/0' |
    Select-Object ifIndex, InterfaceAlias, NextHop, RouteMetric, InterfaceMetric |
    Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'egress IP x6 (must be the SAME VPN address)'
1..6 | ForEach-Object {
    $ip = (curl.exe -s --max-time 10 https://api.ipify.org 2>&1 | Out-String).Trim()
    ("{0} | {1}" -f (Get-Date -Format 'HH:mm:ss'), $ip) | Out-File -Append -Encoding utf8 $out
    Start-Sleep -Milliseconds 500
}

Section 'DNS: EDNS Client Subnet seen by Google (no ISP subnet expected)'
(Resolve-DnsName 'o-o.myaddr.l.google.com' -Type TXT -ErrorAction SilentlyContinue |
    Select-Object Name, Type, Strings | Format-List | Out-String -Width 250) |
    Out-File -Append -Encoding utf8 $out

Section 'DNS servers per interface'
(Get-DnsClientServerAddress -AddressFamily IPv4 |
    Select-Object InterfaceIndex, InterfaceAlias, ServerAddresses |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'Teredo state (expected: disabled)'
(netsh interface teredo show state 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'DPI bypass drivers / services'
(sc.exe query zapret 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out
(Get-CimInstance Win32_SystemDriver |
    Where-Object { $_.Name -match 'windivert|netfilter|nfq|tun' } |
    Select-Object Name, State, PathName | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'system proxy'
(Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' |
    Select-Object ProxyEnable, ProxyServer | Format-List | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome established remote endpoints'
$chromeIds = @((Get-Process chrome -ErrorAction SilentlyContinue).Id)
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $chromeIds -contains [int]$_.OwningProcess -and $_.State -eq 'Established' } |
    Select-Object RemoteAddress, RemotePort -Unique |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Write-Host "Report: $out"
Get-Content -LiteralPath $out
