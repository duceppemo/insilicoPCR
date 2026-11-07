# Reports

insilicoPCR produces report files that summarize primer hits, predicted amplicons, and consolidated results across samples.

## Report types

- runtime and quality logs (`QAlog.txt`)
- detailed per-sample BLAST hit tables (`detailed_report/<sample>/<sample>.tsv`)
- consolidated TSV report (`consolidated_report/report.tsv`)
- FASTQ-mode diagnostics (`consolidated_report/report_fastq_diagnostics.tsv`), when any input is FASTQ
- synthetic gel images rendered from the consolidated report

If a `report.tsv` already exists in the output folder, a new run writes `report(1).tsv`, `report(2).tsv`, and so on instead of overwriting it.

## Consolidated report

The consolidated report is the primary summary table: one row per predicted amplicon that passed the [amplicon calling rules](Amplicon-Calling).

### Standard PCR mode columns

| Column | Meaning |
|---|---|
| `Sample` | Sample name (from the input file name) |
| `Gene` | Assay name (primer ID without the `-F`/`-R`/`-P` suffix) |
| `GenomeLocation` | 1-based start-end of the product on the contig |
| `AmpliconSize` | Product length in bp, primers included |
| `Contig` | Contig/sequence ID the product sits on |
| `Contig Description` | Free text from the contig FASTA header |
| `ForwardPrimers` / `ReversePrimers` | Primer IDs that formed the pair (degenerate variants keep their `_n` suffix) |
| `ForwardMismatches` / `ReverseMismatches` | Mismatches + indels + unaligned 3' bases for each primer |
| `Forward3PrimeMismatches` / `Reverse3PrimeMismatches` | Number of imperfections within the 3'-end window (default last 5 bases) |
| `Strand` | `+` when the forward primer matched the plus strand; `-` when the assay matched the reverse strand |

### qPCR/probe columns

When at least one assay in the primer file defines a `-P` probe, the report appends:

| Column | Meaning |
|---|---|
| `Probe` | Probe ID |
| `ProbeLocation` | 1-based start-end of the probe on the contig |
| `ProbeSize` | Length of the probe match |
| `ProbeMismatches` | Mismatches + indels in the probe alignment |

For an assay that defines a probe, a row is only written when the probe lies entirely within the amplicon. Probe-less assays in the same run keep these columns empty.

## Detailed per-sample reports

The `detailed_report` folder keeps the raw BLAST tabular output per sample (with a header line), useful for auditing why a primer hit was or was not accepted. Columns follow BLAST `-outfmt "6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq"`.

## FASTQ mode and false negatives

FASTQ input is not assembled into a genome. The reads are baited twice with BBDuk (reads carrying a primer
k-mer, then reads overlapping those), the baited reads are micro-assembled with Tadpole, and the primers are
BLASTed against the resulting contigs. The mini-assembly can **omit the target region** or **break inside the
amplicon**, so the forward and reverse primers land on different contigs. Recruitment only reaches about one
fragment length beyond each primer, so amplicons longer than roughly two fragment lengths always end up split.

### Read-level calls

To catch what the mini-assembly misses, the first-pass reads are also searched directly. Overlapping pairs are
merged with BBMerge, and each read or merged pair is treated as a tiny contig: the primers are BLASTed against it
and the same hit filters, amplicon rules and probe requirement apply. A read only counts when it holds the
**whole** product, both primers included.

An assay the assembly did not report is reported from reads when at least **2 reads** hold a complete amplicon
**and** they make up at least **30% of the informative reads**: reads that carry the assay's forward or reverse
primer and reach far enough from it to have shown the amplicon. A real target shows in most informative reads
(not all: a sequencing error in a primer or probe disqualifies a read under a strict `-m`), while a few
contaminating or index-hopped reads from another library show in few of them. The 30% default comes from the
two read sets in [#7](https://github.com/duceppemo/insilicoPCR/issues/7) (real targets at 61% and 83%, a
contaminant signal at 18%); change it with `--read-min-fraction` (0–1, command line only). Raise it for
exclusivity work on runs that may carry contamination, lower it for noisy reads under a strict `-m`. The value
used is recorded in `QAlog.txt`.

