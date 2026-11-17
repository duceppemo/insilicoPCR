# Tutorial example files

The primer file and the reports of the [tutorial](https://github.com/duceppemo/insilicoPCR/wiki/Tutorial-Mixed-PCR-qPCR), from a run of insilicoPCR 1.0.0 on its four public samples with default settings.

| File | What it is |
|---|---|
| [`listeria_assays.fasta`](listeria_assays.fasta) | The two *hly* assays, with their product sizes declared |
| [`report.tsv`](report.tsv) | Consolidated report: one row per amplicon |
| [`report_samples.tsv`](report_samples.tsv) | Every sample of the run, with its number of products: the two *L. innocua* samples have `0` |
| [`report.html`](report.html) | HTML report. Download it and open it in a web browser; GitHub only shows its source |

`report_fastq_diagnostics.tsv` is not included: it only holds its header line in this run, since no assay was missed for the two read sets.
