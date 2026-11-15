# Release Pipeline

The release process creates deterministic portable ZIP archives for Linux and Windows.

![insilicoPCR release pipeline](https://raw.githubusercontent.com/duceppemo/insilicoPCR/master/docs/assets/release-pipeline.svg)

Releases are fully automated: pushing a `v*` tag builds the Linux and Windows portable ZIPs in parallel and publishes them, with their SHA256 checksums, as a GitHub Release, which Zenodo archives with a DOI. Pushes to `master` and pull requests run the same build and tests without publishing (their ZIPs carry a placeholder version in their name, which the smoke test does not compare).

## CI release flow

1. Check out the repository.
2. Install JDK 26.
3. Validate runtime assets.
4. Build with Maven.
5. Stage the application, libraries, JDK, BBMap, and BLAST+.
6. Create portable ZIP files.
7. Create SHA256 checksum files.
8. Smoke test: run the ZIP that was just built, on Linux and on Windows.
9. Publish release artifacts for version tags.

## Version number

The version is written once, as `<version>` in `pom.xml`. The build copies it into `version.properties` (the one filtered resource, under `src/main/version`), which `util.Version` reads for `--version`, the window title, `QAlog.txt` and the reports; a test checks that the two agree. A release changes `pom.xml`, the heading in `CHANGELOG.md` and the example below.

## Smoke test

`scripts/ci/smoke-test.sh <portable ZIP>` extracts the ZIP, checks that its launcher (`run-insilicoPCR.sh`, or `run-insilicoPCR.bat` on Windows) reports the version the ZIP is named for (tag builds and local checks), and runs the program, through the launcher on Linux, with the bundled Java, BLAST+ and BBTools, into a folder with a space in its path, on the [five-isolate example](https://github.com/duceppemo/insilicoPCR/tree/master/docs/examples/five-isolates), two of the five genomes gzipped. It then runs FASTQ mode (BBDuk, BBMerge, Tadpole) on simulated reads of one of the genomes. It fails the build when `report.tsv` differs from the one kept with the example, when the reads do not give the two expected products at their sizes, or when a folder with faulty files (an assembly of header lines only, an empty library, a gzipped FASTQ file cut short) does not end with those files named in warnings, their samples left out and the good sample reported.

The same script checks a downloaded release:

```bash
scripts/ci/smoke-test.sh insilicoPCR-<version>-linux-x64.zip
```

## Linux packaging

```bash
export JAVA_HOME=/path/to/jdk-26
scripts/release/package-linux-portable.sh 0.15.1
```

Expected artifacts:

```text
release/insilicoPCR-<version>-linux-x64.zip
release/insilicoPCR-<version>-linux-x64.zip.sha256
```

## Windows packaging

```powershell
scripts/release-windows.ps1 -Version 0.15.1
```

Without `-Version`, the ZIP is named for the script's default, 0.8.0, whatever version it holds.

Expected artifacts:

```text
release/insilicoPCR-<version>-windows-x64.zip
release/insilicoPCR-<version>-windows-x64.zip.sha256
```

## Design principle

Release builds should not download BBMap or BLAST+ from external mirrors during CI. The runtime assets are validated from the repository checkout.
