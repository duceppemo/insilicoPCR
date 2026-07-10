# Developer Guide

This page summarizes the current project structure and development workflow.

## Requirements

- Java 26
- Maven wrapper included in the repository
- Git
- Platform runtime assets for release packaging

## Build

```bash
./mvnw clean package
```

The packaged JAR is created under `target/`.

## Run CLI from source build

```bash
java -jar target/insilicoPCR.jar -h
```

## Main entry points

| Area | Entry point |
|---|---|
| Dispatcher | `ca.canada.inspection.dispatchpcr.Dispatcher` |
| GUI | `ca.canada.inspection.insilicopcr.MainRun` |
| CLI | `ca.canada.inspection.commandpcr.CommandMain` |
| Pipeline | `ca.canada.inspection.pipeline.InSilicoPcrPipeline` |

The dispatcher starts the GUI when no arguments are provided and starts the CLI pipeline when command-line arguments are present.

## Pipeline overview

The shared pipeline coordinates:

1. Runtime dependency discovery
2. Output directory creation
3. Input sample loading
4. Primer FASTA parsing and preparation
5. FASTQ processing where applicable
6. BLAST database creation
7. BBMap/BLAST execution
8. Report generation
9. Consolidated output
10. Synthetic gel visualization support

## Runtime assets

Runtime assets are staged under `runtime/`.

```text
runtime/
├── common/
│   └── bbmap/
├── linux/
│   └── blast/
│       └── bin/
└── windows/
    └── blast/
        └── bin/
```

The packaged JDK is not committed. Release scripts copy `JAVA_HOME` into the staged portable release.

See [Runtime Layout](../runtime-layout.md).

## Release packaging

Linux portable ZIP:

```bash
export JAVA_HOME=/path/to/jdk-26
scripts/release/package-linux-portable.sh 0.6.0
```

Windows portable ZIP:

```powershell
scripts/release-windows.ps1
```

See [Release Process](../release-process.md).

## Documentation approach

Keep the README compact. Put detailed user and developer documentation under `docs/wiki/`.

When adding a feature, update:

- README only if the feature changes the project overview or quick start
- relevant `docs/wiki/` page for full details
- SVG diagrams under `docs/assets/` if the architecture/workflow changes
- screenshots under `docs/screenshots/` when available

## Notes for future improvements

Potential future work:

- add tests for primer FASTA validation
- enforce the standard PCR vs qPCR mode limitation earlier in the run
- improve synthetic gel export options
- add screenshots to the README and Wiki
- publish `docs/wiki/` pages into the GitHub Wiki when desired
