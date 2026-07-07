# insilicoPCR

Portable JavaFX application for in silico PCR analysis.

## Runtime and releases

The project uses a deterministic portable runtime layout. BBMap and NCBI BLAST are treated as curated application runtime assets and are expected under `runtime/` in the repository:

```text
runtime/
  common/
    bbmap/
  linux/
    blast/
      bin/
  windows/
    blast/
      bin/
```

GitHub Actions does not download BBMap or BLAST during release builds. The workflow validates the checked-in tools, builds the Java application, copies the CI JDK from `JAVA_HOME` into the staged release, then produces portable ZIP archives.

The bundled JDK is generated at package time and is not committed.

See:

- `docs/runtime-layout.md`
- `docs/release-process.md`
- `docs/cleanup-summary.md`

## Build locally

```bash
./mvnw clean package
```

## Linux portable ZIP

```bash
export JAVA_HOME=/path/to/jdk-26
scripts/release/package-linux-portable.sh 0.6.0
```

## Windows portable ZIP

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-26'
.\scripts\release-windows.ps1 -Version 0.6.0
```
