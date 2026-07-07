param(
    [string]$Version = "0.0.0-ci"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReleaseDir = Join-Path $Root "release"
$StageDir = Join-Path $Root "target\insilicoPCR-windows-x64"

Set-Location $Root

if ($IsWindows) {
    .\mvnw.cmd -B clean package -DskipTests
} else {
    ./mvnw -B clean package -DskipTests
}

Remove-Item $StageDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $StageDir, $ReleaseDir | Out-Null

Copy-Item "target\insilicoPCR.jar" $StageDir
if (Test-Path "runtime") { Copy-Item "runtime" (Join-Path $StageDir "runtime") -Recurse -Force }
if (Test-Path "README.md") { Copy-Item "README.md" (Join-Path $StageDir "README.md") -Force }
if (Test-Path "CHANGELOG.md") { Copy-Item "CHANGELOG.md" (Join-Path $StageDir "CHANGELOG.md") -Force }
if (Test-Path "LICENSE") { Copy-Item "LICENSE" (Join-Path $StageDir "LICENSE") -Force }
if (Test-Path "LICENSE.txt") { Copy-Item "LICENSE.txt" (Join-Path $StageDir "LICENSE.txt") -Force }

@"
@echo off
set DIR=%~dp0

if exist "%DIR%runtime\windows\jdk\bin\java.exe" (
  set JAVA=%DIR%runtime\windows\jdk\bin\java.exe
) else if exist "%DIR%runtime\windows\jre\bin\java.exe" (
  set JAVA=%DIR%runtime\windows\jre\bin\java.exe
) else (
  set JAVA=java
)

"%JAVA%" -jar "%DIR%insilicoPCR.jar" %*
"@ | Set-Content -Encoding ASCII (Join-Path $StageDir "run-insilicoPCR.bat")

$Archive = Join-Path $ReleaseDir "insilicoPCR-$Version-windows-x64.zip"
$ShaFile = "$Archive.sha256"

Remove-Item $Archive, $ShaFile -Force -ErrorAction SilentlyContinue

Compress-Archive -Path "$StageDir\*" -DestinationPath $Archive -Force

$Hash = Get-FileHash $Archive -Algorithm SHA256
"$($Hash.Hash.ToLower())  $(Split-Path $Archive -Leaf)" | Set-Content -Encoding ASCII $ShaFile

Write-Host "Created:"
Get-Item $Archive, $ShaFile | Format-Table Name, Length
