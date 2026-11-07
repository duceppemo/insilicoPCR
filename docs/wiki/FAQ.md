# FAQ

## Do I need to install Java?

No. Portable releases include the Java runtime.

## Do I need to install BBMap or BLAST+?

No. Portable releases include BBMap and NCBI BLAST+.

## Can I run insilicoPCR from the terminal?

Yes. Use the bundled Java runtime:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

## Can I use FASTQ reads?

Yes. insilicoPCR supports FASTA assemblies and FASTQ reads. Reads are baited and micro-assembled before the
search, so a FASTQ-mode negative is weaker evidence than one on a genome assembly; negatives whose oligos were
found in the reads are flagged in `report_fastq_diagnostics.tsv` and `QAlog.txt`. See
[FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives).

## Can I use a folder as input?

Yes. The input can be a single sequence file or a directory containing sequence files.

## What primer format is required?

The primer file must be FASTA. Primer IDs must end with `-F`, `-R`, or `-P`.

## Can another tool design the primers and write these files?

Yes. [primer-finder](https://github.com/duceppemo/primer-finder) finds the sequences that tell one group of genomes from another, designs selective (q)PCR assays on them with Primer3, and writes insilicoPCR-ready primer FASTA files. Its `design --insilico-pcr` option also runs insilicoPCR itself against both genome groups and reports which assays amplify every target genome and none of the others (primer-finder 1.10.0+ pairs with insilicoPCR 0.8.0+).

## Can I mix PCR assays with qPCR/probe assays?

Yes (since version 0.8.0). The probe requirement is decided per assay: assays with a `-P` probe are only reported when the probe hits inside the amplicon, while probe-less assays are reported on their primer pair alone. See [Standard PCR vs qPCR](Standard-PCR-vs-qPCR).

## Why is Linux launched from a terminal?

Launching from a terminal makes startup messages and errors visible, which helps troubleshooting.

## Why is an amplicon I expected missing from the report?

The most common reasons, in order:

1. **Product too long.** The default maximum amplicon length is 5000 bp. Raise it with `-l <bp>` or disable it with `-l 0`.
2. **3'-end mismatch.** By default the last 5 bases of each primer must match perfectly (BLAST-trimmed terminal bases count as mismatches). Relax with `--3p-penalty` together with `-m` — see [Amplicon Calling](Amplicon-Calling).
3. **Wrong orientation.** Primer pairs on the same strand, or pointing away from each other, are never reported because they cannot amplify.
4. **qPCR mode without a probe hit.** If any primer in the file ends with `-P`, every reported assay needs its probe inside the amplicon.

## Why does the report show huge amplicons from old versions but not anymore?

Versions before 0.8.0 paired any forward/reverse hits on the same contig, regardless of distance or orientation. Those "phantom" products would not amplify in a real reaction and are now filtered out.

## Are G/C mismatches treated differently from A/T mismatches?

Yes, near the 3' end. Within the last 3 bases of a primer, a mismatch that breaks a G/C pair scores one extra penalty point compared to an A/T pair, because losing the stronger pairing destabilizes extension more. See the penalty table in [Amplicon Calling](Amplicon-Calling).

## What is the synthetic gel visualizer?

It is a visual summary of consolidated report results, displayed as gel-like bands. It is useful for reviewing amplicon size patterns across samples.
