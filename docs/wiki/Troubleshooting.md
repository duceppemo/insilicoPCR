# Troubleshooting

## The application does not start on Linux

Run the launcher from a terminal:

```bash
cd insilicoPCR-linux-x64
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

Check the terminal output for missing files or permission errors.

## Permission denied on Linux

Make the launcher executable:

```bash
chmod +x run-insilicoPCR.sh
```

If needed, also ensure bundled tool scripts are executable.

## The application does not start on Windows

Make sure the ZIP was fully extracted before running the launcher.

Run:

```text
run-insilicoPCR.bat
```

Do not run the application directly from inside the ZIP archive.

## Windows SmartScreen warning

Windows may warn about unsigned software.

Only continue if the release was downloaded from the official repository.

## Missing Java

Portable releases include a bundled Java runtime. You should not need system Java.

If Java is reported missing, the release folder may be incomplete or files may have been moved after extraction.

## Missing BBMap or BLAST+

Portable releases include BBMap and BLAST+.

If a tool is reported missing:

- confirm the ZIP was fully extracted
- keep the `runtime/` folder beside the launcher
- do not move files out of the release folder

BBMerge, used in FASTQ mode for paired reads, also needs `runtime/common/bbmap/networks/bbmerge.bbnet.gz`. In a source checkout, `scripts/dev/verify-runtime-layout.sh` lists anything missing.

## FASTQ run fails with `truncated or corrupt input`

The full message may also say `There appear to be different numbers of reads in the paired input files`, even though the files are intact. This happens with releases before 0.9.0 on multi-member (concatenated) `.fastq.gz` files, which is the default output of Illumina bcl-convert and DRAGEN. The bundled BBTools 39.94 stopped reading at a gzip member boundary whenever BBDuk ran on more than one thread.

Upgrade to 0.9.0 or newer, which bundles BBTools 40.04. The files are then read completely, with no repacking needed.

## FASTQ run aborts with `OutOfMemoryError`

Releases before 0.9.0 ran the second baiting pass with the `-m` mismatch budget as BBDuk's `hdist`, and on large samples with `-m 2` or more its k-mer table outgrew any heap. Since 0.9.0 that pass runs with `hdist=0`, and `-m` still applies to primer baiting and amplicon calling. Upgrade.

## An assay is negative in FASTQ mode but positive on an assembly

A FASTQ-mode negative is weaker evidence than an assembly-mode one: the mini-assembly can miss the target region or break inside the amplicon. Since 0.10.0 such assays are, where the reads support them, called from the reads themselves (`FASTQ read-level call` in `report.tsv`). Every missed assay whose oligos are in the reads is listed in `consolidated_report/report_fastq_diagnostics.tsv`, with the reason. See [FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives).

## Primer file errors

Primer FASTA IDs must end with:

- `-F`
- `-R`
- `-P` for probes

See [Primer FASTA Format](Primer-FASTA-Format.md).

## Missing positive results in mixed primer files

Since version 0.8.0, standard PCR and qPCR/probe assays can be mixed in one primer FASTA file. If an assay that defines a `-P` probe is missing from the report even though its primers hit, the probe did not produce an accepted hit inside the amplicon — that assay is intentionally kept negative. In versions before 0.8.0, any `-P` primer switched the whole report to qPCR mode and silently dropped all probe-less assays; upgrade if you see that behavior.

## CLI help

Use:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```
