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

## Mixing PCR modes

Standard PCR assays and qPCR/probe assays can be mixed in the same primer FASTA file (since version 0.7.0). Assays with a `-P` probe require probe support inside the amplicon; assays without a probe are reported on their primer pair alone.

## Parameters

| Parameter | Purpose |
|---|---|
| Threads | Number of worker threads |
| Mismatches | Allowed primer mismatches |
| E-value | BLASTN e-value threshold |

## Outputs

Outputs vary by run, but typically include logs, detailed report files, consolidated reports, and files used by the synthetic gel visualizer.
