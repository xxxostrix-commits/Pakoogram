$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe12.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'chrome main processes and profile'
(Get-CimInstance Win32_Process -Filter "Name='chrome.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -notmatch '--type=' } |
    Select-Object ProcessId, CommandLine | Format-List | Out-String -Width 400) |
    Out-File -Append -Encoding utf8 $out

Section 'chrome connections (all states)'
$chromeIds = @((Get-Process chrome -ErrorAction SilentlyContinue).Id)
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $chromeIds -contains [int]$_.OwningProcess } |
    Select-Object State, LocalAddress, LocalPort, RemoteAddress, RemotePort, OwningProcess |
    Sort-Object RemoteAddress | Format-Table -AutoSize | Out-String -Width 220) |
    Out-File -Append -Encoding utf8 $out

Section 'distinct chrome remote endpoints'
(Get-NetTCPConnection -ErrorAction SilentlyContinue |
    Where-Object { $chromeIds -contains [int]$_.OwningProcess -and $_.RemoteAddress -ne '0.0.0.0' } |
    Select-Object RemoteAddress, RemotePort -Unique |
    Format-Table -AutoSize | Out-String -Width 200) | Out-File -Append -Encoding utf8 $out

Section 'VPNLY storage strings (Profile 2)'
$p2 = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data\Profile 2'
$id = 'lneaocagcijjdpkcabeanfpdbmapcjjg'
foreach ($folder in 'Local Extension Settings', 'Extension State', 'Sync Extension Settings', 'Extension Rules') {
    $p = Join-Path $p2 "$folder\$id"
    if (-not (Test-Path -LiteralPath $p)) { "missing: $folder\$id" | Out-File -Append -Encoding utf8 $out; continue }
    Get-ChildItem -LiteralPath $p -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $hits = [regex]::Matches($text, '[A-Za-z0-9._-]*\.[A-Za-z]{2,6}(:[0-9]{2,5})?') |
            ForEach-Object { $_.Value } | Select-Object -Unique -First 40
        "$folder/$($_.Name): $($hits -join ' | ')" | Out-File -Append -Encoding utf8 $out
        $ips = [regex]::Matches($text, '[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]{2,5})?') |
            ForEach-Object { $_.Value } | Select-Object -Unique -First 40
        if ($ips) { "  ips: $($ips -join ' , ')" | Out-File -Append -Encoding utf8 $out }
    }
}

Section 'VPNLY extension state in Profile 2 prefs'
$pf = Join-Path $p2 'Preferences'
try {
    $j = Get-Content -LiteralPath $pf -Raw | ConvertFrom-Json
    $e = $j.extensions.settings.$id
    if ($e) {
        "state=$($e.state) location=$($e.location) disable_reasons=$($e.disable_reasons) from_webstore=$($e.from_webstore)"
        "incognito=$($e.incognito) active_permissions=$($e.active_permissions | ConvertTo-Json -Compress)"
    } else { 'no settings entry for VPNLY' }
} catch { "parse failed: $($_.Exception.Message)" }

Section 'secure dns setting raw (Profile 2)'
(Select-String -LiteralPath $pf -Pattern 'dns_over_https' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.Line.Substring(0, [Math]::Min(300, $_.Line.Length)) } | Out-String -Width 350) |
    Out-File -Append -Encoding utf8 $out

"WROTE: $out"
