# insilicoPCR User Guide

## Installation

No installation is required.

Extract the release archive and run:

Linux

```
./run.sh
```

Windows

```
run.bat
```

The application automatically detects the bundled Java runtime, JavaFX, BLAST+, and BBMap.

---

## Input Files

Supported formats

* FASTA
* FASTQ
* gzipped FASTA
* gzipped FASTQ

Primer files may contain degenerate bases using IUPAC notation.

---

## Running Analyses

### Genome assemblies

Select one or more FASTA files.

### Raw sequencing reads

Select paired or single FASTQ files.

Assembly will be performed automatically when required.

---

## Output

The application produces

* PCR product table
* Summary report
* BLAST results
* Amplicon sequences
* Virtual gel image

---

## Command Line

Example

```
java -jar insilicoPCR.jar \
    --primers primers.csv \
    --input genomes \
    --output results
```

Use

```
--help
```

to display all available options.

---

## Troubleshooting

### BLAST not found

Verify the bundled runtime directory contains the BLAST executables.

### Java runtime not found

Ensure the bundled JDK is located inside the release package.

### Large datasets

Allocate additional memory using

```
-Xmx16G
```
