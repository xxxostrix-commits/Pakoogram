$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe9.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$chromeRoot = 'C:\Users\Орел\AppData\Local\Google\Chrome\User Data'

Section 'chrome user data top-level dirs'
(Get-ChildItem -LiteralPath $chromeRoot -Directory -ErrorAction SilentlyContinue |
    Select-Object Name, LastWriteTime | Format-Table -AutoSize | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome profiles that have Preferences'
(Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 1 -Filter 'Preferences' -Force -ErrorAction SilentlyContinue |
    Select-Object FullName, Length | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome extensions folders'
(Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 2 -Directory -Filter 'Extensions' -Force -ErrorAction SilentlyContinue |
    ForEach-Object {
        $_.FullName
        Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { "   ext-id: $($_.Name)" }
    } | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'chrome proxy + secure dns settings per profile'
(Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 1 -Filter 'Preferences' -Force -ErrorAction SilentlyContinue | ForEach-Object {
    "--- $($_.FullName) ---" | Out-File -Append -Encoding utf8 $out
    try {
        $j = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
        ("proxy: " + ($j.proxy | ConvertTo-Json -Depth 5 -Compress)) | Out-File -Append -Encoding utf8 $out
        ("secure_dns: " + ($j.dns_over_https | ConvertTo-Json -Depth 5 -Compress)) | Out-File -Append -Encoding utf8 $out
    } catch {
        "parse failed: $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $out
    }
})

Section 'yandex browser proxy settings'
$yRoot = 'C:\Users\Орел\AppData\Local\Yandex\YandexBrowser\User Data\Default\Preferences'
if (Test-Path -LiteralPath $yRoot) {
    try {
        $yj = Get-Content -LiteralPath $yRoot -Raw | ConvertFrom-Json
        ("proxy: " + ($yj.proxy | ConvertTo-Json -Depth 5 -Compress)) | Out-File -Append -Encoding utf8 $out
    } catch { "parse failed" | Out-File -Append -Encoding utf8 $out }
} else { 'yandex prefs not found' | Out-File -Append -Encoding utf8 $out }

Section 'opera gx vpn state'
$operaVpn = Get-ChildItem 'C:\Users\Орел\AppData\Roaming\Opera Software' -Recurse -Depth 3 -Filter '*VPN*' -ErrorAction SilentlyContinue |
    Select-Object FullName, LastWriteTime
($operaVpn | Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'all established connections grouped by local address'
(Get-NetTCPConnection -State Established -ErrorAction SilentlyContinue |
    Where-Object { $_.RemoteAddress -notlike '127.*' } |
    Select-Object LocalAddress, RemoteAddress, RemotePort |
    Group-Object LocalAddress |
    Select-Object Name, Count | Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
