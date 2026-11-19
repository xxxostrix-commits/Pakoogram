$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe7.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

Section 'HKCU\Software\Happ values'
Get-ChildItem 'HKCU:\Software\Happ' -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
    "--- KEY: $($_.Name) ---" | Out-File -Append -Encoding utf8 $out
    (Get-ItemProperty -LiteralPath $_.PSPath -ErrorAction SilentlyContinue |
        Select-Object * -ExcludeProperty PS* | Format-List | Out-String -Width 300) |
        Out-File -Append -Encoding utf8 $out
}

Section 'happd.log last 25 meaningful lines'
(Get-Content -LiteralPath 'C:\Users\Орел\AppData\Local\Happ\logs\happd.log' -Tail 25 -ErrorAction SilentlyContinue |
    Out-String -Width 400) | Out-File -Append -Encoding utf8 $out

Section 'happd.log lines mentioning dns/split/direct'
(Select-String -LiteralPath 'C:\Users\Орел\AppData\Local\Happ\logs\happd.log' -Pattern 'dns|split|direct|antifilter|bypass' -ErrorAction SilentlyContinue |
    Select-Object -Last 20 | ForEach-Object { $_.Line } | Out-String -Width 400) |
    Out-File -Append -Encoding utf8 $out

"WROTE: $out"
