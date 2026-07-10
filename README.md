# insilicoPCR

<p style="text-align: center;">
  <img src="docs/assets/banner.svg" alt="insilicoPCR banner" width="100%">
</p>

<p style="text-align: center;">
  <strong>Portable in silico PCR for genome assemblies (FASTA) and sequencing reads (FASTQ)</strong>
</p>

<p style="text-align: center;">
  <a href="../../actions"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/duceppemo/insilicoPCR/ci-portable-release.yml?branch=master&label=build"></a>
  <a href="../../releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/duceppemo/insilicoPCR?display_name=tag"></a>
  <a href="../../wiki"><img alt="Wiki" src="https://img.shields.io/badge/docs-Wiki-0ea5e9"></a>
  <img alt="Java" src="https://img.shields.io/badge/Java-26-blue">
  <img alt="JavaFX" src="https://img.shields.io/badge/JavaFX-26.0.1-0ea5e9">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Windows%20%7C%20Linux-14b8a6">
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/duceppemo/insilicoPCR"></a>
</p>

---

## Overview

**insilicoPCR** is a JavaFX desktop and command-line application for performing **in silico PCR** against **genome assemblies (FASTA)** and **sequencing reads (FASTQ)** using bundled **BBMap** and **NCBI BLAST+** runtimes.

It supports primer validation, standard PCR assays, qPCR/probe assays, multiplex workflows, consolidated reports, Excel reports, and a synthetic gel visualizer.

<p style="text-align: center;">
  <img src="docs/assets/workflow.svg" alt="insilicoPCR workflow" width="100%">
</p>

---

## Features

- Analyze genome assemblies: `.fasta`, `.fa`, `.fna`
- Analyze sequencing reads: `.fastq`, `.fq`
- Support single-end and paired-end FASTQ workflows
- Run standard PCR assays or qPCR/probe assays
- Use degenerate primers with IUPAC bases
- Detect candidate amplicons with BBMap and validate with BLAST+
- Generate detailed, consolidated, and Excel reports
- View results as a synthetic gel image
- Run through the graphical desktop app or CLI
- Use portable Windows and Linux ZIP releases with bundled Java, JavaFX, BBMap, and BLAST+

---

## Quick Start

Download the latest portable ZIP from the [Releases](https://github.com/duceppemo/insilicoPCR/releases) page.

| Operating system | Archive | Launcher |
|---|---|---|
| Linux | `insilicoPCR-<version>-linux-x64.zip` | `run-insilicoPCR.sh` |
| Windows | `insilicoPCR-<version>-windows-x64.zip` | `run-insilicoPCR.bat` |

### Linux GUI

```bash
unzip insilicoPCR-<version>-linux-x64.zip
cd insilicoPCR-linux-x64
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

Starting the Linux app from a terminal is recommended because startup messages and errors are shown directly in the console.

### Windows GUI

Extract the ZIP, open the extracted folder, and double-click:

```text
run-insilicoPCR.bat
```

The portable launchers configure the bundled Java runtime, JavaFX runtime, BBMap, and BLAST+ automatically. No separate installation of Java, JavaFX, BBMap, or BLAST+ is required.

---

## Command-line Usage

The portable release can also be used without launching the graphical interface.

```bash
cd insilicoPCR-linux-x64
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

Typical CLI run:

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies_or_reads/ \
  -p primers.fasta \
  -o results/ \
  -t 8 \
  -m 1
```

The `--input` path can be a single FASTA/FASTQ file or a directory containing FASTA/FASTQ files.

See the [Command-line Usage Wiki page](https://github.com/duceppemo/insilicoPCR/wiki/Command-line-Interface) for the full CLI reference.

---

## Primer FASTA Format

Primer files must be FASTA files using this assay naming convention:

| Suffix | Meaning |
|---|---|
| `-F` | Forward primer |
| `-R` | Reverse primer |
| `-P` | Probe |

Example standard PCR assay:

```fasta
>targetA-F
ATGCGTACGTTAGCTAGCTA
>targetA-R
TCGATCGATACGCGTACGTA
```

Example qPCR/probe assay:

```fasta
>targetB-F
GCTAGCTAGGATCGATCGAA
>targetB-R
TTCGATCGATCCTAGCTAGC
>targetB-P
ACGTTAGCTAGCTACGTA
```

**Do not mix standard PCR assays and qPCR/probe assays in the same primer FASTA file.** If any assay includes a `-P` probe, the consolidated report runs in qPCR mode and requires a probe hit for an assay to be reported positive.

See the [Primer FASTA Format Wiki page](https://github.com/duceppemo/insilicoPCR/wiki/Primer-FASTA-Format) for detailed examples and limitations.

---

## Synthetic Gel Visualizer

The synthetic gel visualizer displays consolidated PCR results as gel-like bands, making it easier to review amplicon size patterns across samples and targets.

See the [Synthetic Gel Visualizer Wiki page](https://github.com/duceppemo/insilicoPCR/wiki/Synthetic-Gel-Visualizer) for details.

---

## Documentation

Detailed documentation belongs in the GitHub **Wiki** tab:

- [insilicoPCR Wiki](https://github.com/duceppemo/insilicoPCR/wiki)
- [Installation](https://github.com/duceppemo/insilicoPCR/wiki/Installation)
- [First Analysis](https://github.com/duceppemo/insilicoPCR/wiki/First-Analysis)
- [Command-line Interface](https://github.com/duceppemo/insilicoPCR/wiki/Command-line-Interface)
- [Primer FASTA Format](https://github.com/duceppemo/insilicoPCR/wiki/Primer-FASTA-Format)
- [Synthetic Gel Visualizer](https://github.com/duceppemo/insilicoPCR/wiki/Synthetic-Gel-Visualizer)
- [Developer Guide](https://github.com/duceppemo/insilicoPCR/wiki/Developer-Guide)

A source-controlled mirror of the Wiki content is kept under [`docs/wiki/`](docs/wiki/) for review, history, and easy publishing into the GitHub Wiki repository.

---

## Building From Source

```bash
git clone https://github.com/duceppemo/insilicoPCR.git
cd insilicoPCR
./mvnw clean package
java -jar target/insilicoPCR.jar -h
```

Development requires Java 26. End users should normally use the portable releases.

---

## Architecture and Releases

<p style="text-align: center;">
  <img src="docs/assets/architecture.svg" alt="insilicoPCR architecture" width="100%">
</p>

<p style="text-align: center;">
  <img src="docs/assets/release-pipeline.svg" alt="insilicoPCR release pipeline" width="100%">
</p>

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

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.
