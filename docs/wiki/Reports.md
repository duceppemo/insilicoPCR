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

FASTQ input is not searched read by read. The reads are baited twice with BBDuk (reads carrying a primer
k-mer, then reads overlapping those), the baited reads are micro-assembled with Tadpole, and the primers
are BLASTed against the resulting contigs. An assay can therefore come out negative although its oligos are
in the reads, when the mini-assembly:

- **omits the target region** — e.g. the contig spanning it is shorter than Tadpole's minimum contig length
  or too thinly covered to seed; or
- **breaks inside the amplicon**, so the forward and reverse primers land on different contigs and no
  convergent pair exists on one sequence. Recruitment only reaches about one fragment length beyond each
  primer, so amplicons longer than roughly two fragment lengths always end up split in FASTQ mode.

Neither case means the target is absent. **A FASTQ-mode negative is weaker evidence than a negative on a
genome assembly**; for exclusivity or diagnostic work, confirm negatives on an assembly of the reads.

To make these cases visible, the first BBDuk pass records the reads matched per oligo
(`detailed_report/<sample>/<sample>_baitStats.txt`). Every assay without a reported amplicon whose oligos
matched reads is listed in `report_fastq_diagnostics.tsv` (named after the consolidated report, e.g.
`report(1)_fastq_diagnostics.tsv`), warned about on the console and in the GUI log, and summarized in
`QAlog.txt`:

| Column | Meaning |
|---|---|
| `Sample` / `Gene` | Sample and assay |
| `Status` | See below |
| `ForwardReads` / `ReverseReads` / `ProbeReads` | Reads matched by the assay's oligos in the first baiting pass (degenerate variants summed). BBDuk credits each read to one oligo, so a read carrying two oligos of an assay counts once |
| `Detail` | What the mini-assembly holds for the assay |

| Status | Meaning | What to try |
|---|---|---|
| `NOT_ASSEMBLED` | Forward and reverse primers both matched reads, but at least one has no accepted hit on any contig | Lower `--tadpole-min-contig` / `--tadpole-min-seed`, or use a genome assembly |
| `SPLIT_ACROSS_CONTIGS` | Both primers hit contigs, never the same one. `Detail` lists each hit and its distance to the contig end in the primer's extension direction — a short distance on both sides means the assembly broke inside the amplicon | Use a genome assembly; Tadpole settings rarely join these |
| `NO_VALID_PAIR` | Both primers share a contig but form no reportable amplicon (orientation, `--min-length`/`--max-length`, or missing probe) | Inspect `detailed_report/<sample>/<sample>.tsv` |
| `PARTIAL_BAIT` | Only some of the assay's primers matched reads. Possibly a true negative (e.g. one primer site diverged beyond `-m`); listed for completeness, not warned about | Usually none |

`QAlog.txt` also records the Tadpole settings used. The command-line options `--tadpole-k`,
`--tadpole-min-seed`, `--tadpole-min-cov` and `--tadpole-min-contig` tune the mini-assembly; the defaults
(`k=31 mincountseed=2 mincov=1 mincontig=60`) are deliberately less strict than Tadpole's own
(`mincountseed=3 mincontig=500`), which dropped contigs spanning short amplicons.

## Mode behavior

The probe requirement is decided per assay (since version 0.8.0): assays with a `-P` probe require probe support inside the amplicon, while probe-less assays are reported on their primer pair alone — both can live in the same primer FASTA file. See [Standard PCR vs qPCR](Standard-PCR-vs-qPCR).

## Synthetic gel

The synthetic gel visualizer reads consolidated result data and displays predicted amplicons as gel-like bands. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer).
