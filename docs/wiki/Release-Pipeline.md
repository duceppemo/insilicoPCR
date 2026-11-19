# Release Pipeline

The release process creates portable ZIP archives for Linux and Windows. The Linux archive lists its entries in one order, each dated by the commit, and holds `VERSIONS.txt`, the versions of the Java runtime, BLAST+, BBTools and JavaFX it bundles; the program's jar inside it is dated by its build.

![insilicoPCR release pipeline](https://raw.githubusercontent.com/duceppemo/insilicoPCR/master/docs/assets/release-pipeline.svg)

Releases are fully automated: pushing a `v*` tag builds the Linux and Windows portable ZIPs in parallel and publishes them, with their SHA256 checksums, as a GitHub Release, which Zenodo archives with a DOI. Pushes to `master` and pull requests run the same build and tests without publishing; their ZIPs are named for the version in `pom.xml` (before 0.15.3, for a placeholder, 0.8.0).

## CI release flow

1. Check out the repository.
2. Install JDK 25 (LTS).
3. Validate runtime assets.
4. Build with Maven.
5. Stage the application, libraries, JDK, BBMap, and BLAST+ (and, for Linux, the application and libraries alone: the ZIP without a runtime).
6. Create portable ZIP files.
7. Create SHA256 checksum files.
8. Smoke test: run the ZIP that was just built, on Linux and on Windows, and the Linux ZIP without a runtime on conda's Java, BLAST+ and BBTools.
9. Windows test harness: run the Windows ZIP through the situations a Windows user meets (see below).
10. Publish release artifacts for version tags, once the steps above passed. The release's notes are `docs/release-notes/<version>.md`, when there is one (its highlights and what changes on upgrading), above the list of pull requests GitHub writes. Only this job may write to the repository; the workflow's other jobs, which run a pull request's code, only read it.

## Version number

The version is written once, as `<version>` in `pom.xml`. The build copies it into `version.properties` (the one filtered resource, under `src/main/version`), which `util.Version` reads for `--version`, the window title, `QAlog.txt` and the reports; a test checks that the two agree. A release changes `pom.xml`, the heading in `CHANGELOG.md` and the example below.

## Smoke test

`scripts/ci/smoke-test.sh <portable ZIP>` extracts the ZIP, checks that its launcher (`run-insilicoPCR.sh`, or `run-insilicoPCR.bat` on Windows) reports the version the ZIP is named for (every build but a manual CI run, which may name its ZIPs otherwise), and runs the program, through the launcher on Linux, with the bundled Java, BLAST+ and BBTools, into a folder with a space in its path, on the [five-isolate example](https://github.com/duceppemo/insilicoPCR/tree/master/docs/examples/five-isolates), two of the five genomes gzipped. It then runs FASTQ mode (BBDuk, BBMerge, Tadpole) on simulated reads of one of the genomes. It fails the build when `report.tsv` differs from the one kept with the example, when the reads do not give the two expected products at their sizes, or when a folder with faulty files (an assembly of header lines only, an empty library, a gzipped FASTQ file cut short) does not end with those files named in warnings, their samples left out and the good sample reported.

The same script checks a downloaded release:

```bash
scripts/ci/smoke-test.sh insilicoPCR-<version>-linux-x64.zip
```

## Linux packaging

```bash
export JAVA_HOME=/path/to/jdk-25
scripts/release/package-linux-portable.sh 1.0.2
```

Expected artifacts:

```text
release/insilicoPCR-<version>-linux-x64.zip
release/insilicoPCR-<version>-linux-x64.zip.sha256
```

### The ZIP without a runtime

After the Linux ZIP, `scripts/release/package-no-runtime.sh <version>` packs the same `target/insilicoPCR.jar` and `target/lib`, with a launcher that takes Java from `JAVA_HOME` or `PATH` (25 or later) and the licenses of the libraries it holds, without Java, BLAST+ and BBTools:

```text
release/insilicoPCR-<version>-linux-x64-no-runtime.zip
release/insilicoPCR-<version>-linux-x64-no-runtime.zip.sha256
```

It is what the Bioconda package is made from (see [Installation](Installation#without-the-bundled-programs-package-managers-containers)). CI job `conda-check` installs Java 25, BLAST+ 2.17.0 and BBTools 40.05 from conda-forge and Bioconda, with Miniforge, and runs the smoke test on this ZIP with them on `PATH`; the release waits for it, and publishes the ZIP beside the portable ones.

## Windows packaging

With PowerShell 7 (`pwsh`; `scripts\release-windows.bat` starts it when it is installed):

```powershell
scripts/release-windows.ps1 -Version 1.0.2
```

Windows PowerShell 5.1 is refused: it ran `./mvnw`, the Unix script, instead of `mvnw.cmd`, and its `Compress-Archive` writes ZIP entry names with backslashes. A failed Maven build stops the script, rather than packaging what an earlier build left in `target`.

Without `-Version` (or the version argument of `package-linux-portable.sh`), the ZIP is named for the version in `pom.xml` (before 0.15.2, for a fixed 0.8.0, whatever version it held). CI names a release's ZIPs for its tag, a manual run's for the version it is given, if any, and every other build's for the version in `pom.xml` (before 0.15.3, for 0.8.0).

Expected artifacts:

```text
release/insilicoPCR-<version>-windows-x64.zip
release/insilicoPCR-<version>-windows-x64.zip.sha256
```

## Windows test harness

`scripts/wintest/run-windows-tests.ps1 -Zip <Windows ZIP>` runs a Windows build about 70 ways, on the five-isolate example: the launcher from `cmd` and from PowerShell, installed and given data in folders whose names hold spaces, parentheses, accents, `&`, `%`, `!`, commas, an apostrophe or letters outside the code page; relative paths, forward slashes, a trailing backslash, a `subst` drive, a UNC path, paths past 260 characters; files as Windows tools write them (a byte order mark, CRLF line ends, `desktop.ini`); a copy marked as downloaded; reruns into one folder, a report held open as Excel holds it; and, on a 30 Mb genome and 600,000 read pairs it writes, two runs into one folder and a run killed as Task Manager kills it, whose programs must end with it. Each case is `PASS`, `FAIL`, `KNOWN` (a documented limit, see [Troubleshooting](Troubleshooting)), `INFO` or `SKIP`; the script exits with 1 when one fails.

CI runs it on the Windows ZIP of every build, pull requests included (job `windows-harness`, about 3 minutes); its results (`summary.tsv`, and each case's command, output and files) are the build's `wintest-results` artifact. On a Windows computer, from the `scripts\wintest` folder:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\run-windows-tests.ps1 -Zip insilicoPCR-<version>-windows-x64.zip
```

It writes only under its own folder (`work\`, `results\`), apart from a `subst` drive letter it removes; `-SkipBig` leaves out the large-input cases.

## Monthly run and dependency updates

On the first day of each month, CI runs on `master` as it stands, without publishing: the same build, tests, smoke tests and Windows harness, on the newest runner images, Java 25 update and conda packages. A failure there comes from something that changed outside the repository. GitHub mails it to whoever last changed the schedule in the workflow file. The monthly run and a manual run (Actions, *Run workflow*) also run job `newest-tools`:

- It runs the smoke test on the ZIP without a runtime with the newest Java, BLAST+ and BBTools the Bioconda recipe allows (Java 25 or later, BLAST+ 2.17.0 or later, BBTools 40.x), as a new conda installation gets them. `conda-check` keeps the bundled versions, so that a pull request does not fail for a change elsewhere.
- It reads `VERSIONS.txt` from the latest release's Linux ZIP (`scripts/ci/remote-zip-entry.py`, a few kilobytes of the ZIP) and compares it with the newest Temurin 25 update and the newest BLAST+ and BBTools on Bioconda (`scripts/ci/newer-versions.sh`). For those that are newer, it opens an issue, *Newer versions to bundle: …*, once for each set of versions. A release bundles the newest Java 25 update by itself. BLAST+ and BBTools are the copies under `runtime/`, which are replaced by hand.

GitHub turns off the schedule of a repository with no activity for 60 days; *Enable workflow* on the Actions page turns it back on.

Dependabot (`.github/dependabot.yml`) opens pull requests each month for newer libraries in `pom.xml` and newer GitHub Actions, which the workflows pin to commits. Minor and patch updates come in one pull request per ecosystem; a major version comes alone. CI tests each pull request as any other.

## Design principle

Release builds should not download BBMap or BLAST+ from external mirrors during CI. The runtime assets are validated from the repository checkout.
