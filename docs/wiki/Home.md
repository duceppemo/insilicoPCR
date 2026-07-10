# insilicoPCR Wiki

Welcome to the insilicoPCR Wiki.

This is the detailed documentation for users and developers. The repository README is intentionally compact; the Wiki should be the main manual for installing, running, troubleshooting, and developing the application.

> This `docs/wiki/` directory is a source-controlled mirror of the GitHub Wiki content. Once the GitHub Wiki is initialized, these pages can be copied or pushed into the `insilicoPCR.wiki` repository.

## Getting Started

- [Installation](Installation.md)
- [Portable Releases](Portable-Releases.md)
- [First Analysis](First-Analysis.md)

## User Guide

- [GUI Overview](GUI-Overview.md)
- [Running Analyses](Running-Analyses.md)
- [Primer FASTA Format](Primer-FASTA-Format.md)
- [Standard PCR vs qPCR](Standard-PCR-vs-qPCR.md)
- [Reports](Reports.md)
- [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer.md)
- [Command-line Interface](Command-line-Interface.md)
- [Troubleshooting](Troubleshooting.md)
- [FAQ](FAQ.md)

## Developer Guide

- [Developer Guide](Developer-Guide.md)
- [Architecture](Architecture.md)
- [Runtime Layout](Runtime-Layout.md)
- [Build System](Build-System.md)
- [Release Pipeline](Release-Pipeline.md)
- [Contributing](Contributing.md)

## What insilicoPCR does

insilicoPCR performs in silico PCR analysis against genome assemblies and sequencing reads.

Supported input types include:

- genome assemblies: `.fasta`, `.fa`, `.fna`
- sequencing reads: `.fastq`, `.fq`
- directories containing multiple FASTA or FASTQ files

The application supports both:

- a JavaFX graphical desktop interface
- a command-line interface for batch runs and pipelines

Portable releases include Java, JavaFX, BBMap, and NCBI BLAST+, so end users do not need to install these tools separately.
