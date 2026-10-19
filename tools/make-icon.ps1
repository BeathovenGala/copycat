param([string]$Out = "$PSScriptRoot\..\desktop\copycat.ico", [string]$PngOut = "$PSScriptRoot\icon-512.png")
Add-Type -AssemblyName System.Drawing

function Draw-Icon([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = $size / 32.0
    function RoundRect($x, $y, $w, $h, $r) {
        $p = New-Object System.Drawing.Drawing2D.GraphicsPath
        $p.AddArc($x, $y, $r, $r, 180, 90); $p.AddArc($x + $w - $r, $y, $r, $r, 270, 90)
        $p.AddArc($x + $w - $r, $y + $h - $r, $r, $r, 0, 90); $p.AddArc($x, $y + $h - $r, $r, $r, 90, 90)
        $p.CloseFigure(); $p
    }
    $back = [System.Drawing.Color]::FromArgb(255, 124, 92, 255)
    $front = [System.Drawing.Color]::FromArgb(255, 255, 196, 61)
    $g.FillPath((New-Object System.Drawing.SolidBrush $back), (RoundRect (2*$s) (2*$s) (19*$s) (19*$s) (6*$s)))
    $g.FillPath((New-Object System.Drawing.SolidBrush $front), (RoundRect (11*$s) (11*$s) (19*$s) (19*$s) (6*$s)))
    $g.Dispose()
    $bmp
}

$sizes = 16, 32, 48, 256
$pngs = foreach ($sz in $sizes) {
    $ms = New-Object System.IO.MemoryStream
    (Draw-Icon $sz).Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    , $ms.ToArray()
}
(Draw-Icon 512).Save($PngOut, [System.Drawing.Imaging.ImageFormat]::Png)

$fs = [System.IO.File]::Create($Out)
$w = New-Object System.IO.BinaryWriter $fs
$w.Write([UInt16]0); $w.Write([UInt16]1); $w.Write([UInt16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dim = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([UInt16]1); $w.Write([UInt16]32); $w.Write([UInt32]$pngs[$i].Length); $w.Write([UInt32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $w.Write($p) }
$w.Close()
