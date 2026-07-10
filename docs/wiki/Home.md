# insilicoPCR Wiki

Welcome to the insilicoPCR documentation.

This directory is the source-controlled version of the project Wiki. The README is intentionally compact; detailed user and developer documentation lives here.

## User documentation

- [User Guide](User-Guide.md)
- [Command-line Usage](Command-line-Usage.md)
- [Primer FASTA Format](Primer-FASTA-Format.md)
- [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer.md)

## Developer documentation

- [Developer Guide](Developer-Guide.md)
- [Runtime Layout](../runtime-layout.md)
- [Release Process](../release-process.md)

## What insilicoPCR does

insilicoPCR performs in silico PCR analysis against genome assemblies and sequencing reads.

Supported input types include:

- genome assemblies: `.fasta`, `.fa`, `.fna`
- sequencing reads: `.fastq`, `.fq`
- directories containing multiple FASTA or FASTQ files

The application supports both:

- a JavaFX graphical desktop interface
- a command-line interface for batch runs and pipelines

Portable releases include the Java runtime, JavaFX, BBMap, and NCBI BLAST+, so end users do not need to install these tools separately.
