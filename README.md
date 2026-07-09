# insilicoPCR

<p style="text-align: center;">
  <img src="docs/assets/banner.svg" alt="insilicoPCR banner" width="100%">
</p>

<p style="text-align: center;">
  <strong>Modern cross-platform in silico PCR analysis for genome assemblies (FASTA) and sequencing reads (FASTQ)</strong>
</p>

<p style="text-align: center;">
  <a href="../../actions"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/duceppemo/insilicoPCR/ci-portable-release.yml?branch=master&label=build"></a>
  <a href="../../releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/duceppemo/insilicoPCR?display_name=tag"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-26-blue">
  <img alt="JavaFX" src="https://img.shields.io/badge/JavaFX-26.0.1-0ea5e9">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux-14b8a6">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/duceppemo/insilicoPCR"></a>
</p>

---

## Table of Contents

- [Overview](#overview)
- [Why insilicoPCR?](#why-insilicopcr)
- [Features](#features)
- [Screenshots](#screenshots)
- [Scientific Workflow](#scientific-workflow)
- [Architecture](#architecture)
- [Performance and Modernization](#performance-and-modernization)
- [Quick Start: Portable App](#quick-start-portable-app)
- [Command-line Interface](#command-line-interface)
- [Primer FASTA Format](#primer-fasta-format)
- [Building From Source](#building-from-source)
- [Portable Releases](#portable-releases)
- [Runtime Layout](#runtime-layout)
- [Repository Structure](#repository-structure)
- [Documentation](#documentation)
- [CI/CD](#cicd)
- [Roadmap](#roadmap)
- [Third-Party Software](#third-party-software)
- [Citation](#citation)
- [License](#license)

---

## Overview

**insilicoPCR** is a modern JavaFX desktop and command-line application for performing **in silico PCR** against **genome assemblies (FASTA)** and **high-throughput sequencing reads (FASTQ)** using **BBMap** and **NCBI BLAST+**.

It is designed for researchers, diagnostic laboratories, surveillance programs, and microbial genomics workflows that need a reproducible way to validate primers, evaluate multiplex PCR assays, detect expected amplicons, visualize synthetic gels, and generate reviewable reports.

insilicoPCR supports both assembled genomes and sequencing reads, allowing the same primer sets to be evaluated against finished assemblies or directly against raw sequencing datasets.

Unlike ad hoc command-line workflows, insilicoPCR provides an integrated desktop experience while still packaging the underlying scientific tools in a deterministic, portable runtime.

---

## Why insilicoPCR?

insilicoPCR combines BBMap and BLAST+ into a reproducible desktop and CLI workflow, eliminating manual command-line processing while producing structured, reviewable reports.

It is especially useful when users need to:

- screen genome assemblies against one or more primer sets;
- analyze sequencing reads directly when an assembly is not available;
- validate predicted amplicons;
- support standard PCR and qPCR-style probe workflows;
- inspect results using reports and the synthetic gel visualizer;
- generate Excel reports for downstream review;
- run the same workflow on Windows and Linux without installing Java, BBMap, or BLAST+ separately.

---

## Features

| Feature | Status |
|---|:---:|
| Genome assembly analysis (`.fasta`, `.fa`, `.fna`) | ✅ |
| Sequencing read analysis (`.fastq`, `.fq`) | ✅ |
| Single-end FASTQ workflows | ✅ |
| Paired-end FASTQ workflows | ✅ |
| Primer pair analysis | ✅ |
| Standard PCR mode | ✅ |
| qPCR/probe mode | ✅ |
| Multiplex PCR workflows | ✅ |
| Degenerate primer support | ✅ |
| BBMap candidate search | ✅ |
| NCBI BLAST+ validation | ✅ |
| Amplicon detection | ✅ |
| Product size validation | ✅ |
| Primer quality assessment | ✅ |
| Synthetic gel visualizer | ✅ |
| Excel report generation | ✅ |
| Consolidated reports | ✅ |
| Graphical desktop interface | ✅ |
| Command-line interface | ✅ |
| Windows portable ZIP | ✅ |
| Linux portable ZIP | ✅ |
| Bundled OpenJDK 26 | ✅ |
| Bundled JavaFX 26.0.1 | ✅ |
| No separate Java, JavaFX, BBMap, or BLAST+ installation required | ✅ |

---

## Screenshots

> Add screenshots under `docs/screenshots/` when available.

| Main Window | Results |
|---|---|
| `docs/screenshots/main-window.png` | `docs/screenshots/results.png` |

| Synthetic Gel | Consolidated Report |
|---|---|
| `docs/screenshots/synthetic-gel.png` | `docs/screenshots/consolidated.png` |

---

## Scientific Workflow

<p style="text-align: center;">
  <img src="docs/assets/workflow.svg" alt="insilicoPCR scientific workflow" width="100%">
</p>

The workflow follows a practical analysis path:

1. Load primer definitions.
2. Load genome assemblies (FASTA) and/or sequencing reads (FASTQ).
3. Search input sequences for candidate primer matches using BBMap.
4. Detect candidate amplicons and validate product sizes.
5. Confirm sequence-level results using BLAST+ where applicable.
6. Perform quality assessment and summarize hits.
7. Generate detailed reports, consolidated reports, and synthetic gel visualizations.

---

## Architecture

<p style="text-align: center;">
  <img src="docs/assets/architecture.svg" alt="insilicoPCR application architecture" width="100%">
</p>

The application separates the JavaFX user interface from the core analysis workflow, external tool runners, parsers, domain model, gel viewer, and report writers.

This keeps the codebase easier to test, optimize, and maintain while preserving compatibility with existing scientific outputs.

---

## Performance and Modernization

Version `0.6` represents a major modernization of the original application.

| Area | Improvement |
|---|---|
| Java platform | Migrated to Java 26 |
| UI toolkit | Migrated to JavaFX 26.0.1 |
| Filesystem API | Migrated from `File` to `Path` |
| Runtime detection | Modern deterministic runtime layout |
| External tools | Curated BBMap and BLAST+ runtime assets |
| Release process | Portable ZIP releases through GitHub Actions |
| Code structure | Improved separation of concerns |
| Maintainability | Cleaner services, runners, parsers, and report writers |
| Performance | Reduced filesystem overhead and improved object lifetimes |
| Concurrency | Safer multithreaded processing where appropriate |

Scientific behavior is intended to remain compatible with previous versions while improving maintainability, portability, and long-term project health.

---

## Quick Start: Portable App

The recommended way to run insilicoPCR is to use the portable release for your operating system.

Portable releases include:

- the insilicoPCR application;
- a bundled OpenJDK 26 runtime;
- the JavaFX 26.0.1 runtime;
- BBMap;
- NCBI BLAST+;
- platform-specific launcher scripts.

You do **not** need to install Java, JavaFX, BBMap, or BLAST+ separately.

### 1. Download a release

Download the latest portable ZIP from the [Releases](https://github.com/duceppemo/insilicoPCR/releases) page.

Choose the archive that matches your operating system:

| Operating system | Release archive |
|---|---|
| Linux | `insilicoPCR-<version>-linux-x64.zip` |
| Windows | `insilicoPCR-<version>-windows-x64.zip` |

### 2. Unzip the archive

Extract the ZIP file to a writable folder, for example:

| Operating system | Example location |
|---|---|
| Linux | `~/Applications/insilicoPCR/` |
| Windows | `C:\Users\<your-user-name>\Applications\insilicoPCR\` |

Keep the extracted folder intact. Do not move files out of the release folder, because the launchers expect the bundled runtime tools to stay beside the application.

Do **not** run the application directly from inside the compressed ZIP archive. Extract it first.

### 3. Start the graphical application

#### Linux

Open a terminal in the extracted release folder and run:

```bash
./run-insilicoPCR.sh
```

Running insilicoPCR from a terminal on Linux is recommended because startup messages and errors are displayed directly in the console, which makes troubleshooting easier.

If the launcher is not executable after unzipping, run this once:

```bash
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

#### Windows

Open the extracted release folder and double-click:

```text
run-insilicoPCR.bat
```

You can also start it from Command Prompt or PowerShell:

```powershell
.\run-insilicoPCR.bat
```

The launcher scripts automatically configure the bundled Java runtime, JavaFX runtime, BBMap, and BLAST+ before starting the application.

### Troubleshooting startup

- Make sure you downloaded the ZIP for the correct operating system.
- Extract the ZIP before running the launcher. Do not run the application directly from inside the compressed archive.
- Keep the bundled `runtime/` folder next to the launcher.
- Use `run-insilicoPCR.sh` on Linux and `run-insilicoPCR.bat` on Windows.
- On Linux, start the application from a terminal so startup messages are visible.
- On Linux, make sure the shell launcher has executable permission.
- On Windows, if SmartScreen warns about an unsigned application, choose **More info** and then **Run anyway** only if you downloaded the release from this repository.

---

## Command-line Interface

insilicoPCR can also be run entirely from the terminal without launching the graphical interface.

This is useful for batch processing, automation, remote Linux systems, HPC environments, and workflow managers such as Nextflow or Snakemake.

Use the bundled Java runtime from the portable release so the command does not depend on a system Java installation.

### Show CLI help

From inside the extracted Linux portable release folder:

```bash
cd insilicoPCR-linux-x64
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

This prints:

```text
usage: java -jar insilicoPCR.jar -i <input> -o <output> -p <primers> [-t
            n] [-m n] [-e value]
 -e,--evalue <value>   blastn e-value; default: 1e5
 -h,--help             Print help and usage
 -i,--input <path>     Input file/directory containing .fasta/.fastq
                       sequence(s)
 -m,--mismatches <n>   Allowed primer mismatches; default: 0
 -o,--output <dir>     Directory for output files
 -p,--primers <file>   Primer FASTA file
 -t,--threads <n>      Number of worker threads; default: available
                       processors
 -v,--version          Print version
```

### Example CLI runs

Run against a folder of genome assemblies:

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies/ \
  -p primers.fasta \
  -o results/
```

Run against a folder of sequencing reads:

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i reads/ \
  -p primers.fasta \
  -o results/ \
  -t 8 \
  -m 1
```

The `--input` path can be a single FASTA/FASTQ file or a directory containing FASTA/FASTQ sequence files.

---

## Primer FASTA Format

Primer definitions must be provided as a FASTA file.

Each primer ID must end with one of these suffixes:

| Suffix | Meaning |
|---|---|
| `-F` | Forward primer |
| `-R` | Reverse primer |
| `-P` | Probe |

The text before the final suffix is treated as the assay or target name. Forward, reverse, and probe entries belonging to the same assay must share the same base name.

### Standard PCR mode

Use standard PCR mode when each assay has a forward and reverse primer only.

```fasta
>toxA-F
ATGCGTACGTTAGCTAGCTA
>toxA-R
TCGATCGATACGCGTACGTA

>toxB-F
GCTAGCTAGGATCGATCGAA
>toxB-R
TTCGATCGATCCTAGCTAGC
```

### qPCR/probe mode

Use qPCR mode when each assay has a forward primer, reverse primer, and probe.

```fasta
>toxA-F
ATGCGTACGTTAGCTAGCTA
>toxA-R
TCGATCGATACGCGTACGTA
>toxA-P
FAMACGTTAGCTAGCTACGTABHQ1

>toxB-F
GCTAGCTAGGATCGATCGAA
>toxB-R
TTCGATCGATCCTAGCTAGC
>toxB-P
FAMCGATCGATCCTAGCTAGCBHQ1
```

> **Important:** Do not mix standard PCR assays and qPCR/probe assays in the same primer FASTA file.
>
> If any primer ID includes a `-P` probe, the consolidated report is generated in qPCR mode. In qPCR mode, an assay is only reported as positive when the forward primer, reverse primer, and probe are detected in the expected amplicon. Standard PCR assays without probes should be run separately from qPCR assays.

Primer sequences may contain standard IUPAC degenerate bases such as `R`, `Y`, `S`, `W`, `K`, `M`, `B`, `D`, `H`, `V`, and `N`.

---

## Building From Source

### Requirements

- Java 26
- Maven 3.9+

### Clone

```bash
git clone https://github.com/duceppemo/insilicoPCR.git
cd insilicoPCR
```

### Build

```bash
./mvnw clean package
```

### Run

```bash
java -jar target/insilicoPCR.jar
```

Building from source requires the development dependencies and runtime assets expected by the project. End users should normally use the portable releases instead.

---

## Portable Releases

Portable release archives are built for distribution and are intended for users who do not want to install Java or configure external command-line tools manually.

Each portable release contains:

- insilicoPCR application;
- bundled OpenJDK 26 runtime;
- bundled JavaFX runtime;
- BBMap;
- NCBI BLAST+;
- platform-specific launcher script:
  - `run-insilicoPCR.sh` for Linux;
  - `run-insilicoPCR.bat` for Windows.

### Building a Linux portable ZIP

```bash
export JAVA_HOME=/path/to/jdk-26
scripts/release/package-linux-portable.sh 0.6.0
```

The expected output is:

```text
release/insilicoPCR-<version>-linux-x64.zip
release/insilicoPCR-<version>-linux-x64.zip.sha256
```

### Building a Windows portable ZIP

Run from PowerShell with `JAVA_HOME` pointing to the Windows JDK 26 that should be bundled:

```powershell
scripts/release-windows.ps1
```

The expected output is:

```text
release/insilicoPCR-<version>-windows-x64.zip
release/insilicoPCR-<version>-windows-x64.zip.sha256
```

---

## Runtime Layout

The project uses a deterministic portable runtime layout.

```text
runtime/
├── common/
│   └── bbmap/
│
├── linux/
│   ├── blast/
│   │   └── bin/
│   ├── jdk/
│   └── javafx/
│
└── windows/
    ├── blast/
    │   └── bin/
    ├── jdk/
    └── javafx/
```

BBMap and NCBI BLAST+ are treated as curated application runtime assets.

The bundled JDK is generated or staged during packaging and is **not committed** to the repository.

Verify runtime assets with:

```bash
scripts/dev/verify-runtime-layout.sh
```

---

## Repository Structure

```text
.github/                 GitHub Actions workflows

docs/                    User, developer, release, and architecture documentation
  assets/                SVG diagrams and README graphics
  screenshots/           Application screenshots

runtime/                 Curated runtime assets used by portable builds
  common/bbmap/          Shared BBMap runtime
  linux/blast/           Linux BLAST+ runtime
  windows/blast/         Windows BLAST+ runtime

scripts/                 Development and release automation

src/main/java/           Java application source
src/main/resources/      Application resources
src/test/                Tests
```

---

## Documentation

| Document | Description |
|---|---|
| `docs/user_guide.md` | End-user workflow documentation |
| `docs/developer_guide.md` | Development notes and local setup |
| `docs/architecture.md` | Application architecture |
| `docs/runtime-layout.md` | Portable runtime layout |
| `docs/release-process.md` | Release process and packaging |
| `docs/cleanup-summary.md` | Modernization cleanup summary |

---

## CI/CD

<p style="text-align: center;">
  <img src="docs/assets/release-pipeline.svg" alt="insilicoPCR release pipeline" width="100%">
</p>

The release workflow validates checked-in runtime tools, builds the Java application, stages the runtime, and produces portable ZIP archives.

GitHub Actions does **not** download BBMap or BLAST+ during release builds.

---

## Roadmap

Planned future work includes:

- plugin architecture;
- improved primer visualization;
- additional report formats;
- enhanced batch workflow automation;
- automated primer QC summaries;
- improved statistics and diagnostics;
- additional developer-facing tests.

---

## Third-Party Software

| Software | Purpose |
|---|---|
| BBMap | Candidate primer mapping for FASTA/FASTQ inputs |
| NCBI BLAST+ | Sequence validation |
| OpenJDK | Java runtime |
| JavaFX | Desktop user interface |
| Apache Maven | Build system |

Please cite and respect the licenses of bundled third-party tools when distributing or publishing analyses.

---

## Citation

If you use insilicoPCR in published work, please cite this repository and the underlying tools used by the workflow.

```bibtex
@software{insilicopcr,
  title  = {insilicoPCR},
  author = {Duceppe, Marc-Olivier},
  url    = {https://github.com/duceppemo/insilicoPCR},
  year   = {2026}
}
```

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

---

<p style="text-align: center;">
  <strong>Modernized for Java 26 • Portable • Cross-platform • Open Source</strong>
</p>
