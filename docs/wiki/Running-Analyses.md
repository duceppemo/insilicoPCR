# Running Analyses

insilicoPCR can analyze genome assemblies and sequencing reads.

## Supported inputs

Supported sequence inputs include:

- assemblies: `.fasta`, `.fa`, `.fna`, `.ffn`, `.fsa`, plain or (since 0.12.1) gzipped
- reads: `.fastq`, `.fq`, plain or gzipped (`.fastq.gz`, `.fq.gz`)

The input can be a single file or a directory containing multiple sequence files. Gzipped assemblies (`.fasta.gz`, `.fna.gz` and so on) are read since 0.12.1; with earlier releases, decompress them first.

## Input check

Since 0.13.0 every input file is checked before anything is run on it. A sample with a faulty file is **left out with a warning and the other samples are run**; before, such a file was skipped without a word, run as half of a read pair, or stopped the whole run inside BBDuk.

| Fault | Example of what is reported |
|---|---|
| Empty file, or an empty gzip stream (a library that gave no read) | `the file holds no read (the file is empty)` |
| Not gzip, or a gzip stream that is damaged or cut short (an interrupted download or copy) | `the file cannot be read (the gzip stream ends early, after 331 complete reads: the file is incomplete)` |
| A file that ends inside a read | `the file is incomplete (it ends inside read 501, after 500 complete reads)` |
| A read that is not four lines of the expected kind: no `@` header, no `+` line, a quality line of another length, a character that is not a base or a quality score, a blank line between reads | `a read is not well formed (read 10 (line 40): the quality line has 100 characters for 150 bases)` |
| Not a FASTQ file under a `.fastq` name | `not a FASTQ file` |
| The two files of a read pair with different numbers of reads | `the two files of the pair hold different numbers of reads (695 and 600)` |
| An assembly that is empty, holds header lines only, does not start with `>`, or whose gzip stream is cut short | `the file holds no sequence (header lines only)` |

A sample is left out as a whole: when one file of a read pair is faulty, the other is not run as single-end reads.

The faults are reported in four places:

- as `Warning:` lines on the terminal, or in the output area of the application;
- in `QAlog.txt`, under `Input not analysed`, with one `Sample not analysed: <name>` line per sample;
- at the top of the HTML report, in a section **Input not analysed** and a count among the summary tiles. The samples are not in the tables of the report: nothing was run on them, so they are neither positive nor negative;
- such a sample has no lane in the synthetic gel.

The run itself still ends normally (exit code 0), also when no sample is left to analyse: the reports are then empty apart from that section.

Checking every read means reading each FASTQ file once more, which takes about as long as decompressing it (about half a minute for a 1.2 GB `.fastq.gz` of 10 million reads; files are checked in parallel, one per thread). On the command line, `--input-check quick` reads the first read of each file only: it still catches empty files and files of the wrong kind, but not a file that is damaged or cut short further on, and it does not compare the two files of a pair. The application always checks every read.

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
| Threads | Number of worker threads; FASTQ samples processed at once share them, and the memory: see [Threads and memory in FASTQ mode](Command-line-Interface#threads-and-memory-in-fastq-mode) |
| Mismatches | Allowed mismatches + indels per primer (3' bases that do not pair with the template count) |
| E-value | BLASTN e-value threshold |
| Amplicon settings… | Probe mismatches, 3'-end check, 5' truncation, min/max amplicon length (default max 5000; 0 disables the limit) |
| FASTQ settings… | Tadpole mini-assembly settings, the read-level call threshold and the read extension |

Every GUI setting has a command-line equivalent; see [Command-line Interface](Command-line-Interface), [Amplicon Calling](Amplicon-Calling) and [GUI Overview](GUI-Overview).

## Outputs

| In the output folder | What it is |
|---|---|
| `consolidated_report/report.html` | [HTML report](Reports#html-report): which samples are positive for which assay, then every amplicon |
| `consolidated_report/report.tsv` | One row per amplicon; the source of the HTML report and of the synthetic gel |
| `consolidated_report/report_fastq_diagnostics.tsv` | FASTQ input only: why an assay was not reported although its primers are in the reads |
| `detailed_report/<sample>/` | Raw BLAST hits per sample and, for FASTQ input, the baited reads and mini-assembly |
| `QAlog.txt` | Version, inputs and the settings that decide calls |
| `primer_tmp.fasta*` | The primers as a BLAST database; the application deletes it no longer, since 0.14.0 |
| `log.txt` | Application only: the run log, written when the window is closed |

A run into a folder that already holds a report writes `report(1).tsv` and `report(1).html`, and so on. See [Reports](Reports).
