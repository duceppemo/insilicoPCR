param(
    [string]$Version = "0.0.0-ci"
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$ReleaseDir = Join-Path $Root "release"
$StageDir = Join-Path $Root "target\insilicoPCR-windows-x64"

Remove-Item $StageDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $StageDir, $ReleaseDir | Out-Null

Set-Location $Root

.\mvnw.cmd -B clean package -DskipTests

Copy-Item "target\insilicoPCR.jar" $StageDir
if (Test-Path "runtime") { Copy-Item "runtime" $StageDir -Recurse }
if (Test-Path "README.md") { Copy-Item "README.md" $StageDir }
if (Test-Path "CHANGELOG.md") { Copy-Item "CHANGELOG.md" $StageDir }
if (Test-Path "LICENSE") { Copy-Item "LICENSE" $StageDir }
if (Test-Path "LICENSE.txt") { Copy-Item "LICENSE.txt" $StageDir }

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
