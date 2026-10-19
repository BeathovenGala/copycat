# Builds both apps and publishes a GitHub release. Bump ../VERSION first.
param([string]$Notes = "")

# gh/git write progress to stderr, which "Stop" would treat as fatal in PowerShell 5.1.
$ErrorActionPreference = "Continue"
$root = Split-Path $PSScriptRoot -Parent
$repo = "BeathovenGala/copycat"
$version = (Get-Content "$root\VERSION" -Raw).Trim()
$tag = "v$version"
$dist = "$root\dist"

$env:GH_TOKEN = gh auth token -u BeathovenGala
$existing = gh release list --repo $repo --json tagName -q ".[].tagName"
if ($existing -contains $tag) { throw "$tag already exists - bump VERSION first" }

Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dist | Out-Null

Push-Location "$root\android"
.\gradlew.bat assembleRelease -q
if ($LASTEXITCODE) { throw "Android build failed" }
Pop-Location
Copy-Item "$root\android\app\build\outputs\apk\release\app-release.apk" "$dist\CopyCat.apk"

dotnet publish "$root\desktop\CopyCat.Desktop.csproj" -c Release -r win-x64 --self-contained true `
    -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:EnableCompressionInSingleFile=true `
    -o "$root\desktop\bin\publish" | Out-Null
if ($LASTEXITCODE) { throw "Desktop build failed" }
Copy-Item "$root\desktop\bin\publish\CopyCat.exe" "$dist\CopyCat.exe"

if (-not $Notes) { $Notes = "CopyCat $tag" }
$Notes += "`n`n**Phone:** download ``CopyCat.apk`` and install it.`n**Laptop (Windows):** download ``CopyCat.exe`` and run it.`n**Laptop (Mac):** download ``CopyCat-mac.zip`` (attached a few minutes after release by CI).`n**iPhone:** laptop menu → Connect iPhone, scan the QR code.`n`nAlready installed? Both apps update themselves."

git -C $root push origin HEAD 2>&1 | Out-Null
if ($LASTEXITCODE) { throw "git push failed" }
gh release create $tag "$dist\CopyCat.apk" "$dist\CopyCat.exe" --repo $repo --title "CopyCat $tag" --notes $Notes
if ($LASTEXITCODE) { throw "gh release failed" }
Write-Host "Released $tag -> https://github.com/$repo/releases/tag/$tag"
