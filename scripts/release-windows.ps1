param(
    [string]$Version = "0.6.0"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReleaseDir = Join-Path $Root "release"
$StageDir = Join-Path $Root "build\insilicoPCR-windows-x64"

function Require-File([string]$Path) {
    if (-not (Test-Path $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }
}

function Require-Directory([string]$Path) {
    if (-not (Test-Path $Path -PathType Container)) {
        throw "Missing required directory: $Path"
    }
}

function Validate-BundledTools {
    Require-Directory (Join-Path $Root "runtime\common\bbmap")

    $bbdukCandidates = @(
        (Join-Path $Root "runtime\common\bbmap\bbduk.bat"),
        (Join-Path $Root "runtime\common\bbmap\current\bbduk.bat"),
        (Join-Path $Root "runtime\common\bbmap\bbduk.sh"),
        (Join-Path $Root "runtime\common\bbmap\current\bbduk.sh")
    )

    if (-not ($bbdukCandidates | Where-Object { Test-Path $_ -PathType Leaf } | Select-Object -First 1)) {
        throw "Missing BBMap bbduk launcher under runtime\common\bbmap"
    }

    Require-Directory (Join-Path $Root "runtime\windows\blast\bin")
    Require-File (Join-Path $Root "runtime\windows\blast\bin\blastn.exe")
    Require-File (Join-Path $Root "runtime\windows\blast\bin\makeblastdb.exe")
}

Set-Location $Root

Validate-BundledTools

if ($IsWindows) {
    .\mvnw.cmd -B clean package -DskipTests
} else {
    ./mvnw -B clean package -DskipTests
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME must point to the Windows JDK that should be bundled in the portable release."
}

Remove-Item $StageDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path `
    $StageDir, `
    $ReleaseDir, `
    (Join-Path $StageDir "runtime\windows"), `
    (Join-Path $StageDir "runtime\common") | Out-Null

Require-File (Join-Path $Root "target\insilicoPCR.jar")
Require-Directory (Join-Path $Root "target\lib")

Copy-Item "target\insilicoPCR.jar" $StageDir -Force
Copy-Item "target\lib" (Join-Path $StageDir "lib") -Recurse -Force
Copy-Item $env:JAVA_HOME (Join-Path $StageDir "runtime\windows\jdk") -Recurse -Force
Copy-Item "runtime\common\bbmap" (Join-Path $StageDir "runtime\common\bbmap") -Recurse -Force
Copy-Item "runtime\windows\blast" (Join-Path $StageDir "runtime\windows\blast") -Recurse -Force

if (Test-Path "README.md") { Copy-Item "README.md" (Join-Path $StageDir "README.md") -Force }
if (Test-Path "CHANGELOG.md") { Copy-Item "CHANGELOG.md" (Join-Path $StageDir "CHANGELOG.md") -Force }
if (Test-Path "LICENSE") { Copy-Item "LICENSE" (Join-Path $StageDir "LICENSE") -Force }
if (Test-Path "LICENSE.txt") { Copy-Item "LICENSE.txt" (Join-Path $StageDir "LICENSE.txt") -Force }
if (Test-Path "docs\runtime-layout.md") { Copy-Item "docs\runtime-layout.md" (Join-Path $StageDir "runtime-layout.md") -Force }

@"
@echo off
set DIR=%~dp0
set JAVA=%DIR%runtime\windows\jdk\bin\java.exe

if not exist "%JAVA%" (
  echo Could not find bundled Java runtime at: %JAVA%
  exit /b 1
)

"%JAVA%" -p "%DIR%lib;%DIR%insilicoPCR.jar" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher %*
"@ | Set-Content -Encoding ASCII (Join-Path $StageDir "run-insilicoPCR.bat")

$Archive = Join-Path $ReleaseDir "insilicoPCR-$Version-windows-x64.zip"
$ShaFile = "$Archive.sha256"

Remove-Item $Archive, $ShaFile -Force -ErrorAction SilentlyContinue

Compress-Archive -Path "$StageDir\*" -DestinationPath $Archive -Force

$Hash = Get-FileHash $Archive -Algorithm SHA256
"$($Hash.Hash.ToLower())  $(Split-Path $Archive -Leaf)" | Set-Content -Encoding ASCII $ShaFile

Write-Host "Created:"
Get-Item $Archive, $ShaFile | Format-Table Name, Length
