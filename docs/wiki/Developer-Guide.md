# Developer Guide

This page summarizes the current project structure and points developers to the detailed Wiki pages.

## Developer pages

- [Architecture](Architecture)
- [Runtime Layout](Runtime-Layout)
- [Build System](Build-System)
- [Release Pipeline](Release-Pipeline)
- [Contributing](Contributing)
- [Publishing to GitHub Wiki](Publishing-to-GitHub-Wiki)

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

## Documentation approach

Keep the README compact. Use the GitHub Wiki as the user-facing manual.

The `docs/wiki/` directory is a source-controlled mirror that can be pushed into the actual GitHub Wiki repository after the Wiki has been initialized.

When adding a feature, update:

- README only if the feature changes the project overview or quick start
- relevant Wiki page for full details
- SVG diagrams under `docs/assets/` if the architecture, workflow, or release process changes
- screenshots under `docs/screenshots/` when available

## Notes for future improvements

Potential future work:

- add tests for primer FASTA validation
- enforce the standard PCR vs qPCR mode limitation earlier in the run
- improve synthetic gel export options
- add screenshots to the README and Wiki
- publish `docs/wiki/` pages into the GitHub Wiki after initialization