Read-level rows appear in `report.tsv` like any other amplicon, with the representative read as `Contig` (its
coordinates are on that read) and a `Contig Description` starting with `FASTQ read-level call:` and giving the
support, e.g. `complete amplicon in 17 of 28 informative read(s) (61%)`.

Read-level calling only works when an amplicon fits in a read or a merged pair. **A FASTQ-mode negative is weaker
evidence than a negative on a genome assembly**; for exclusivity or diagnostic work, confirm negatives on an
assembly of the reads.

### Diagnostics file

Every assay the mini-assembly did not report but whose oligos are in the baited reads is listed in
`report_fastq_diagnostics.tsv` (named after the consolidated report, e.g. `report(1)_fastq_diagnostics.tsv`),
summarized in `QAlog.txt`, and noted on the console or in the GUI log. Files kept per sample under
`detailed_report/<sample>/`: `_merged.fastq.gz` / `_unmerged.fastq.gz` (BBMerge output), `_baitedReads.fasta`
(the reads searched, renamed `read_<n>` with the original name as description) and `_baitedReads.blast.txt`
(their BLAST hits).

| Column | Meaning |
|---|---|
| `Sample` / `Gene` | Sample and assay |
| `Status` | What the reads show; see below |
| `Assembly` | Why the mini-assembly did not report it; see below |
| `ReadsWithForward` / `ReadsWithReverse` / `ReadsWithProbe` | Baited reads (or merged pairs) holding the whole oligo with an accepted hit; every oligo a read carries is counted, degenerate variants are summed |
| `CheckableLength` | Longest amplicon a read carrying one of the primers could have held |
| `ReadDetail` / `AssemblyDetail` | Details for the two classifications |

| Status | Meaning |
|---|---|
| `CALLED_FROM_READS` | Reported in `report.tsv` from reads |
| `WEAK_READ_SUPPORT` | Complete amplicons in reads, but fewer than 2 or under `--read-min-fraction` (default 30%) of informative reads. **Not reported, and warned about**: contamination, index hopping, a mixed sample, or a primer site repeated elsewhere in the genome. Inspect the reads before trusting either answer |
| `NOT_IN_READS` | Both primers are in the reads, but no read holds a complete amplicon. Conclusive only for amplicons up to `CheckableLength`; a longer amplicon cannot be ruled out |
| `PARTIAL_BAIT` | Only some of the assay's primers are in the reads (e.g. one primer site diverged beyond `-m`); usually a true negative |

| Assembly | Meaning |
|---|---|
| `NOT_ASSEMBLED` | A primer found in the reads has no accepted hit on any contig |
| `SPLIT_ACROSS_CONTIGS` | Both primers hit contigs, never the same one. `AssemblyDetail` lists each hit and its distance to the contig end in the primer's extension direction; short distances on both sides mean the assembly broke inside the amplicon |
| `NO_VALID_PAIR` | Both primers share a contig but form no reportable amplicon (orientation, `--min-length`/`--max-length`, or missing probe) |

`QAlog.txt` also records the Tadpole settings used. The command-line options `--tadpole-k`,
`--tadpole-min-seed`, `--tadpole-min-cov` and `--tadpole-min-contig` tune the mini-assembly; the defaults
(`k=31 mincountseed=2 mincov=1 mincontig=60`) are less strict than Tadpole's own (`mincountseed=3
mincontig=500`), which drops contigs spanning short amplicons.

## Mode behavior

The probe requirement is decided per assay (since version 0.8.0): assays with a `-P` probe require probe support inside the amplicon, while probe-less assays are reported on their primer pair alone — both can live in the same primer FASTA file. See [Standard PCR vs qPCR](Standard-PCR-vs-qPCR).

## Synthetic gel

The synthetic gel visualizer reads consolidated result data and displays predicted amplicons as gel-like bands. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer).
