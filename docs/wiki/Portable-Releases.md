# Portable Releases

Portable releases are self-contained ZIP archives for end users.

They are designed to run without installing Java, JavaFX, BBMap, or BLAST+ separately.

## Archive names

| Platform | Archive |
|---|---|
| Linux x64 | `insilicoPCR-<version>-linux-x64.zip` |
| Windows x64 | `insilicoPCR-<version>-windows-x64.zip` |

Each release also includes a matching `.sha256` checksum file, and `insilicoPCR-<version>-linux-x64-no-runtime.zip`, which uses the Java, BLAST+ and BBTools on `PATH` (see [Installation](Installation)).

The Linux ZIP holds one folder, `insilicoPCR-linux-x64/`; the Windows ZIP has its files at the top.

## Launcher files

| Platform | Launcher |
|---|---|
| Linux | `run-insilicoPCR.sh` |
| Windows | `run-insilicoPCR.bat` |

Use these launchers for both the window and the command line: without arguments they open the window, with them they run on the command line. They use the bundled Java, and the Linux one switches to UTF-8 under a locale that is not (`LANG=C`), so that file names with accents can be opened.

## CLI use

```bash
./run-insilicoPCR.sh -h
```

The bundled Java executable also works directly:

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
CHANGELOG.md
LICENSE
THIRD-PARTY-NOTICES.md
third_party/
runtime-layout.md
VERSIONS.txt (Linux)
```

## Important

Do not move files out of the extracted release folder. The launchers and runtime detection expect the release layout to remain intact.
