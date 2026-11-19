$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe14.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$app = 'C:\Program Files\FlyFrogLLC\Happ'

Section 'qml files list (top level)'
(Get-ChildItem -LiteralPath (Join-Path $app 'qml') -Recurse -Filter '*.qml' -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName) | Out-File -Append -Encoding utf8 $out

Section 'qml lines mentioning dns / split / routing / tun'
(Select-String -Path (Join-Path $app 'qml\*.qml') -Pattern 'DNS|dns' -ErrorAction SilentlyContinue |
    Select-Object -First 40 | ForEach-Object { "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" } |
    Out-String -Width 300) | Out-File -Append -Encoding utf8 $out

Section 'qml lines mentioning split tunneling'
(Select-String -Path (Join-Path $app 'qml\*.qml') -Pattern 'split|Split|tunnel|Tunnel|routing|Routing' -ErrorAction SilentlyContinue |
    Select-Object -First 40 | ForEach-Object { "$($_.Filename):$($_.LineNumber): $($_.Line.Trim())" } |
    Out-String -Width 300) | Out-File -Append -Encoding utf8 $out

Section 'binary strings: DoH/https dns hints in app dir (top-level exes)'
(Get-ChildItem -LiteralPath $app -Filter '*.exe' -ErrorAction SilentlyContinue |
    Select-Object Name, Length | Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out

"WROTE: $out"
