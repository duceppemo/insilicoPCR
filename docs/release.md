# Release packaging

This project uses Maven for compilation/dependency staging, `jlink` for a small application runtime image, and `jpackage` for platform-native application images.

## Local dependency layout

The release scripts expect the large external tools to be present locally but not committed to Git:

```text
runtime/
  common/
    bbmap/
  linux/
    blast/
      bin/
        blastn
        makeblastdb
  windows/
    blast/
      bin/
        blastn.exe
        makeblastdb.exe
```

The old bundled JDK and JavaFX SDK folders are no longer needed for releases. The scripts use `JAVA_HOME` and Maven JavaFX dependencies instead.

## Linux release

```bash
export JAVA_HOME=/home/marco/.jdks/openjdk-26.0.1
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/release-linux.sh 0.6.0
```

This creates:

```text
release/insilicoPCR-0.6.0-linux-x64.zip
release/insilicoPCR-0.6.0-linux-x64.sha256
```

To also try creating a `.deb` installer:

```bash
DO_INSTALLER=true ./scripts/release-linux.sh 0.6.0
```

## Windows release

Run on Windows with JDK 26 installed:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-26.0.1"
.\scripts\release-windows.ps1 -Version 0.6.0
```

This creates:

```text
release/insilicoPCR-0.6.0-windows-x64.zip
release/insilicoPCR-0.6.0-windows-x64.sha256
```

To also try creating an `.msi` installer, install the Windows packaging prerequisites, then run:

```powershell
.\scripts\release-windows.ps1 -Version 0.6.0 -Installer
```

## What the scripts do

1. Run Maven with the platform release profile.
2. Copy runtime dependencies to `target/lib`.
3. Stage BBMap and BLAST under `target/release-app-content/dependencies`.
4. Use `jdeps` to infer runtime modules.
5. Use `jlink` to create a trimmed Java runtime image.
6. Use `jpackage` to create a native app image.
7. Zip the app image and write a SHA-256 checksum.

`jpackage` app images and installers must be built on the target OS. Build Linux packages on Linux and Windows packages on Windows.
