param([Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
[IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
# Original geometric examples. No reference pictures, logos, signatures or fonts.
foreach ($letter in @('a', 'b', 'c')) {
    $bitmap = [Drawing.Bitmap]::new(256, 256)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $colors = @{ a = '#3D7A80'; b = '#D59659'; c = '#596886' }
    $brush = [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($colors[$letter]))
    $pen = [Drawing.Pen]::new([Drawing.Color]::White, 12)
    try {
        $graphics.Clear([Drawing.Color]::Transparent)
        $graphics.FillEllipse($brush, 8, 8, 240, 240)
        if ($letter -eq 'a') {
            $graphics.DrawEllipse($pen, 64, 64, 128, 128)
            $graphics.DrawEllipse($pen, 100, 100, 56, 56)
        } elseif ($letter -eq 'b') {
            $graphics.DrawLine($pen, 54, 174, 110, 82)
            $graphics.DrawLine($pen, 110, 82, 150, 142)
            $graphics.DrawLine($pen, 150, 142, 176, 104)
            $graphics.DrawLine($pen, 176, 104, 208, 174)
        } else {
            $graphics.DrawArc($pen, 58, 58, 140, 140, 35, 290)
            $graphics.DrawLine($pen, 78, 172, 184, 86)
        }
        $bitmap.Save((Join-Path $OutputDirectory "circle_$letter.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $pen.Dispose(); $brush.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
foreach ($letter in @('a', 'b', 'c', 'd')) {
    $bitmap = [Drawing.Bitmap]::new(480, 160)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $pen = [Drawing.Pen]::new([Drawing.Color]::Black, 9)
    $pen.StartCap = $pen.EndCap = [Drawing.Drawing2D.LineCap]::Round
    try {
        $graphics.Clear([Drawing.Color]::Transparent)
        if ($letter -eq 'a') {
            $graphics.DrawBezier($pen, 40, 115, 210, -10, 120, 155, 350, 50)
            $graphics.DrawBezier($pen, 90, 130, 160, 65, 300, 120, 435, 96)
        } elseif ($letter -eq 'b') {
            $graphics.DrawEllipse($pen, 40, 36, 90, 90)
            $graphics.DrawLine($pen, 165, 40, 165, 120)
            $graphics.DrawLine($pen, 165, 120, 240, 120)
            $graphics.DrawLine($pen, 285, 40, 285, 120)
            $graphics.DrawLine($pen, 285, 40, 365, 40)
            $graphics.DrawLine($pen, 285, 80, 350, 80)
        } elseif ($letter -eq 'c') {
            $graphics.DrawBezier($pen, 35, 105, 145, -25, 130, 170, 275, 55)
            $graphics.DrawBezier($pen, 215, 130, 315, 10, 335, 150, 445, 55)
        } else {
            $graphics.DrawRectangle($pen, 38, 38, 88, 88)
            $graphics.DrawLine($pen, 170, 50, 420, 50)
            $graphics.DrawLine($pen, 170, 82, 360, 82)
            $graphics.DrawLine($pen, 170, 114, 390, 114)
        }
        $bitmap.Save((Join-Path $OutputDirectory "sig_$letter.png"), [Drawing.Imaging.ImageFormat]::Png)
    } finally { $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
}
