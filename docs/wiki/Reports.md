# Reports

insilicoPCR produces report files that summarize primer hits, predicted amplicons, and consolidated results across samples.

## Report types

- runtime and quality logs (`QAlog.txt`)
- detailed per-sample BLAST hit tables (`detailed_report/<sample>/<sample>.tsv`)
- consolidated TSV report (`consolidated_report/report.tsv`)
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

### qPCR/probe mode columns

qPCR mode (any `-P` primer present) appends:

| Column | Meaning |
|---|---|
| `Probe` | Probe ID |
| `ProbeLocation` | 1-based start-end of the probe on the contig |
| `ProbeSize` | Length of the probe match |
| `ProbeMismatches` | Mismatches + indels in the probe alignment |

A row is only written when the probe lies entirely within the amplicon.

## Detailed per-sample reports

The `detailed_report` folder keeps the raw BLAST tabular output per sample (with a header line), useful for auditing why a primer hit was or was not accepted. Columns follow BLAST `-outfmt "6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq"`.

## Important mode behavior

If any primer ID includes `-P`, the consolidated report is generated in qPCR mode. Standard PCR assays without probes should be run separately from qPCR assays.

## Synthetic gel

The synthetic gel visualizer reads consolidated result data and displays predicted amplicons as gel-like bands. See [Synthetic Gel Visualizer](Synthetic-Gel-Visualizer).
