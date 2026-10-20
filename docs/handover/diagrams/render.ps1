# Renders every .mmd in this folder to .svg and .png (white background, 2x scale).
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot
python make_sources.py
$chrome = @("$env:ProgramFiles\Google\Chrome\Application\chrome.exe", "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
$cfg = Join-Path $env:TEMP "copycat-puppeteer.json"
@{ executablePath = $chrome; args = @("--no-sandbox") } | ConvertTo-Json | Set-Content -Encoding ascii $cfg
$env:PUPPETEER_SKIP_DOWNLOAD = "true"
foreach ($f in Get-ChildItem *.mmd) {
    foreach ($ext in "svg", "png") {
        npx -y @mermaid-js/mermaid-cli@11.4.2 -p $cfg -b white -s 2 -i $f.Name -o "$($f.BaseName).$ext" 2>&1 | Out-Null
        if ($LASTEXITCODE) { Write-Warning "failed: $($f.Name) -> $ext" }
    }
}
Get-ChildItem *.svg, *.png | Select-Object Name, Length
