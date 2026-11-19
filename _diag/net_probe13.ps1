$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe13.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'chrome Local State: profile info'
$ls = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data\Local State'
if (Test-Path -LiteralPath $ls) {
    try {
        $j = Get-Content -LiteralPath $ls -Raw | ConvertFrom-Json
        ("last_used=" + $j.profile.last_used + " last_active_profiles=" + ($j.profile.last_active_profiles -join ',')) |
            Out-File -Append -Encoding utf8 $out
        $j.profile.info_cache.PSObject.Properties | ForEach-Object {
            "$($_.Name) -> name=$($_.Value.name) active_time=$($_.Value.active_time)"
        } | Out-File -Append -Encoding utf8 $out
    } catch { "parse failed: $($_.Exception.Message)" | Out-File -Append -Encoding utf8 $out }
}

Section 'VPNLY state (raw grep in Profile 2 Preferences)'
$pf = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data\Profile 2\Preferences'
(Select-String -LiteralPath $pf -Pattern 'lneaocagcijjdpkcabeanfpdbmapcjjg' -ErrorAction SilentlyContinue |
    ForEach-Object { $_.Line.Substring(0, [Math]::Min(400, $_.Line.Length)) } | Out-String -Width 450) |
    Out-File -Append -Encoding utf8 $out

Section 'zapret service registry entry'
if (Test-Path 'HKLM:\SYSTEM\CurrentControlSet\Services\zapret') {
    (Get-ItemProperty 'HKLM:\SYSTEM\CurrentControlSet\Services\zapret' |
        Select-Object ImagePath, Start, Type, DisplayName, ObjectName, DelayedAutostart |
        Format-List | Out-String -Width 300) | Out-File -Append -Encoding utf8 $out
} else { 'no HKLM\SYSTEM\CurrentControlSet\Services\zapret' | Out-File -Append -Encoding utf8 $out }

Section 'remaining WinDivert driver files on disk'
(Get-ChildItem 'C:\WINDOWS\System32\drivers' -Filter '*divert*' -ErrorAction SilentlyContinue |
    Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize | Out-String) |
    Out-File -Append -Encoding utf8 $out

Section 'Happ ProxyBackup flags'
(Get-ItemProperty 'HKCU:\Software\Happ\OrganizationDefaults\ProxyBackup' -ErrorAction SilentlyContinue |
    Select-Object backup | Format-List | Out-String -Width 400) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
