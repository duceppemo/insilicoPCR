# Reports

insilicoPCR produces report files that summarize primer hits, predicted amplicons, and consolidated results across samples.

## Report types

Common output types include:

- runtime and quality logs
- detailed per-sample reports
- consolidated TSV report
- Excel reports
- files used by the synthetic gel visualizer

## Consolidated report

The consolidated report is the primary summary table for reviewing positive results across samples and assays.

The exact columns depend on the reporting mode.

### Standard PCR mode

Standard PCR mode reports forward/reverse primer-supported amplicons.

Typical information includes:

- sample
- gene or assay name
- genome location
- amplicon size
- contig
- forward and reverse primer names
- mismatch information

### qPCR/probe mode

qPCR/probe mode adds probe-related information.

Typical additional information includes:

- probe name
- probe location
- probe size
- probe mismatches

## Important mode behavior

If any primer ID includes `-P`, the consolidated report is generated in qPCR mode. Standard PCR assays without probes should be run separately from qPCR assays.

## Synthetic gel

The synthetic gel visualizer reads consolidated result data and displays predicted amplicons as gel-like bands. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer.md).
