# Tutorial example files

The primer file and the reports of the [tutorial](https://github.com/duceppemo/insilicoPCR/wiki/Tutorial-Mixed-PCR-qPCR), from a run of insilicoPCR 2.0.0 on its four public samples with default settings.

| File | What it is |
|---|---|
| [`listeria_assays.fasta`](listeria_assays.fasta) | The two *hly* assays, with their product sizes and published annealing temperatures declared (`size=`, `ta=`) |
| [`report.tsv`](report.tsv) | Consolidated report: one row per amplicon |
| [`report_samples.tsv`](report_samples.tsv) | Every sample of the run, with its number of products: the two *L. innocua* samples have `0` |
| [`report_no_product.tsv`](report_no_product.tsv) | Every sample and assay without a product (the two *L. innocua* samples), with the oligo that has no acceptable site, its best site and that site's Tm |
| [`report_fastq_diagnostics.tsv`](report_fastq_diagnostics.tsv) | For the read sets: assays missed although their primers are in the reads. Only its header in this run (without `ta=55` on `hly702-F`, it lists `hly702` on `SRR17090570`) |
| [`report.html`](report.html) | HTML report. Download it and open it in a web browser; GitHub only shows its source |
