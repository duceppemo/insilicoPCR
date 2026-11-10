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

Standard PCR assays and qPCR/probe assays can be mixed in the same primer FASTA file (since version 0.8.0). Assays with a `-P` probe require probe support inside the amplicon; assays without a probe are reported on their primer pair alone.

## Parameters

| Parameter | Purpose |
|---|---|
| Threads | Number of worker threads |
| Mismatches | Allowed mismatches + indels per primer (unaligned 3' bases count) |
| E-value | BLASTN e-value threshold |
| Amplicon settings… | Probe mismatches, 3'-end check, 5' truncation, min/max amplicon length (default max 5000; 0 disables the limit) |
| FASTQ settings… | Tadpole mini-assembly settings and the read-level call threshold |

Every GUI setting has a command-line equivalent; see [Command-line Interface](Command-line-Interface), [Amplicon Calling](Amplicon-Calling) and [GUI Overview](GUI-Overview).

## Outputs

| In the output folder | What it is |
|---|---|
| `consolidated_report/report.html` | [HTML report](Reports#html-report): which samples are positive for which assay, then every amplicon |
| `consolidated_report/report.tsv` | One row per amplicon; the source of the HTML report and of the synthetic gel |
| `consolidated_report/report_fastq_diagnostics.tsv` | FASTQ input only: why an assay was not reported although its primers are in the reads |
| `detailed_report/<sample>/` | Raw BLAST hits per sample and, for FASTQ input, the baited reads and mini-assembly |
| `QAlog.txt` | Version, inputs and the settings that decide calls |

A run into a folder that already holds a report writes `report(1).tsv` and `report(1).html`, and so on. See [Reports](Reports).
