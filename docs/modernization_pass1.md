# insilicoPCR architecture modernization pass

This bundle is a coherent source replacement for the command-line pipeline architecture.

## What changed

- `Dispatcher` now only handles GUI/CLI dispatch and argument parsing.
- `CliConfig` is a standalone immutable validated CLI configuration record.
- `CommandMain` is now a backwards-compatible adapter into the pipeline package.
- The command pipeline is split into cohesive services under `ca.canada.inspection.pipeline`:
    - `InSilicoPcrPipeline`
    - `PipelineConfig`
    - `PipelineContext`
    - `PipelineDirectories`
    - `RuntimeDependencies`
    - `PrimerService`
    - `SampleRepository`
    - `FastqProcessor`
    - `BlastDatabaseBuilder`
    - `BlastRunner`
    - `ReportGenerator`
    - `ParallelStageRunner`
    - typed pipeline exceptions
- JDK-specific `com.sun.management` and `java.lang.management.ManagementFactory` usage is removed.
- `module-info.java` no longer requires `java.management` or `jdk.management`.
- FastQ baiting, second baiting, assembly, and BLAST are structured as independently parallelizable stages.
- `ProcessRunner` now supports a typed `ExternalCommand` while retaining the existing varargs API.
- `BlastResult` is now an immutable record with legacy getter methods for compatibility.
- `Sample` exposes safer immutable views while preserving legacy mutable getters for GUI/helper compatibility.
- The obsolete `CommandMethods` transition utility has been removed now that the CLI path delegates to the modern pipeline.

## How to apply

Copy the contents of this bundle's `src/main/java` folder over your repository's `src/main/java` folder.

From the repository root:

```bash
cp -R /path/to/insilicoPCR-architecture-modernization/src/main/java/* src/main/java/
mvn clean test
mvn clean package
```

## Compatibility note

`CommandMain` remains as the backwards-compatible command entry point, but it now delegates directly to `InSilicoPcrPipeline`. The older monolithic `CommandMethods` helper was removed after its functionality was migrated into focused pipeline services such as `PrimerService`, `SampleRepository`, `BlastRunner`, and `ReportGenerator`.
