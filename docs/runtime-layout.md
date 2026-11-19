# Runtime layout

insilicoPCR uses a deterministic portable runtime layout. CI no longer downloads BBMap or BLAST from external mirrors during release packaging.

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

The bundled JDK is intentionally not committed. GitHub Actions installs JDK 25 (LTS), then the packaging scripts copy `JAVA_HOME` into the generated ZIP as `runtime/<platform>/jdk`.

## Without a runtime folder

When there is no `runtime` folder beside `insilicoPCR.jar` (the ZIP without a runtime, `scripts/release/package-no-runtime.sh`, and the Bioconda package made from it), `AppPaths` takes the programs installed beside insilicoPCR instead: `blastn` and `makeblastdb` from the first `PATH` folder that has both, BBTools from the folder `bbduk.sh` is in (links followed), its classes in `bbtools.jar` or in `current/` (conda's bbmap package), and the Java runtime running insilicoPCR for BBTools. A `runtime` folder, even an incomplete one, is always used as the portable layout: a ZIP missing a tool fails, it does not fall back to `PATH`.

## Why tools are committed

BBMap and BLAST are application runtime assets. Downloading them during every CI run makes releases depend on SourceForge and NCBI mirror availability, redirects, HTML error pages, and changing latest artifacts. Keeping known-good tool builds in the repository makes releases reproducible.

## What should not be committed

Do not commit generated release output, Maven output, IDE output, JavaFX SDK folders, or JDK/JRE folders. The `.gitignore` allows the curated BBMap/BLAST runtime folders while excluding generated runtimes.

## Updating BBMap or BLAST

1. Replace the files under the relevant `runtime/` folders.
2. Run `REQUIRE_BINARIES=true scripts/dev/verify-runtime-layout.sh`, from the repository root (without `REQUIRE_BINARIES=true` it only checks that the `runtime/` folders exist).
3. Run the platform packaging script locally.
4. Confirm that the generated ZIP contains the expected layout.
5. Commit the runtime tool update together with a note in the changelog.
