# GUI Overview

The graphical interface is intended for interactive analysis and result review.

## Main workflow

The GUI lets users select:

- input FASTA/FASTQ file or directory
- output directory
- primer FASTA file
- number of worker threads
- allowed primer mismatches
- BLAST e-value

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
