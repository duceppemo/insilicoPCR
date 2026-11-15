# Running Analyses

insilicoPCR can analyze genome assemblies and sequencing reads.

## Supported inputs

Supported sequence inputs include:

- assemblies: `.fasta`, `.fa`, `.fna`, `.ffn`, `.fsa`, plain or (since 0.12.1) gzipped
- reads: `.fastq`, `.fq`, plain or gzipped (`.fastq.gz`, `.fq.gz`)

The input can be a single file or a directory containing multiple sequence files. The output folder must not be the input folder (the run writes files there, which would be read as samples); paths may contain spaces (before 0.15.2, an output folder whose path had a space stopped every run at `makeblastdb`). In FASTQ mode, the name of a read file, and of a folder holding the reads but not the output folder, must not contain a comma: BBTools reads a comma in a file name as a list of files, and the run stops with a message naming the file to rename. A comma in the output folder's path, or in a folder above both the reads and the output, is fine since 0.15.2 (before, it stopped the run at Tadpole). FASTQ input may be a read pair or single-end reads (before 0.15.2, single-end reads were taken for interleaved pairs: a file with an odd number of reads stopped the run in BBDuk, and with an even number each baited read brought its unrelated neighbour along). Gzipped assemblies (`.fasta.gz`, `.fna.gz` and so on) are read since 0.12.1; with earlier releases, decompress them first. An assembly is read as BLAST reads it: only nucleotide letters (IUPAC codes and `U`, either case) are bases, and anything else in a sequence line (alignment gaps `-` and `.`, digits, `*`) is left out, so positions agree with BLAST's (before 0.15.2 such characters shifted them).

## Sample names

Each sample is named after its file, without the extension (in any case: `ISO2.FASTA` is the sample `ISO2`). A sample is one assembly, one FASTQ file, or the two FASTQ files of a read pair:

- `_R1`/`_R2`, with or without `_001` (`s_R1_001.fastq.gz`, `s_R2_001.fastq.gz`), make the pair `s`;
- `_1`/`_2` (`SRR123_1.fastq.gz`, `SRR123_2.fastq.gz`, as the ENA and SRA name them) make the pair `SRR123` when both files are there and no `_3` file says the names are numbered samples;
- an assembly keeps these endings: `strain_R1.fasta` is the sample `strain_R1`, and `strain_R2.fasta` another one.

Before 0.15.1, an assembly whose name ended with `_R1`, `_R2` or `_assembly`, or had an upper-case extension, was searched but reported with no product, whatever it held.

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
| An assembly in which two sequences share an ID (assemblies joined into one file, each with contigs named `1`, `2`, ...); since 0.15.2 (with `--input-check quick` too): before, a primer on one and a primer on the other could be paired into a product of two molecules | `two sequences share an ID (the ID 1 names more than one sequence; ...)` |
| Files with the same sample name that are not a read pair (`x.fasta` and `x.fastq`, `x.fasta` and `x.fna`) | `these files have the same sample name (a sample is one FASTA file, ...)` |
| Sample names that differ only in case (`iso2.fasta`, `ISO2.fna`); their results would share a folder on Windows and macOS | `the sample name differs only in case from another one (ISO2, iso2 would share a results folder; rename one)` |

A sample is left out as a whole: when one file of a read pair is faulty, the other is not run as single-end reads.

The faults are reported in four places:

- as `Warning:` lines on the terminal, or in the output area of the application;
- in `QAlog.txt`, under `Input not analysed`, with one `Sample not analysed: <name>` line per sample;
- at the top of the HTML report, in a section **Input not analysed** and a count among the summary tiles. The samples are not in the tables of the report: nothing was run on them, so they are neither positive nor negative;
- such a sample has no lane in the synthetic gel.

The run itself still ends normally (exit code 0), also when no sample is left to analyse: the reports are then empty apart from that section. An input with no FASTA or FASTQ file at all (an empty folder, a mistyped `-i`) is not run: it stops with `No FASTA or FASTQ file in ...: nothing to analyse` and exit code 1 (before 0.15.2, it ended with empty reports and exit code 0, which a workflow manager took for success). The command line also refuses arguments it does not expect (exit code 2): a shell pattern such as `-i reads/*.fastq.gz` gives `-i` only its first file, so give the folder that holds the files instead (before 0.15.2, the other files were silently left out).

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
| `QAlog.txt` | Version, inputs, the samples analysed, the report written and the settings that decide calls; written when a run finishes |
| `primer_tmp.fasta*` | The primers as a BLAST database; the application deletes it no longer, since 0.14.0 |
| `synthetic_gel_report.png` | With `--gel` only: the synthetic gel, in the output folder itself, named after the report (`synthetic_gel_report(1).png` for `report(1).tsv`) |
| `log.txt` | Application only: what the window showed of each run, added at the end of the run, also one that failed or was stopped |

Give each run its own output folder when runs go at the same time (a workflow manager running one job per sample): a run holds its output folder while it runs (through an empty file, `.insilicoPCR.lock`, left in the folder; the system lets go of it when the run ends, however it ends), and a second run into it stops at once with `Another run is using the output folder` (exit code 1); before 0.15.2 such runs overwrote each other's files and failed at random, or searched each other's primers. The hidden `.insilicoPCR.lock` file in the folder is what holds it; it is left there. A run into a folder that already holds a report writes the report numbered after the highest one there (`report(1).tsv`, `report(1).html` and its log `QAlog(1).txt`, and so on): each report keeps the log of the run that wrote it, and **Open Previous Run** opens the latest (before 0.15.2, the second run replaced `QAlog.txt`, and the first report could no longer be reopened). The files under `detailed_report/` are those of the latest run: a sample analysed again has its files replaced. See [Reports](Reports).
