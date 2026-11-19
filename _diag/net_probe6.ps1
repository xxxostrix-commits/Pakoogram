$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe6.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)

Section 'identity / elevation'
"user: $($id.Name)" | Out-File -Append -Encoding utf8 $out
"elevated: $($pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))" | Out-File -Append -Encoding utf8 $out

Section 'registry keys mentioning Happ/FlyFrog/Proxy (HKCU)'
(Get-ChildItem 'HKCU:\Software' -ErrorAction SilentlyContinue |
    Where-Object { $_.PSChildName -match 'Happ|FlyFrog' } |
    Select-Object Name | Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out
(Get-ChildItem 'HKCU:\Software' -Recurse -Depth 2 -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match 'Happ|FlyFrog' } |
    Select-Object -First 40 Name | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'registry keys mentioning Happ (HKLM)'
(Get-ChildItem 'HKLM:\SOFTWARE' -ErrorAction SilentlyContinue |
    Where-Object { $_.PSChildName -match 'Happ|FlyFrog' } |
    Select-Object Name | Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out
(Get-ChildItem 'HKLM:\SOFTWARE\WOW6432Node' -ErrorAction SilentlyContinue |
    Where-Object { $_.PSChildName -match 'Happ|FlyFrog' } |
    Select-Object Name | Format-Table -AutoSize | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'HappService config'
(sc.exe qc HappService 2>&1 | Out-String) | Out-File -Append -Encoding utf8 $out

Section 'Happ settings files'
(Get-ChildItem "$env:LOCALAPPDATA", "$env:APPDATA" -Recurse -Depth 2 -Include '*.ini', '*.conf', 'settings*' -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match 'Happ|FlyFrog' } |
    Select-Object FullName, Length, LastWriteTime | Format-Table -AutoSize | Out-String -Width 200) |
    Out-File -Append -Encoding utf8 $out

Section 'happd.log lines with dns / split / direct / route (last 200 matches)'
(Select-String -LiteralPath 'C:\Users\Орел\AppData\Local\Happ\logs\happd.log' -Pattern 'dns|split|direct|route|antifilter|bypass' -ErrorAction SilentlyContinue |
    Select-Object -Last 40 | ForEach-Object { $_.Line } | Out-String -Width 400) |
    Out-File -Append -Encoding utf8 $out

"WROTE: $out"
