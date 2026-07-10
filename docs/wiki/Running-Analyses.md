# Running Analyses

insilicoPCR can analyze genome assemblies and sequencing reads.

## Supported inputs

Supported sequence inputs include:

- `.fasta`
- `.fa`
- `.fna`
- `.fastq`
- `.fq`

The input can be a single file or a directory containing multiple sequence files.

## Standard PCR analysis

Use a primer FASTA where each assay has:

- one `-F` forward primer
- one `-R` reverse primer

Example:

```fasta
>targetA-F
ATGCGTACGTTAGCTAGCTA
>targetA-R
TCGATCGATACGCGTACGTA
```

## qPCR/probe analysis

Use a primer FASTA where each assay has:

- one `-F` forward primer
- one `-R` reverse primer
- one `-P` probe

Example:

```fasta
>targetB-F
GCTAGCTAGGATCGATCGAA
>targetB-R
TTCGATCGATCCTAGCTAGC
>targetB-P
ACGTTAGCTAGCTACGTA
```

## Do not mix PCR modes

Do not mix assays with probes and assays without probes in the same primer FASTA file.

If any assay includes a `-P` probe, the consolidated report runs in qPCR mode and requires probe support for positive reporting.

## Parameters

| Parameter | Purpose |
|---|---|
| Threads | Number of worker threads |
| Mismatches | Allowed primer mismatches |
| E-value | BLASTN e-value threshold |

## Outputs

Outputs vary by run, but typically include logs, detailed report files, consolidated reports, and files used by the synthetic gel visualizer.
