# GUI Overview

The graphical interface is intended for interactive analysis and result review.

## Main workflow

The GUI lets users select:

- input FASTA/FASTQ file or directory (**Select ▾** → **File…** or **Folder…**, or drag it onto the field; files and folders are told apart automatically)
- primer FASTA file
- output directory
- number of worker threads
- allowed primer mismatches
- BLAST e-value

Two buttons above the log open the remaining settings. Each dialog has **Defaults**, checks values on **OK**, and a ` *` on the button means a value differs from the default; hover the button for a summary.

| Dialog | Settings | Command-line equivalents |
|---|---|---|
| **Amplicon settings…** | probe mismatches (blank = same as primers), 3' window, max 3' penalty, max 5' unaligned bases, min and max amplicon length | `--probe-mismatches`, `--3p-window`, `--3p-penalty`, `--5p-truncation`, `--min-length`, `--max-length` |
| **FASTQ settings…** | Tadpole k-mer length, min k-mer count to seed, min contig coverage, min contig length; min share of informative reads for read-level calls | `--tadpole-k`, `--tadpole-min-seed`, `--tadpole-min-cov`, `--tadpole-min-contig`, `--read-min-fraction` |

See [Amplicon Calling](Amplicon-Calling) and [FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives) for what they do.

After the inputs are selected, click **Run** to start the analysis.

## Progress and logs

The application displays progress and log output during the run.

On Linux, starting the launcher from a terminal is recommended because startup messages and errors are also visible in the console.

## Post-run actions

After a run finishes, the GUI can provide actions such as:

- opening report files
- opening previous run folders
- viewing synthetic gel images

## Synthetic gel viewer

The synthetic gel viewer displays consolidated report results as gel-like bands. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer).
