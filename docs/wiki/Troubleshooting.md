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

Two cases remain negative by design:

- **The amplicon is longer than a read or merged pair** (about 580 bp for 2 x 300 bp reads). Reads cannot hold it, and the mini-assembly often breaks inside high-copy regions such as rDNA. The diagnostics say `NOT_IN_READS` with the longest amplicon the reads could have held; when the primer file declares the assay's size, the HTML report marks the sample "not checkable from reads" instead of negative. Run the assay on an assembly of the reads.
- **`WEAK_READ_SUPPORT`**: complete amplicons are in the reads, but in under `--read-min-fraction` (default 30%) of the reads covering the locus. This is the signature of contamination, and also of a locus of which only some copies match the primers and probe. Read the counts in `ReadDetail`, and lower `--read-min-fraction` if the sample is known to be mixed.

## FASTQ run seems to hang after BLAST

Releases before 0.11.0 could spend hours in the read-level search after `blastn` had finished, on samples with hundreds of thousands of baited reads (large primer panels, permissive `-m` and `--5p-truncation`). One CPU stays busy and nothing is logged. The results are correct but late. Upgrade to 0.11.0, where the same step takes seconds.

## An assay is never reported although its primers match

Check the primer FASTA headers. In releases before 0.11.0, any text after the primer ID (`>assay-F forward primer`) became part of the ID, no BLAST hit matched it, and the assay was silently negative in every sample. Since 0.11.0 the ID ends at the first blank and the rest is a description.

## A product has an unexpected size

That is a result, not an error: every product a PCR would amplify is reported. Permissive settings (`-m 1` or more with a large `--5p-truncation`) let primers pair at other places in the genome. See [the FAQ](FAQ#why-is-a-product-of-the-wrong-size-reported).

## Primer file errors

Primer FASTA IDs must end with:

- `-F`
- `-R`
- `-P` for probes

See [Primer FASTA Format](Primer-FASTA-Format).

Since 0.12.0 the command line reports a mistake in the primer file (an ID without a role suffix, characters that are not bases, a `size=` that cannot be read, two different sizes for one assay) as one `Error:` line and exits with code 1. Earlier releases printed a Java stack trace for the same mistakes.

Since 0.12.1 a failing external tool (BLAST+, BBTools) is reported the same way: the tool's own message, then `Error:` with the stage that failed and `Cause:` with the full command line and its exit code. Copy the command line to run the tool by hand.

## Run fails with `BLAST query error` on a gzipped assembly

Releases before 0.12.1 accept a gzipped FASTA file (`.fasta.gz`, `.fna.gz` and so on) as input, then hand it to `blastn`, which does not read gzip and stops. In the application the same input ends with `Unable to read sequence file for sample`. Upgrade to 0.12.1, or decompress the assemblies first (`gunzip *.fasta.gz`). Gzipped FASTQ reads were never affected.

## Results differ between 0.11.0 and 0.12.0

Releases before 0.12.0 miscounted mismatches near the ends of probes and near the 3' end of primers, because BLAST leaves such a mismatch out of its alignment together with the matching bases beyond it. Since 0.12.0 those bases are compared with the sample sequence. Three kinds of calls can change:

- **A probe mismatch on the first or last two bases** used to go uncounted: the assay was reported with `0` probe mismatches, even at the default `--probe-mismatches 0`. It is now counted, so the assay is negative unless `--probe-mismatches` is 1 or more.
- **A probe mismatch on the third or fourth base from either end** used to remove the assay whatever `--probe-mismatches` allowed. It now counts as one mismatch, and the assay is reported when the budget allows it.
- **Primer mismatches near the 3' end** used to count once per base BLAST trimmed. They now count once each, so runs with `--3p-penalty` above 0, or with `-m 2` or more, can report amplicons that 0.11.0 rejected.

See [Amplicon Calling](Amplicon-Calling) for the rules.

## Missing positive results in mixed primer files

Since version 0.8.0, standard PCR and qPCR/probe assays can be mixed in one primer FASTA file. If an assay that defines a `-P` probe is missing from the report even though its primers hit, the probe did not produce an accepted hit inside the amplicon — that assay is intentionally kept negative. In versions before 0.8.0, any `-P` primer switched the whole report to qPCR mode and silently dropped all probe-less assays; upgrade if you see that behavior.

## CLI help

Use:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```
