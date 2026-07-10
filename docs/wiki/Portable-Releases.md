# Portable Releases

Portable releases are self-contained ZIP archives for end users.

They are designed to run without installing Java, JavaFX, BBMap, or BLAST+ separately.

## Archive names

| Platform | Archive |
|---|---|
| Linux x64 | `insilicoPCR-<version>-linux-x64.zip` |
| Windows x64 | `insilicoPCR-<version>-windows-x64.zip` |

Each release also includes a matching `.sha256` checksum file.

## Launcher files

| Platform | Launcher |
|---|---|
| Linux | `run-insilicoPCR.sh` |
| Windows | `run-insilicoPCR.bat` |

Use these launchers for the graphical application. They configure the bundled runtime paths automatically.

## CLI use

For command-line runs, use the bundled Java executable directly.

Linux example:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

## Runtime contents

A portable release contains:

```text
insilicoPCR.jar
lib/
runtime/
  common/bbmap/
  linux/jdk/ or windows/jdk/
  linux/blast/ or windows/blast/
run-insilicoPCR.sh or run-insilicoPCR.bat
README.md
LICENSE
```

## Important

Do not move files out of the extracted release folder. The launchers and runtime detection expect the release layout to remain intact.
