param(
    [string]$Version = "",
    [switch]$Installer
)

$ErrorActionPreference = "Stop"
$AppName = "insilicoPCR"
$AppModule = "ca.canada.inspection.insilicopcr"
$MainClass = "ca.canada.inspection.dispatchpcr.Dispatcher"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")

if ([string]::IsNullOrWhiteSpace($Version)) {
    $Pom = Get-Content (Join-Path $Root "pom.xml") -Raw
    $Version = [regex]::Match($Pom, "<version>([^<]+)</version>").Groups[1].Value
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME) -or
    -not (Test-Path (Join-Path $env:JAVA_HOME "bin\jlink.exe")) -or
    -not (Test-Path (Join-Path $env:JAVA_HOME "bin\jpackage.exe"))) {
    throw "JAVA_HOME must point to a JDK 26+ that contains jlink and jpackage."
}

$RuntimeCommon = Join-Path $Root "runtime\common"
$RuntimePlatform = Join-Path $Root "runtime\windows"
$BbmapSrc = Join-Path $RuntimeCommon "bbmap"
$BlastSrc = Join-Path $RuntimePlatform "blast"

if (-not (Test-Path $BbmapSrc)) { throw "BBMap not found: $BbmapSrc" }
if (-not (Test-Path (Join-Path $BlastSrc "bin"))) { throw "BLAST bin not found: $BlastSrc\bin" }

Push-Location $Root
try {
    & .\mvnw.cmd -Prelease-windows -DskipTests clean package

    $AppJar = Join-Path $Root "target\$AppName.jar"
    $LibDir = Join-Path $Root "target\lib"
    $ImageRuntime = Join-Path $Root "target\jlink-runtime-windows-x64"
    $JpackageDir = Join-Path $Root "target\jpackage-windows"
    $AppContent = Join-Path $Root "target\release-app-content"
    $ReleaseDir = Join-Path $Root "release"
    $AppImageName = "$AppName-$Version-windows-x64"
    $AppImage = Join-Path $JpackageDir $AppName
    $FinalImage = Join-Path $ReleaseDir $AppImageName

    Remove-Item -Recurse -Force $ImageRuntime, $JpackageDir, $AppContent, $FinalImage -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Join-Path $AppContent "dependencies") | Out-Null
    New-Item -ItemType Directory -Force $ReleaseDir | Out-Null
    Copy-Item -Recurse $BbmapSrc (Join-Path $AppContent "dependencies\bbmap")
    Copy-Item -Recurse $BlastSrc (Join-Path $AppContent "dependencies\blast")
    foreach ($doc in @("README.md", "LICENSE", "CHANGELOG.md")) {
        $p = Join-Path $Root $doc
        if (Test-Path $p) { Copy-Item $p $AppContent }
    }

    $ModulePath = "$env:JAVA_HOME\jmods;$AppJar;$LibDir"
    $JdepsOut = & "$env:JAVA_HOME\bin\jdeps.exe" --ignore-missing-deps --multi-release 26 --module-path "$LibDir" --print-module-deps "$AppJar" 2>$null
    if ([string]::IsNullOrWhiteSpace($JdepsOut)) {
        $Modules = "java.base,java.desktop,java.logging,java.xml,java.naming,jdk.crypto.ec,jdk.localedata,javafx.controls,javafx.fxml"
    } else {
        $Modules = "$JdepsOut,jdk.crypto.ec,jdk.localedata"
    }

    & "$env:JAVA_HOME\bin\jlink.exe" `
        --module-path "$ModulePath" `
        --add-modules "$Modules" `
        --strip-debug `
        --no-header-files `
        --no-man-pages `
        --compress=zip-6 `
        --output "$ImageRuntime"

    & "$env:JAVA_HOME\bin\jpackage.exe" `
        --type app-image `
        --name "$AppName" `
        --app-version "$Version" `
        --vendor "Canadian Food Inspection Agency" `
        --description "Portable JavaFX application for in silico PCR analysis." `
        --dest "$JpackageDir" `
        --runtime-image "$ImageRuntime" `
        --module-path "$AppJar;$LibDir" `
        --module "$AppModule/$MainClass" `
        --java-options "--enable-native-access=javafx.graphics" `
        --app-content "$AppContent"

    Move-Item $AppImage $FinalImage

    $Zip = Join-Path $ReleaseDir "$AppImageName.zip"
    Remove-Item $Zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path $FinalImage -DestinationPath $Zip -Force
    Get-FileHash $Zip -Algorithm SHA256 | ForEach-Object { "$($_.Hash.ToLower())  $(Split-Path $Zip -Leaf)" } | Set-Content (Join-Path $ReleaseDir "$AppImageName.sha256")

    if ($Installer) {
        & "$env:JAVA_HOME\bin\jpackage.exe" `
            --type msi `
            --name "$AppName" `
            --app-version "$Version" `
            --vendor "Canadian Food Inspection Agency" `
            --description "Portable JavaFX application for in silico PCR analysis." `
            --dest "$ReleaseDir" `
            --app-image "$FinalImage"
    }

    Write-Host "Release artifacts written to: $ReleaseDir"
    Get-ChildItem $ReleaseDir | Format-Table Name, Length, LastWriteTime
}
finally {
    Pop-Location
}
