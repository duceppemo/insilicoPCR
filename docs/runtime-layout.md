# Runtime layout

insilicoPCR uses a deterministic portable runtime layout. CI no longer downloads BBMap or BLAST from external mirrors during release packaging.

```text
runtime/
  common/
    bbmap/
      bbduk.sh / bbduk.bat
      bbmap.sh / bbmap.bat
      tadpole.sh / tadpole.bat
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

The bundled JDK is intentionally not committed. GitHub Actions installs JDK 26, then the packaging scripts copy `JAVA_HOME` into the generated ZIP as `runtime/<platform>/jdk`.

## Why tools are committed

BBMap and BLAST are application runtime assets. Downloading them during every CI run makes releases depend on SourceForge and NCBI mirror availability, redirects, HTML error pages, and changing `latest` artifacts. Keeping known-good tool builds in the repository makes releases reproducible.

## What should not be committed

Do not commit generated release output, Maven output, IDE output, JavaFX SDK folders, or JDK/JRE folders. The `.gitignore` allows the curated BBMap/BLAST runtime folders while excluding generated runtimes.

## Updating BBMap or BLAST

1. Replace the files under the relevant `runtime/` folders.
2. Run the platform packaging script locally.
3. Confirm that the generated ZIP contains the expected layout.
4. Commit the runtime tool update together with a note in the changelog.
