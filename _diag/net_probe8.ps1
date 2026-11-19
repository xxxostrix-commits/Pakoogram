$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe8.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'geo 45.147.194.202 (proxies in Happ backup)'
(curl.exe -s --max-time 10 'http://ip-api.com/line/45.147.194.202?fields=query,country,city,isp,org,as' 2>&1 | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'current connections to 45.147.194.202'
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $_.RemoteAddress -eq '45.147.194.202' } |
    Select-Object State, LocalAddress, LocalPort, RemoteAddress, RemotePort, OwningProcess |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'chrome extensions with proxy/webRequest permission'
$extRoot = 'C:\Users\Орел\AppData\Local\Google\Chrome\User Data'
$manifests = Get-ChildItem -Path $extRoot -Recurse -Depth 5 -Filter 'manifest.json' -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match '\\Extensions\\|\\Unpacked\\' }
foreach ($m in $manifests) {
    $raw = Get-Content -LiteralPath $m.FullName -Raw -ErrorAction SilentlyContinue
    if (-not $raw) { continue }
    if ($raw -notmatch '"proxy"|webRequest|declarativeNetRequest') { continue }
    $name = ''
    $perms = ''
    try {
        $j = $raw | ConvertFrom-Json
        $name = $j.name
        $perms = ($j.permissions + $j.host_permissions) -join ','
    } catch {
        $name = 'parse-error'
        $perms = ($raw -replace '\s+', ' ')
    }
    $id = Split-Path (Split-Path $m.FullName -Parent) -Leaf
    "$id | $name | $perms" | Out-File -Append -Encoding utf8 $out
}

Section 'chrome Preferences mentions of 45.147.194.202'
$pref = Join-Path $extRoot 'Default\Preferences'
if (Test-Path -LiteralPath $pref) {
    (Select-String -LiteralPath $pref -Pattern '45\.147\.194\.202' -AllMatches -ErrorAction SilentlyContinue |
        Select-Object -First 5 | ForEach-Object { $_.Line.Substring(0, [Math]::Min(600, $_.Line.Length)) } | Out-String -Width 700) |
        Out-File -Append -Encoding utf8 $out
} else {
    'Preferences not found' | Out-File -Append -Encoding utf8 $out
}

Section 'chrome Preferences proxy section'
if (Test-Path -LiteralPath $pref) {
    try {
        $pj = Get-Content -LiteralPath $pref -Raw | ConvertFrom-Json
        ($pj.proxy | ConvertTo-Json -Depth 5 | Out-String) | Out-File -Append -Encoding utf8 $out
    } catch {
        "proxy section parse failed: $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $out
    }
}

"WROTE: $out"
