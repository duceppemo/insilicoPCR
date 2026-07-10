# User Guide

This guide explains how to install and run insilicoPCR from the portable releases.

## Download and extract

1. Open the GitHub Releases page.
2. Download the archive for your operating system:
   - Linux: `insilicoPCR-<version>-linux-x64.zip`
   - Windows: `insilicoPCR-<version>-windows-x64.zip`
3. Extract the ZIP archive to a writable folder.
4. Keep the extracted folder intact. Do not move files out of the release folder.

Portable releases include:

- insilicoPCR
- OpenJDK 26 runtime
- JavaFX 26.0.1 runtime
- BBMap
- NCBI BLAST+
- platform launchers

No separate Java, JavaFX, BBMap, or BLAST+ installation is required.

## Starting the graphical application

### Linux

Open a terminal in the extracted release folder and run:

```bash
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

Running from a terminal is recommended on Linux because startup messages and errors are displayed directly in the console.

### Windows

Open the extracted release folder and double-click:

```text
run-insilicoPCR.bat
```

You can also run it from PowerShell:

```powershell
.\run-insilicoPCR.bat
```

## Inputs

insilicoPCR accepts genome assemblies and sequencing reads.

Supported examples:

- `.fasta`
- `.fa`
- `.fna`
- `.fastq`
- `.fq`

The input can be one sequence file or a directory containing sequence files.

## Primer file

The primer file must be a FASTA file using the special primer nomenclature described in [Primer FASTA Format](Primer-FASTA-Format.md).

## Standard PCR and qPCR mode

Run standard PCR assays and qPCR/probe assays separately.

- Standard PCR assays use `-F` and `-R` entries.
- qPCR/probe assays use `-F`, `-R`, and `-P` entries.

Do not mix assays with probes and assays without probes in the same primer FASTA file.

## Outputs

A typical run creates output files such as:

- quality/runtime logs
- detailed per-sample results
- consolidated reports
- Excel reports
- files used by the synthetic gel visualizer

## Synthetic gel visualizer

After a run completes, use the gel viewer button in the GUI to inspect a gel-like representation of the consolidated report.

The gel viewer can also open previous run folders or consolidated report TSV files when available. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer.md).

## Troubleshooting

If the application does not start:

- Make sure the ZIP was fully extracted.
- Make sure you downloaded the correct release for your operating system.
- On Linux, run the launcher from a terminal.
- On Linux, run `chmod +x run-insilicoPCR.sh` if needed.
- Keep the `runtime/` directory beside the launcher.
- On Windows, SmartScreen may warn about unsigned software. Only continue if the release was downloaded from the official repository.
