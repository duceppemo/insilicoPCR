# Release Pipeline

The release process creates deterministic portable ZIP archives for Linux and Windows.

## CI release flow

1. Check out the repository.
2. Install JDK 26.
3. Validate runtime assets.
4. Build with Maven.
5. Stage the application, libraries, JDK, BBMap, and BLAST+.
6. Create portable ZIP files.
7. Create SHA256 checksum files.
8. Publish release artifacts for version tags.

## Linux packaging

```bash
export JAVA_HOME=/path/to/jdk-26
scripts/release/package-linux-portable.sh 0.6.0
```

Expected artifacts:

```text
release/insilicoPCR-<version>-linux-x64.zip
release/insilicoPCR-<version>-linux-x64.zip.sha256
```

## Windows packaging

```powershell
scripts/release-windows.ps1
```

Expected artifacts:

```text
release/insilicoPCR-<version>-windows-x64.zip
release/insilicoPCR-<version>-windows-x64.zip.sha256
```

## Design principle

Release builds should not download BBMap or BLAST+ from external mirrors during CI. The runtime assets are validated from the repository checkout.
