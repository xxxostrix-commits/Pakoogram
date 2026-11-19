$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe10.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$chromeRoot = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data'

Section "chrome root exists: $chromeRoot"
(Test-Path -LiteralPath $chromeRoot) | Out-File -Append -Encoding utf8 $out

Section 'chrome top-level dirs'
(Get-ChildItem -LiteralPath $chromeRoot -Directory -ErrorAction SilentlyContinue |
    Select-Object Name, LastWriteTime | Format-Table -AutoSize | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome Preferences files'
(Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 1 -Filter 'Preferences' -Force -ErrorAction SilentlyContinue |
    Select-Object FullName, Length | Format-Table -AutoSize | Out-String -Width 220) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome extensions (id | name | permissions)'
Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 3 -Directory -Filter 'Extensions' -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $profileDir = $_.Parent.FullName
    Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        $extId = $_.Name
        $verDir = Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $verDir) { return }
        $mf = Join-Path $verDir.FullName 'manifest.json'
        if (-not (Test-Path -LiteralPath $mf)) { return }
        $raw = Get-Content -LiteralPath $mf -Raw -ErrorAction SilentlyContinue
        $nm = ''
        $perm = ''
        try {
            $j = $raw | ConvertFrom-Json
            $nm = $j.name
            $perm = (($j.permissions + $j.host_permissions) -join ',')
        } catch { $nm = 'parse-error' }
        "$extId | $nm | $perm" | Out-File -Append -Encoding utf8 $out
    }
}

Section 'chrome proxy / secure dns per profile'
Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 1 -Filter 'Preferences' -Force -ErrorAction SilentlyContinue | ForEach-Object {
    "--- $($_.FullName) ---" | Out-File -Append -Encoding utf8 $out
    try {
        $j = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
        ("proxy: " + ($j.proxy | ConvertTo-Json -Depth 6 -Compress)) | Out-File -Append -Encoding utf8 $out
        ("secure_dns: " + ($j.dns_over_https | ConvertTo-Json -Depth 6 -Compress)) | Out-File -Append -Encoding utf8 $out
    } catch { "parse failed: $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $out }
}

Section 'happd.log last 30 lines'
$log = Join-Path $env:LOCALAPPDATA 'Happ\logs\happd.log'
if (Test-Path -LiteralPath $log) {
    (Get-Content -LiteralPath $log -Tail 30 | Out-String -Width 400) | Out-File -Append -Encoding utf8 $out
    Section 'happd.log lines with dns/direct/split/route'
    (Select-String -LiteralPath $log -Pattern 'dns|direct|split|route|antifilter' -ErrorAction SilentlyContinue |
        Select-Object -Last 25 | ForEach-Object { $_.Line } | Out-String -Width 400) |
        Out-File -Append -Encoding utf8 $out
} else {
    "log not found: $log" | Out-File -Append -Encoding utf8 $out
}

"WROTE: $out"
