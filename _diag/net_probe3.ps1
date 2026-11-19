$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe3.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$ips = '31.76.116.130', '31.76.116.126', '88.147.187.34', '172.104.157.114', '91.218.230.227', '37.143.15.31', '95.163.218.220'
foreach ($ip in $ips) {
    Section "geo $ip"
    (curl.exe -s --max-time 15 --interface 192.168.0.176 "http://ip-api.com/line/$ip`?fields=query,country,city,isp,org,as" 2>&1 | Out-String) |
        Out-File -Append -Encoding utf8 $out
}

Section 'running processes outside Windows folder'
(Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.Path -and $_.Path -notlike 'C:\WINDOWS\*' } |
    Select-Object Id, ProcessName, Path |
    Sort-Object ProcessName | Format-Table -AutoSize | Out-String -Width 250) |
    Out-File -Append -Encoding utf8 $out

Section 'tcp reachability checks'
foreach ($t in @(@('172.104.157.114', 443), @('172.104.157.114', 80), @('91.218.230.227', 443), @('37.143.15.31', 443))) {
    $r = Test-NetConnection -ComputerName $t[0] -Port $t[1] -WarningAction SilentlyContinue -InformationLevel Quiet
    "$($t[0]):$($t[1]) reachable=$r" | Out-File -Append -Encoding utf8 $out
}

Section 'hosts file'
(Get-Content -LiteralPath 'C:\WINDOWS\system32\drivers\etc\hosts' -ErrorAction SilentlyContinue |
    Where-Object { $_ -and $_ -notmatch '^\s*#' } | Out-String -Width 250) |
    Out-File -Append -Encoding utf8 $out

Section 'Netsh WFP filters mentioning 10808/172.104'
(netsh wfp show filters file=- 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 (Join-Path $env:TEMP 'happ_wfp.xml')

Section 'Proximity / other vpn apps installed (uninstall keys)'
(Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*', 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\*' -ErrorAction SilentlyContinue |
    Where-Object { $_.DisplayName -match 'Happ|Proximity|zapret|GoodbyeDPI|ByeDPI|v2ray|Nekoray|Clash|Outline|WireGuard|OpenVPN|AdGuard|Psiphon' } |
    Select-Object DisplayName, DisplayVersion, InstallLocation |
    Format-Table -AutoSize | Out-String -Width 250) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
