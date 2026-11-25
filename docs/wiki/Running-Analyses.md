# Running Analyses

insilicoPCR can analyze genome assemblies and sequencing reads.

## Supported inputs

Supported sequence inputs include:

- assemblies: `.fasta`, `.fa`, `.fna`, `.ffn`, `.fsa`, plain or (since 0.12.1) gzipped
- reads: `.fastq`, `.fq`, plain or gzipped (`.fastq.gz`, `.fq.gz`)

The input can be a single file or a directory containing multiple sequence files. Only the files directly in the folder are read, not its subfolders: a subfolder holding sequence files (one folder per sample, as some sequencers' software writes them) is named in a warning since 0.15.3 (before, its samples were missing without a word); give it as an input of its own, or move its files up. The output folder must not be the input folder (the run writes files there, which would be read as samples); paths may contain spaces (before 0.15.2, an output folder whose path had a space stopped every run at `makeblastdb`). In FASTQ mode, the name of a read file, and of a folder holding the reads but not the output folder, must not contain a comma or an equals sign: BBTools reads a comma in a file name as a list of files, and an equals sign as the start of a value. Since 1.0 such a sample is left out with a warning, `BBTools cannot be given the file (BBTools reads a comma in a file name as a list of files, and cannot read ...: remove the comma from the name of the file or of its folder)`, and the other samples are run. Before 1.0 such a sample was named as `BBDuk cannot read the reads`, and two read sets left out for their names failed the run, the assemblies beside them with it, as if BBTools could not run. A comma in the output folder's path, or in a folder above both the reads and the output, is fine since 0.15.2 (before, it stopped the run at Tadpole). FASTQ input may be a read pair or single-end reads (before 0.15.2, single-end reads were taken for interleaved pairs: a file with an odd number of reads stopped the run in BBDuk, and with an even number each baited read brought its unrelated neighbour along). Gzipped assemblies (`.fasta.gz`, `.fna.gz` and so on) are read since 0.12.1; with earlier releases, decompress them first. An assembly is read as BLAST reads it: only nucleotide letters (IUPAC codes and `U`, either case) are bases, and anything else in a sequence line (alignment gaps `-` and `.`, digits, `*`) is left out, so positions agree with BLAST's (before 0.15.2 such characters shifted them).

## Sample names

Each sample is named after its file, without the extension (in any case: `ISO2.FASTA` is the sample `ISO2`). A sample is one assembly, one FASTQ file, or the two FASTQ files of a read pair:

- `_R1`/`_R2` at the end of a FASTQ file's name, in either case (`_r1`), with or without `_001` after it, make the pair; only that ending is taken off: `s_S1_L001_R1_001.fastq.gz` and `s_S1_L001_R2_001.fastq.gz` are the pair `s_S1_L001`, the sample sheet number and lane kept in the name;
- `_1`/`_2` at the end of a FASTQ file's name (`SRR123_1.fastq.gz`, `SRR123_2.fastq.gz`, as the ENA and SRA name them) make the pair `SRR123` only when the other file of the pair is in the same folder, under the same extension written the same way, and no `_3` file under that extension is beside them (which says the names are numbered samples: `run_1`, `run_2`, `run_3` are three samples). Otherwise the file is a sample of its own, `_1` kept in its name;
- an assembly keeps these endings: `strain_R1.fasta` is the sample `strain_R1`, and `strain_R2.fasta` another one.

Before 0.15.1, an assembly whose name ended with `_R1`, `_R2` or `_assembly`, or had an upper-case extension, was searched but reported with no product, whatever it held.

## Input check

Since 0.13.0 every input file is checked before anything is run on it. A sample with a faulty file is **left out with a warning and the other samples are run**; before, such a file was skipped without a word, run as half of a read pair, or stopped the whole run inside BBDuk.

| Fault | Example of what is reported |
|---|---|
| Empty file, or an empty gzip stream (a library that gave no read) | `the file holds no read (the file is empty)` |
| Not gzip, or a gzip stream that is damaged or cut short (an interrupted download or copy) | `the file cannot be read (the gzip stream ends early, after 331 complete reads: the file is incomplete)` |
| A file that ends inside a read | `the file is incomplete (it ends inside read 501, after 500 complete reads)` |
| A read that is not four lines of the expected kind: no `@` header, no `+` line, a quality line of another length, a character that is not a base or a quality score, a blank line between reads. A base is a letter BBDuk reads: an IUPAC code, `U` or `X`, in either case (since 0.15.3; `J`, `Z`, `.`, `-` or `*` passed the check before, and BBDuk stopped the whole run on them) | `a read is not well formed (read 10 (line 40): the quality line has 100 characters for 150 bases)` |
| A gzipped read file named in upper case (`.GZ`, `.Gz`): BBTools reads a gzipped file only by a lower-case `.gz`, and read such a file as text, stopping the whole run (since 0.15.3) | `the file cannot be read (BBTools reads a gzipped file only by a lower-case .gz: rename it s_R1.FASTQ.gz)` |
| A read file BBTools cannot be given: its name, or that of a folder holding it but not the output folder, holds a comma (BBTools reads it as a list of files) or an equals sign (the start of a value), or its own name holds a `#` (in the name of a file BBTools writes, the place of a mate's number). Named so since 1.0 (before, as `BBDuk cannot read the reads`) | `BBTools cannot be given the file (BBTools reads a comma in a file name as a list of files, and cannot read ...: remove the comma from the name of the file or of its folder)` |
| Reads BBDuk cannot read although the input check passed them (a file cut short past the first read, with `--input-check quick`); since 0.15.3, found at the first baiting, where it stopped the whole run before. A BBDuk that runs out of memory is run again alone, as before | `BBDuk cannot read the reads (its message is in the log above: ...)` |
| Not a FASTQ file under a `.fastq` name | `not a FASTQ file` |
| The two files of a read pair with different numbers of reads | `the two files of the pair hold different numbers of reads (695 and 600)` |
| An assembly that is empty, holds header lines only, does not start with `>` (blank lines before it are skipped, as BLAST skips them, since 0.15.3), or whose gzip stream is cut short | `the file holds no sequence (header lines only)` |
| An assembly in which two sequences share an ID (assemblies joined into one file, each with contigs named `1`, `2`, ...); since 0.15.2 (with `--input-check quick` too): before, a primer on one and a primer on the other could be paired into a product of two molecules | `two sequences share an ID (the ID 1 names more than one sequence; ...)` |
| An assembly blastn cannot read although the input check passed it (a record whose first sequence line holds no base, as in an aligned FASTA; no base at all); since 0.15.3, found at the BLAST search, where it stopped the whole run before. Only blastn's reader refusing the file counts (exit codes 1 and 3): a blastn that fails otherwise (killed for lack of memory, a database or disk error) fails the run, as does blastn refusing every sample of the run, since the fault is then not the files' | `blastn cannot read the file (its message is in the log above: ...)` |
| A link to a file that does not exist, named as an input file (since 0.15.3; it was left out without a word) | `the file cannot be read (a link to a file that does not exist)` |
| A header line with nothing after its `>`, which BLAST names `Query_1` (since 0.15.3) | `a sequence has no ID (...)` |
| Files with the same sample name that are not a read pair (`x.fasta` and `x.fastq`, `x.fasta` and `x.fna`) | `these files have the same sample name (a sample is one FASTA file, ...)` |
| Sample names that differ only in case (`iso2.fasta`, `ISO2.fna`), or in their Unicode form (`café` typed or decomposed, since 0.15.3); their results would share a folder on Windows and macOS | `the sample name differs only in case (or in its Unicode form) from another one (ISO2, iso2 would share a results folder; rename one)` |
| A file name holding a control or invisible character (a line break, an escape sequence, a zero-width or right-to-left mark), since 0.15.3: it wrote lines of its own in `QAlog.txt`, took over the terminal, or made two names look alike. Such characters are shown as `\u000a` in the warning | `the file's name holds a control or invisible character (rename the file)` |
| A sample name so long that its result files' names would pass the 255 bytes a file name may have, since 0.15.3: the run failed with every sample, and the reads of one hung it | `the sample name is too long (its result files' names would have 270 bytes, more than the 255 a file name may have; shorten the file's name by 15)` |

A sample is left out as a whole: when one file of a read pair is faulty, the other is not run as single-end reads. A file named like a sequence file of a kind the run does not read (`x.fas`, `x.fasta.bz2`) is named in a warning, since 0.15.3 (`y.fasta.bz2 is not read: input files are named .fasta, ...`): it was left out without a word. When BBDuk refuses the reads of every read set of a run (two or more), the run fails (`Error: BBDuk could not read the reads of any sample`), assemblies beside them or not: the fault is then not the files' but the program's. Read sets left out because BBTools cannot be given their files are not counted (since 1.0): BBDuk never saw them.

An output folder holding symbolic links where a run writes (`primer_tmp.fasta`, `QAlog.txt.part`, `detailed_report/<sample>`), as one reused from another run or unpacked from an archive may, is refused before the run starts, since 0.15.3: the run wrote through them, overwriting or deleting the files they point to, outside the folder. Links elsewhere in it are no concern of the run's (an input folder inside it, its reads linked from a shared store). The output folder itself may be a link. On Windows, a junction (`mklink /J`, a link to a folder that Java does not see as a link) where a run writes is refused too, since 0.15.4; other reparse points are not, since 1.0, such as a file of a deduplicated volume or one a cloud service keeps (0.15.4 refused them). The names where a run writes are matched whatever their case, on every system (since 0.15.4): on Windows and macOS, and on a share mounted on Linux that ignores case, `Detailed_Report` is the folder a run writes in.

A read set of one file named as one read of a pair (`x_R1.fastq.gz`, its `x_R2` not in the input, or `-i` given the `_R1` file alone) is run as single-end reads, with a warning since 0.15.3: before, half the data went unused without a word.

The faults are reported in four places:

- as `Warning:` lines on the terminal, or in the output area of the application;
- in `QAlog.txt`, under `Input not analysed`, with one `Sample not analysed: <name>` line per sample;
- at the top of the HTML report, in a section **Input not analysed** and a count among the summary tiles. The samples are not in the tables of the report: nothing was run on them, so they are neither positive nor negative;
- such a sample has no lane in the synthetic gel.

The run itself still ends normally (exit code 0) when at least one sample is left to analyse. When none is, every input file being faulty, the run stops after naming them, with `No sample of ... can be analysed: every input file is faulty` and exit code 1 (before 0.15.3, it wrote reports empty apart from that section and ended with exit code 0, which a workflow manager running one job per sample took for success). An input with no FASTA or FASTQ file at all (an empty folder, a mistyped `-i`) is not run: it stops with `No FASTA or FASTQ file in ...: nothing to analyse` and exit code 1 (before 0.15.2, it ended with empty reports and exit code 0, which a workflow manager took for success). The command line also refuses arguments it does not expect (exit code 2): a shell pattern such as `-i reads/*.fastq.gz` gives `-i` only its first file, so give the folder that holds the files instead (before 0.15.2, the other files were silently left out).

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

| Parameter | Command line | Default | Purpose |
|---|---|---|---|
| Threads | `-t` | the processors, less one in the window | Number of worker threads; FASTQ samples processed at once share them, and the memory: see [Threads and memory in FASTQ mode](Command-line-Interface#threads-and-memory-in-fastq-mode) |
| Anneal (°C) | `--anneal-temp` | 60 | Annealing temperature of the reaction. A primer or probe site is accepted when its Tm on the template reaches it less the Tm margin (55 °C by default), and a primer's 3' end passes the 3' rule. Replaces **Mismatches** of 1.x |
| E-value | `-e` | 1e5 | BLASTN e-value threshold |
| Amplicon settings… | `--tm-margin`, `--na`, `--mg`, `--dntp`, `--oligo-conc`, `--3p-window`, `--3p-penalty`, `--min-length`, `-l` | see [Amplicon Calling](Amplicon-Calling) | Tm margin and the reaction the Tm assumes, 3'-end check, min/max amplicon length (default max 5000; 0 disables the limit) |
| FASTQ settings… | the Tadpole options, `--read-min-fraction`, `--read-extension`, `--memory` (`--bbtools-memory` before 1.0) | see [Command-line Interface](Command-line-Interface) | Tadpole mini-assembly settings, the read-level call threshold, the read extension and the BBTools programs' memory |

Every setting of the window has a command-line equivalent. The command line has a few the window does not: `--input-check quick` (the window checks every read), and `--gel` (the window has its own gel viewer); see [Command-line Interface](Command-line-Interface), [Amplicon Calling](Amplicon-Calling) and [GUI Overview](GUI-Overview).

## Outputs

| In the output folder | What it is |
|---|---|
| `consolidated_report/report.html` | [HTML report](Reports#html-report): which samples are positive for which assay, then every amplicon |
| `consolidated_report/report.tsv` | One row per amplicon; the source of the HTML report and of the synthetic gel. Its columns and how it is written: [File format](Reports#file-format) |
| `consolidated_report/report_samples.tsv` | Since 1.0: every sample of the run, analysed or left out (and why), with its number of products; the samples with no row in `report.tsv` are found here. See [Samples file](Reports#samples-file) |
| `consolidated_report/report_no_product.tsv` | Since 2.0: each assay without a product in a sample, and why (the oligo without an accepted site, its best site and its Tm, the rule it fails). See [Assays without a product](Reports#assays-without-a-product) |
| `consolidated_report/report_fastq_diagnostics.tsv` | FASTQ input only: why an assay was not reported although its primers are in the reads |
| `detailed_report/<sample>/` | The BLAST hits the rules accept per sample (before 0.15.3, every alignment) and, for FASTQ input, the baited reads and mini-assembly |
| `QAlog.txt` | Version, inputs, the samples analysed, the report written, the settings that decide calls, each oligo's Tm on a perfect match, and how many assays gave no product; written when a run finishes |
| `primer_tmp.fasta*` | The primers as a BLAST database; the application deletes it no longer, since 0.14.0 |
| `synthetic_gel_report.png` | With `--gel` only: the synthetic gel, in the output folder itself, named after the report (`synthetic_gel_report(1).png` for `report(1).tsv`) |
| `log.txt` | Application only: what the window showed of each run, added at the end of the run, also one that failed or was stopped |

Give each run its own output folder when runs go at the same time (a workflow manager running one job per sample): a run holds its output folder while it runs (through an empty file, `.insilicoPCR.lock`, left in the folder; the system lets go of it when the run ends, however it ends), and a second run into it stops at once with `Another run is using the output folder` (exit code 1; on a file system that cannot lock files, as some network ones, the run says so in a warning and goes on without the lock: do not run two into one folder there); before 0.15.2 such runs overwrote each other's files and failed at random, or searched each other's primers. The hidden `.insilicoPCR.lock` file in the folder is what holds it; it is left there. A run into a folder that already holds a report writes the report numbered after the highest one there (`report(1).tsv`, `report(1).html` and its log `QAlog(1).txt`, and so on): each report keeps the log of the run that wrote it, and **Open Previous Run** opens the latest (before 0.15.2, the second run replaced `QAlog.txt`, and the first report could no longer be reopened). The files under `detailed_report/` are those of the latest run: a sample analysed again has its files replaced. See [Reports](Reports).
