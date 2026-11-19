# fix_plan.ps1 - fixes based on diagnostics. Run as Administrator. ASCII only on purpose.
# NOT executed automatically: confirm with the PC owner first.

$ErrorActionPreference = 'Stop'
$log = Join-Path $env:TEMP 'happ_fix_log.txt'

Write-Host '=== 1. Broken leftover service: zapret (DPI bypass in autostart) ===' -ForegroundColor Cyan
# Start=2 (auto), but its binary lives in %TEMP%\_MEI*\Tools\zapret_1.10.3 and is already gone,
# so the service fails with error 1067 on every boot.
sc.exe query zapret | Out-Host
sc.exe stop zapret 2>$null | Out-Host
sc.exe delete zapret | Out-Host

Write-Host '=== 2. Teredo (IPv6 tunnel that bypasses VPN) ===' -ForegroundColor Cyan
netsh interface teredo show state | Out-Host
netsh interface teredo set state disabled | Out-Host

Write-Host '=== 3. (optional) IPv6 binding on physical NIC ===' -ForegroundColor Cyan
# The ISP gives no global IPv6 now, but the binding is enabled = extra leak channel.
# Uncomment to remove IPv6 completely:
# Disable-NetAdapterBinding -Name 'Ethernet 2' -ComponentID ms_tcpip6

Write-Host '=== 4. Remove inherited proxy from Happ backup ===' -ForegroundColor Cyan
# On exit Happ restores the system proxy from HKCU\Software\Happ\OrganizationDefaults\ProxyBackup.
# It currently stores a third-party proxy 45.147.194.202:8934 (Greece, Hivelocity) - a second IP source.
$bp = 'HKCU:\Software\Happ\OrganizationDefaults\ProxyBackup'
if (Test-Path $bp) {
    $cur = (Get-ItemProperty $bp).backup
    reg.exe export 'HKCU\Software\Happ\OrganizationDefaults\ProxyBackup' "$env:TEMP\Happ_ProxyBackup.reg" /y 2>$null | Out-Null
    "OLD BACKUP: $cur" | Out-File -Append -Encoding utf8 $log
    $new = '{"autoConfigUrl":"","autoDiscoveryFlags":1,"flags":1,"isValid":true,"proxyBypass":"localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>","proxyServer":""}'
    Set-ItemProperty -Path $bp -Name backup -Value $new
    Write-Host 'ProxyBackup cleaned (45.147.194.202:8934 removed)' -ForegroundColor Green
}

Write-Host '=== 5. Current system proxy (should stay: socks=127.0.0.1:10808) ===' -ForegroundColor Cyan
Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' |
    Select-Object ProxyEnable, ProxyServer, ProxyOverride | Format-List | Out-Host

Write-Host '=== 6. Manual steps (UI) ===' -ForegroundColor Yellow
@'
  a) Chrome -> Settings -> Privacy and security -> "Use secure DNS" = On (Cloudflare/Google).
     This removes the DNS leak and EDNS Client Subnet 88.147.187.0/24.
  b) Chrome -> chrome://extensions -> profile "Profile 2" -> remove/disable
     "Free VPN & Proxy For Chrome - VPNLY" (id lneaocagcijjdpkcabeanfpdbmapcjjg, permissions proxy + <all_urls>).
  c) Happ -> select ONE server (now "Poland (Obhod#41-gRpc)": exit IP flips .126 <-> .127).
     Turn off auto / smart server selection if enabled.
  d) Happ -> DNS: enable DoH / secure DNS if available
     (now config has dns.servers = 8.8.8.8 with "detour": "direct").
'@ | Write-Host

Write-Host '=== 7. Verification ===' -ForegroundColor Cyan
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'verify.ps1')
