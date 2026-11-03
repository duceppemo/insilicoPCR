# insilicoPCR Wiki

Welcome to the insilicoPCR Wiki.

This is the detailed documentation for users and developers. The repository README is intentionally compact; the Wiki should be the main manual for installing, running, troubleshooting, and developing the application.

> This `docs/wiki/` directory is a source-controlled mirror of the GitHub Wiki content. After editing pages here, publish them with `scripts/docs/publish-wiki.sh` (see [Publishing to GitHub Wiki](Publishing-to-GitHub-Wiki)).

## Getting Started

- [Installation](Installation)
- [Portable Releases](Portable-Releases)
- [First Analysis](First-Analysis)

## User Guide

- [User Guide](User-Guide)
- [GUI Overview](GUI-Overview)
- [Running Analyses](Running-Analyses)
- [Primer FASTA Format](Primer-FASTA-Format)
- [Standard PCR vs qPCR](Standard-PCR-vs-qPCR)
- [Amplicon Calling](Amplicon-Calling)
- [Reports](Reports)
- [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer)
- [Command-line Interface](Command-line-Interface)
- [Troubleshooting](Troubleshooting)
- [FAQ](FAQ)

## Developer Guide

- [Developer Guide](Developer-Guide)
- [Architecture](Architecture)
- [Runtime Layout](Runtime-Layout)
- [Build System](Build-System)
- [Release Pipeline](Release-Pipeline)
- [Contributing](Contributing)

## What insilicoPCR does

insilicoPCR performs in silico PCR analysis against genome assemblies and sequencing reads.

Supported input types include:

- genome assemblies: `.fasta`, `.fa`, `.fna`
- sequencing reads: `.fastq`, `.fq`
- directories containing multiple FASTA or FASTQ files

The application supports both:

- a JavaFX graphical desktop interface
- a command-line interface for batch runs and pipelines

Reported amplicons are restricted to primer pairs that could plausibly amplify in a real reaction: convergent orientation on opposite strands, a configurable product size limit, and a weighted 3'-end mismatch check. See [Amplicon Calling](Amplicon-Calling).

Portable releases include Java, JavaFX, BBMap, and NCBI BLAST+, so end users do not need to install these tools separately.
