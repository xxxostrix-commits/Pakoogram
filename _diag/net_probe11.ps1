$ErrorActionPreference = 'Continue'
$out = Join-Path $env:TEMP 'happ_probe11.txt'
'' | Out-File -Encoding utf8 $out
function Section($t) { "=== $t ===" | Out-File -Append -Encoding utf8 $out }

$chromeRoot = Join-Path $env:LOCALAPPDATA 'Google\Chrome\User Data'
$ids = 'lneaocagcijjdpkcabeanfpdbmapcjjg', 'ebgmlfdcgihhfheckfdmhnmedjigogmm', 'ahkjpbeeocnddjkakilopmfdlnjdpcdm'

foreach ($id in $ids) {
    Section "extension $id"
    Get-ChildItem -LiteralPath $chromeRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -in 'Default', 'Profile 2', 'Profile 3', 'Profile 1' } | ForEach-Object {
            $prof = $_.Name
            $extDir = Join-Path $_.FullName "Extensions\$id"
            if (-not (Test-Path -LiteralPath $extDir)) { return }
            Get-ChildItem -LiteralPath $extDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $mf = Join-Path $_.FullName 'manifest.json'
                if (-not (Test-Path -LiteralPath $mf)) { return }
                $j = Get-Content -LiteralPath $mf -Raw | ConvertFrom-Json
                $loc = $j.default_locale
                $nm = $j.name
                if ($nm -like '__MSG_*') {
                    $key = $nm -replace '__MSG_', '' -replace '__', ''
                    $msgFile = Join-Path $_.FullName "_locales\$loc\messages.json"
                    if (Test-Path -LiteralPath $msgFile) {
                        $mj = Get-Content -LiteralPath $msgFile -Raw | ConvertFrom-Json
                        $val = $mj.$key
                        if ($val) { $nm = $val.message }
                    }
                }
                "profile=$prof version=$($_.Name) name=$nm"
                "  path=$($_.FullName)"
                "  update_url=$($j.update_url) homepage=$($j.homepage_url)"
                "  permissions=$((($j.permissions) -join ','))"
                "  host_permissions=$((($j.host_permissions) -join ','))"
                if ($j.background) { "  background=$($j.background | ConvertTo-Json -Compress)" }
            } | Out-File -Append -Encoding utf8 $out
        }
}

Section 'Preferences: extension states (id, state, location, from_webstore)'
Get-ChildItem -LiteralPath $chromeRoot -Recurse -Depth 1 -Filter 'Preferences' -Force -ErrorAction SilentlyContinue | ForEach-Object {
    $prof = Split-Path $_.DirectoryName -Leaf
    try {
        $j = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json
        $settings = $j.extensions.settings
        if ($settings) {
            $settings.PSObject.Properties | ForEach-Object {
                $sid = $_.Name
                if ($ids -contains $sid) {
                    $v = $_.Value
                    "profile=$prof id=$sid state=$($v.state) location=$($v.location) from_webstore=$($v.from_webstore) path=$($v.path)"
                }
            } | Out-File -Append -Encoding utf8 $out
        }
    } catch { "profile=$prof parse failed" | Out-File -Append -Encoding utf8 $out }
}

Section 'IPv4 strings found in proxy-extension storage/state'
foreach ($folder in 'Local Extension Settings', 'Extension State', 'Extension Rules') {
    $p = Join-Path $chromeRoot "Default\$folder\lneaocagcijjdpkcabeanfpdbmapcjjg"
    if (-not (Test-Path -LiteralPath $p)) { continue }
    Get-ChildItem -LiteralPath $p -File -Recurse -ErrorAction SilentlyContinue | ForEach-Object {
        $bytes = [System.IO.File]::ReadAllBytes($_.FullName)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        [regex]::Matches($text, '[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]{2,5})?') |
            ForEach-Object { $_.Value } | Select-Object -Unique |
            ForEach-Object { "$folder/$($_.Name)" } | Out-Null
        $hits = [regex]::Matches($text, '[0-9]{1,3}(\.[0-9]{1,3}){3}(:[0-9]{2,5})?') |
            ForEach-Object { $_.Value } | Select-Object -Unique
        if ($hits) { "$folder -> $($hits -join ' , ')" | Out-File -Append -Encoding utf8 $out }
    }
}

"WROTE: $out"
