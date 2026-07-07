$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$Work = Join-Path $Root 'target\ci-runtime-downloads\windows'

$BbmapUrl = "https://sourceforge.net/projects/bbmap/files/BBMap_39.94.tar.gz/download"
$BbmapArchive = Join-Path $env:RUNNER_TEMP "BBMap_39.94.tar.gz"
$BbmapExtract = Join-Path $env:RUNNER_TEMP "bbmap-extract"

Write-Host "Downloading BBMap from: $BbmapUrl"

curl.exe -L `
  --retry 5 `
  --retry-delay 5 `
  --user-agent "Mozilla/5.0" `
  --output $BbmapArchive `
  $BbmapUrl

$size = (Get-Item $BbmapArchive).Length
if ($size -lt 100MB) {
    throw "BBMap download is too small ($size bytes); likely received an HTML redirect/error page."
}

Remove-Item $BbmapExtract -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $BbmapExtract | Out-Null

tar -xzf $BbmapArchive -C $BbmapExtract

$BbmapDir = Get-ChildItem $BbmapExtract -Directory -Recurse |
        Where-Object { Test-Path (Join-Path $_.FullName "current") } |
        Select-Object -First 1

if (-not $BbmapDir) {
    throw "Unable to locate extracted BBMap directory."
}

$BlastBaseUrl = if ($env:BLAST_BASE_URL) { $env:BLAST_BASE_URL } else { 'https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/' }
$BlastUrl = $env:BLAST_WINDOWS_URL

Remove-Item -Recurse -Force $Work, (Join-Path $Root 'runtime\common\bbmap'), (Join-Path $Root 'runtime\windows\blast') -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $Work, (Join-Path $Root 'runtime\common'), (Join-Path $Root 'runtime\windows') | Out-Null

Push-Location $Work
try {
    Write-Host "Downloading BBMap from: $BbmapUrl"
    Invoke-WebRequest -Uri $BbmapUrl -OutFile 'bbmap.tar.gz'
    tar -xzf 'bbmap.tar.gz'
    $BbmapDir = Get-ChildItem -Directory -Recurse | Where-Object { $_.Name -match '^bbmap(_.*)?$' } | Select-Object -First 1
    if (-not $BbmapDir) { throw 'Unable to locate extracted BBMap directory.' }
    Move-Item $BbmapDir.FullName (Join-Path $Root 'runtime\common\bbmap')

    if ([string]::IsNullOrWhiteSpace($BlastUrl)) {
        Write-Host "Resolving latest Windows BLAST+ package from: $BlastBaseUrl"
        $Index = (Invoke-WebRequest -Uri $BlastBaseUrl).Content
        $Matches = [regex]::Matches($Index, 'ncbi-blast-[^"<> ]+-x64-win64\.tar\.gz') | ForEach-Object { $_.Value } | Sort-Object -Unique
        $BlastFile = $Matches | Select-Object -Last 1
        if ([string]::IsNullOrWhiteSpace($BlastFile)) {
            throw 'Could not resolve latest Windows BLAST+ package. Set BLAST_WINDOWS_URL explicitly.'
        }
        $BlastUrl = "$BlastBaseUrl$BlastFile"
    }

    Write-Host "Downloading BLAST+ from: $BlastUrl"
    Invoke-WebRequest -Uri $BlastUrl -OutFile 'blast-windows.tar.gz'
    tar -xzf 'blast-windows.tar.gz'
    $BlastDir = Get-ChildItem -Directory | Where-Object { $_.Name -like 'ncbi-blast-*' } | Select-Object -First 1
    if (-not $BlastDir) { throw 'Unable to locate extracted BLAST+ directory.' }
    Move-Item $BlastDir.FullName (Join-Path $Root 'runtime\windows\blast')

    Write-Host 'Runtime dependencies prepared:'
    Get-ChildItem -Directory -Recurse (Join-Path $Root 'runtime') | Select-Object FullName
}
finally {
    Pop-Location
}
