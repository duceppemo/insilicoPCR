$ErrorActionPreference = 'Stop'

$Root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
$Work = Join-Path $Root 'target\ci-runtime-downloads\windows'

$BlastBaseUrl = if ($env:BLAST_BASE_URL) {
    $env:BLAST_BASE_URL
} else {
    'https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/LATEST/'
}
$BlastUrl = $env:BLAST_WINDOWS_URL

Remove-Item -Recurse -Force `
    $Work, `
    (Join-Path $Root 'runtime\common\bbmap'), `
    (Join-Path $Root 'runtime\windows\blast') `
    -ErrorAction SilentlyContinue

New-Item -ItemType Directory -Force `
    $Work, `
    (Join-Path $Root 'runtime\common'), `
    (Join-Path $Root 'runtime\windows') | Out-Null

Push-Location $Work
try {
    $BbmapUrls = @()

    if ($env:BBMAP_URL) {
        $BbmapUrls += $env:BBMAP_URL
    }

    $BbmapUrls += @(
        'https://sourceforge.net/projects/bbmap/files/BBMap_39.94.tar.gz/download',
        'https://downloads.sourceforge.net/project/bbmap/BBMap_39.94.tar.gz',
        'https://archive.org/download/bbmap_39.01/BBMap_39.01.tar.gz'
    )

    $DownloadedBbmap = $false

    foreach ($Url in $BbmapUrls) {
        Write-Host "Trying BBMap URL: $Url"

        Remove-Item 'bbmap.tar.gz' -Force -ErrorAction SilentlyContinue

        curl.exe -L -f `
        --retry 3 `
        --retry-delay 5 `
        --user-agent "Mozilla/5.0" `
        --output 'bbmap.tar.gz' `
        $Url

        if ($LASTEXITCODE -ne 0 -or -not (Test-Path 'bbmap.tar.gz')) {
            Write-Warning "Failed to download BBMap from: $Url"
            continue
        }

        $size = (Get-Item 'bbmap.tar.gz').Length
        if ($size -lt 10MB) {
            Write-Warning "BBMap download from $Url is too small ($size bytes)."
            continue
        }

        $DownloadedBbmap = $true
        break
    }

    if (-not $DownloadedBbmap) {
        throw "Unable to download BBMap from any configured URL. Set BBMAP_URL to a stable mirror or release asset."
    }

    tar -xzf 'bbmap.tar.gz'

    $BbmapDir = Get-ChildItem -Directory -Recurse |
            Where-Object {
                (Test-Path (Join-Path $_.FullName 'current')) -or
                        (Test-Path (Join-Path $_.FullName 'bbmap.sh')) -or
                        (Test-Path (Join-Path $_.FullName 'bbduk.sh'))
            } |
            Select-Object -First 1

    if (-not $BbmapDir) {
        throw 'Unable to locate extracted BBMap directory.'
    }

    Move-Item $BbmapDir.FullName (Join-Path $Root 'runtime\common\bbmap')

    if ([string]::IsNullOrWhiteSpace($BlastUrl)) {
        Write-Host "Resolving latest Windows BLAST+ package from: $BlastBaseUrl"
        $Index = (Invoke-WebRequest -Uri $BlastBaseUrl).Content
        $Matches = [regex]::Matches($Index, 'ncbi-blast-[^"<> ]+-x64-win64\.tar\.gz') |
                ForEach-Object { $_.Value } |
                Sort-Object -Unique

        $BlastFile = $Matches | Select-Object -Last 1
        if ([string]::IsNullOrWhiteSpace($BlastFile)) {
            throw 'Could not resolve latest Windows BLAST+ package. Set BLAST_WINDOWS_URL explicitly.'
        }

        $BlastUrl = "$BlastBaseUrl$BlastFile"
    }

    Write-Host "Downloading BLAST+ from: $BlastUrl"
    Invoke-WebRequest -Uri $BlastUrl -OutFile 'blast-windows.tar.gz'

    tar -xzf 'blast-windows.tar.gz'

    $BlastDir = Get-ChildItem -Directory |
            Where-Object { $_.Name -like 'ncbi-blast-*' } |
            Select-Object -First 1

    if (-not $BlastDir) {
        throw 'Unable to locate extracted BLAST+ directory.'
    }

    Move-Item $BlastDir.FullName (Join-Path $Root 'runtime\windows\blast')

    Write-Host 'Runtime dependencies prepared:'
    Get-ChildItem -Directory -Recurse (Join-Path $Root 'runtime') | Select-Object FullName
}
finally {
    Pop-Location
}
