# Pakoogram: генератор заглушек иконки приложения (фиолетовый фон + белая буква 'P').
# Запуск: powershell -NoProfile -ExecutionPolicy Bypass -File Tools\generate_pakoogram_icons.ps1
Add-Type -AssemblyName System.Drawing

$res = Join-Path (Split-Path $PSScriptRoot -Parent) 'TMessagesProj\src\main\res'

function New-PakoogramIcon {
    param(
        [int]$Size,
        [string]$Path,
        [switch]$Round,
        [switch]$TransparentBackground,
        [double]$LetterScale = 0.62
    )

    $bmp = New-Object System.Drawing.Bitmap($Size, $Size, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.Clear([System.Drawing.Color]::Transparent)

    if (-not $TransparentBackground) {
        $rect = New-Object System.Drawing.Rectangle(0, 0, $Size, $Size)
        $top = [System.Drawing.ColorTranslator]::FromHtml('#8B5CF6')
        $bottom = [System.Drawing.ColorTranslator]::FromHtml('#6D28D9')
        $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush($rect, $top, $bottom, 90)
        $shape = New-Object System.Drawing.Drawing2D.GraphicsPath
        if ($Round) {
            $shape.AddEllipse($rect)
        } else {
            $radius = [int]($Size * 0.22)
            $d = $radius * 2
            $shape.AddArc(0, 0, $d, $d, 180, 90)
            $shape.AddArc($Size - $d, 0, $d, $d, 270, 90)
            $shape.AddArc($Size - $d, $Size - $d, $d, $d, 0, 90)
            $shape.AddArc(0, $Size - $d, $d, $d, 90, 90)
            $shape.CloseFigure()
        }
        $g.FillPath($brush, $shape)
        $brush.Dispose()
        $shape.Dispose()
    }

    $font = New-Object System.Drawing.Font('Segoe UI', [float]($Size * $LetterScale), [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
    $format = New-Object System.Drawing.StringFormat
    $format.Alignment = [System.Drawing.StringAlignment]::Center
    $format.LineAlignment = [System.Drawing.StringAlignment]::Center
    $textBrush = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
    $layout = New-Object System.Drawing.RectangleF(0, 0, [float]$Size, [float]$Size)
    $g.DrawString('P', $font, $textBrush, $layout, $format)

    $textBrush.Dispose()
    $format.Dispose()
    $font.Dispose()
    $g.Dispose()

    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

$densities = @{
    'mdpi'    = 48
    'hdpi'    = 72
    'xhdpi'   = 96
    'xxhdpi'  = 144
    'xxxhdpi' = 192
}

foreach ($density in $densities.Keys) {
    $size = $densities[$density]
    $dir = Join-Path $res "mipmap-$density"
    New-PakoogramIcon -Size $size -Path (Join-Path $dir 'ic_launcher.png') -LetterScale 0.62
    New-PakoogramIcon -Size $size -Path (Join-Path $dir 'ic_launcher_round.png') -Round -LetterScale 0.62
    # 108dp foreground для adaptive-icon (буква внутри safe zone)
    $foregroundSize = [int]($size * 2.25)
    New-PakoogramIcon -Size $foregroundSize -Path (Join-Path $dir 'ic_launcher_foreground.png') -TransparentBackground -LetterScale 0.42
    "mipmap-$density : launcher=${size}px, foreground=${foregroundSize}px"
}
