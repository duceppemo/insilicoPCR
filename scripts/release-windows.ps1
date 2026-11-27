param(
    # The version the ZIP is named for: given (CI gives the tag's), or the project's own.
    [string]$Version = ""
)

$ErrorActionPreference = "Stop"

# PowerShell 7 (pwsh): Windows PowerShell 5.1 has no $IsWindows, so this script took the Unix branch
# below and ran ./mvnw, and its Compress-Archive writes entry names with backslashes, which a ZIP
# must not hold. CI runs it with pwsh; release-windows.bat starts pwsh when it is installed.
if ($PSVersionTable.PSVersion.Major -lt 6) {
    throw "Run this script with PowerShell 7 (pwsh), not Windows PowerShell $($PSVersionTable.PSVersion): https://aka.ms/powershell"
}

$Root = Split-Path -Parent $PSScriptRoot
if (-not $Version) {
    $Version = ([xml](Get-Content (Join-Path $Root "pom.xml"))).project.version
}
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
    Require-File (Join-Path $Root "runtime\common\bbmap\bbtools.jar")
    Require-File (Join-Path $Root "runtime\common\bbmap\bbmap.sh")
    Require-File (Join-Path $Root "runtime\common\bbmap\bbduk.sh")
    Require-File (Join-Path $Root "runtime\common\bbmap\tadpole.sh")
    Require-File (Join-Path $Root "runtime\common\bbmap\calcmem.sh")
    Require-File (Join-Path $Root "runtime\common\bbmap\javasetup.sh")
    Require-File (Join-Path $Root "runtime\common\bbmap\networks\bbmerge.bbnet.gz")
    Require-Directory (Join-Path $Root "runtime\common\bbmap\config")
    Require-Directory (Join-Path $Root "runtime\common\bbmap\resources")

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
# A failed build would otherwise leave target\ as an earlier build left it, and that would be packaged.
if ($LASTEXITCODE -ne 0) {
    throw "The Maven build failed (exit code $LASTEXITCODE)"
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
# The bundled programs' and libraries' licenses: their redistribution requires them.
Copy-Item "THIRD-PARTY-NOTICES.md" (Join-Path $StageDir "THIRD-PARTY-NOTICES.md") -Force
Copy-Item "third_party" (Join-Path $StageDir "third_party") -Recurse -Force
if (Test-Path "LICENSE.txt") { Copy-Item "LICENSE.txt" (Join-Path $StageDir "LICENSE.txt") -Force }
if (Test-Path "docs\runtime-layout.md") { Copy-Item "docs\runtime-layout.md" (Join-Path $StageDir "runtime-layout.md") -Force }

@"
@echo off
rem setlocal keeps DIR and JAVA out of the caller's session. No ( ) block expands %JAVA%: a folder
rem name holding a parenthesis ("... (1)", "Program Files (x86)") would end the block early.
rem Native access: the programs a run starts are put in a job object, so they end with it (WindowsJob),
rem and JavaFX loads its native libraries without a warning in the console window.
setlocal
set "DIR=%~dp0"
set "JAVA=%DIR%runtime\windows\jdk\bin\java.exe"
if not exist "%JAVA%" goto nojava
"%JAVA%" --enable-native-access=ca.canada.inspection.insilicopcr,javafx.graphics -p "%DIR%lib;%DIR%insilicoPCR.jar" -m ca.canada.inspection.insilicopcr/ca.canada.inspection.dispatchpcr.Dispatcher %*
exit /b %ERRORLEVEL%
:nojava
echo Could not find bundled Java runtime at: %JAVA%
exit /b 1
"@ | Set-Content -Encoding ASCII (Join-Path $StageDir "run-insilicoPCR.bat")

# The versions bundled, for whoever audits a result, as the Linux ZIP lists them: the Windows BLAST+ is its own
# build, whose version nothing recorded.
function First-Line([string]$Program, [string]$Arguments, [string]$Pattern) {
    $info = New-Object System.Diagnostics.ProcessStartInfo $Program, $Arguments
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.UseShellExecute = $false
    $process = [System.Diagnostics.Process]::Start($info)
    # Both streams read at once: one filling while the other is waited for would stop the program.
    $out = $process.StandardOutput.ReadToEndAsync()
    $err = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $line = (($out.Result + "`n" + $err.Result) -split "`r?`n") | Where-Object { $_ -match $Pattern } | Select-Object -First 1
    if (-not $line) { throw "$Program $Arguments printed no line matching $Pattern" }
    return $line.Trim()
}
$BundledJava = Join-Path $StageDir "runtime\windows\jdk\bin\java.exe"
$BbTools = Join-Path $StageDir "runtime\common\bbmap\bbtools.jar"
$JavaFxJar = Get-ChildItem (Join-Path $StageDir "lib") -Filter "javafx-base-*-win.jar" | Select-Object -First 1
if (-not $JavaFxJar) { throw "No javafx-base-*-win.jar in $StageDir\lib" }
$JavaFx = $JavaFxJar.Name -replace '^javafx-base-(.*)-win\.jar$', '$1'
$BbDuk = First-Line $BundledJava "-cp `"$BbTools`" jgi.BBDuk --version" 'BBTools version'
@(
    "insilicoPCR $Version"
    "Java: $(First-Line $BundledJava '-version' 'Runtime Environment')"
    "BLAST+: $(First-Line (Join-Path $StageDir 'runtime\windows\blast\bin\blastn.exe') '-version' '^blastn:')"
    "BBTools: $([regex]::Match($BbDuk, 'BBTools version [0-9.]+').Value)"
    "JavaFX: $JavaFx"
) | Set-Content -Encoding ASCII (Join-Path $StageDir "VERSIONS.txt")
Get-Content (Join-Path $StageDir "VERSIONS.txt")

$Archive = Join-Path $ReleaseDir "insilicoPCR-$Version-windows-x64.zip"
$ShaFile = "$Archive.sha256"

Remove-Item $Archive, $ShaFile -Force -ErrorAction SilentlyContinue

Compress-Archive -Path "$StageDir\*" -DestinationPath $Archive -Force

$Hash = Get-FileHash $Archive -Algorithm SHA256
"$($Hash.Hash.ToLower())  $(Split-Path $Archive -Leaf)" | Set-Content -Encoding ASCII $ShaFile

Write-Host "Created:"
Get-Item $Archive, $ShaFile | Format-Table Name, Length
