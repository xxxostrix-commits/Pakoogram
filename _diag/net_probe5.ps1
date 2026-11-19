$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe5.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section '8 sequential egress checks via default route (timestamp | ip)'
1..8 | ForEach-Object {
    $ip = (curl.exe -s --max-time 10 https://api.ipify.org 2>&1 | Out-String).Trim()
    "{0} | {1}" -f (Get-Date -Format 'HH:mm:ss'), $ip | Out-File -Append -Encoding utf8 $out
    Start-Sleep -Milliseconds 700
}

Section 'DNS resolver IP as seen by Google authoritative (system resolver)'
(Resolve-DnsName 'o-o.myaddr.l.google.com' -Type TXT -ErrorAction SilentlyContinue |
    Select-Object Name,Type,Strings | Format-List | Out-String -Width 250) |
    Out-File -Append -Encoding utf8 $out

Section 'nslookup TXT o-o.myaddr (default resolver)'
(nslookup -type=txt o-o.myaddr.l.google.com 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'NRPT policy'
(Get-DnsClientNrptPolicy -ErrorAction SilentlyContinue | Format-List | Out-String -Width 250) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome remote connections (local address -> remote)'
$chromeIds = (Get-Process chrome -ErrorAction SilentlyContinue).Id
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $chromeIds -contains $_.OwningProcess -and $_.RemoteAddress -notin '127.0.0.1', '0.0.0.0', '::', '::1' } |
    Select-Object State, LocalAddress, RemoteAddress, RemotePort |
    Sort-Object LocalAddress, RemoteAddress | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome command lines'
(Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" |
    Select-Object ProcessId, CommandLine | Format-List | Out-String -Width 400) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome policies'
(Get-ItemProperty 'HKLM:\SOFTWARE\Policies\Google\Chrome' -ErrorAction SilentlyContinue |
    Select-Object ProxyMode, ProxyServer, ProxyPacUrl, DnsOverHttpsMode, DnsOverHttpsTemplates |
    Format-List | Out-String -Width 250) | Out-File -Append -Encoding utf8 $out

Section 'Proximity.exe on Desktop'
$p = 'C:\Users\Орел\Desktop\Proximity.exe'
if (Test-Path -LiteralPath $p) {
    (Get-Item -LiteralPath $p | Select-Object FullName, Length, LastWriteTime | Format-List | Out-String) |
        Out-File -Append -Encoding utf8 $out
    (Get-Item -LiteralPath $p).VersionInfo | Select-Object CompanyName, FileDescription, ProductName, FileVersion |
        Format-List | Out-String | Out-File -Append -Encoding utf8 $out
    (Get-AuthenticodeSignature -LiteralPath $p | Select-Object Status, SignerCertificate |
        Format-List | Out-String -Width 250) | Out-File -Append -Encoding utf8 $out
} else {
    'Proximity.exe not found' | Out-File -Append -Encoding utf8 $out
}

"WROTE: $out"
