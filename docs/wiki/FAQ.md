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
search; targets the mini-assembly misses are called from the reads themselves when single reads or merged
pairs hold the whole amplicon, and a product longer than a read is looked for by extending the reads that
carry its primers. A FASTQ-mode negative is still weaker evidence than one on a genome assembly;
assays whose oligos were found in the reads are explained in `report_fastq_diagnostics.tsv` and `QAlog.txt`. See
[FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives).

## Can I use a folder as input?

Yes. The input can be a single sequence file or a directory containing sequence files.

## What primer format is required?

The primer file must be FASTA. Primer IDs must end with `-F`, `-R`, or `-P`. Text after the ID on the header
line is a description (since 0.11.0; earlier releases silently reported nothing for such an assay). See
[Primer FASTA Format](Primer-FASTA-Format).

## How do I see which samples are positive?

Open `consolidated_report/report.html` in the output folder (in the GUI: **View HTML Report**). Its grid has
one row per sample and one column per assay; each column head counts the positive samples, and the **By
assay** section names them. See [HTML report](Reports#html-report).

## Why is a product of the wrong size reported?

Because a real PCR would amplify it. insilicoPCR reports every product that passes the
[amplicon rules](Amplicon-Calling), whatever its size; an assay that gives a band of the wrong size is a
positive PCR, not necessarily a positive test. Check `AmpliconSize`, or declare the size the assay is
designed to give in the primer file (`>assay-F size=434`): the reports then label each product `target`,
`other` or `unexpected`, and the HTML report counts only the target size as positive. Nothing is removed
either way. See [Declaring product sizes](Primer-FASTA-Format#declaring-product-sizes-optional).

## Can another tool design the primers and write these files?

Yes. [primer-finder](https://github.com/duceppemo/primer-finder) finds the sequences that tell one group of genomes from another, designs selective (q)PCR assays on them with Primer3, and writes insilicoPCR-ready primer FASTA files. Its `design --insilico-pcr` option also runs insilicoPCR itself against both genome groups and reports which assays amplify every target genome and none of the others (primer-finder 1.10.0+ pairs with insilicoPCR 0.8.0+).

## Can I mix PCR assays with qPCR/probe assays?

Yes (since version 0.8.0). The probe requirement is decided per assay: assays with a `-P` probe are only reported when the probe hits inside the amplicon, while probe-less assays are reported on their primer pair alone. See [Standard PCR vs qPCR](Standard-PCR-vs-qPCR).

## Why is Linux launched from a terminal?

Launching from a terminal makes startup messages and errors visible, which helps troubleshooting.

## Why is an amplicon I expected missing from the report?

The most common reasons, in order:

1. **Product too long.** The default maximum amplicon length is 5000 bp. Raise it with `-l <bp>` or disable it with `-l 0`.
2. **3'-end mismatch.** By default the last 5 bases of each primer must match perfectly (the 3' bases BLAST trims off its alignment are compared with the template, and those that do not pair count as mismatches). Relax with `--3p-penalty` together with `-m` — see [Amplicon Calling](Amplicon-Calling).
3. **Wrong orientation.** Primer pairs on the same strand, or pointing away from each other, are never reported because they cannot amplify.
4. **The assembly breaks inside the product.** A product is called on one contig only. When the primers point off the ends of two contigs (or both ends of a circular one), the run warns `Primers at contig ends` (since 0.15.0); see [Limitations](Limitations).
5. **Probe assay without a probe hit.** An assay that defines a `-P` probe is reported only when an accepted probe hit lies inside the amplicon. Every probe mismatch counts against `--probe-mismatches` (default: same as `-m`, so 0), wherever it sits in the probe.

## Why does the report show huge amplicons from old versions but not anymore?

Versions before 0.8.0 paired any forward/reverse hits on the same contig, regardless of distance or orientation. Those "phantom" products would not amplify in a real reaction and are now filtered out.

## Does insilicoPCR model melting temperature, primer dimers or hairpins?

No. insilicoPCR applies sequence rules only: where each primer binds and with how many mismatches (counted by number and position, not by free energy), whether the primers face each other on one sequence, the product size, and for qPCR assays whether the probe lies inside the product. Nothing models melting or annealing temperature, primer dimers, hairpins or other secondary structure, amplification efficiency, or competition between assays in a multiplex. Check those with a primer design tool such as Primer3. See [Limitations](Limitations) for this and the cases where the report can miss a product.

## Are G/C mismatches treated differently from A/T mismatches?

Yes, near the 3' end. Within the last 3 bases of a primer, a mismatch that breaks a G/C pair scores one extra penalty point compared to an A/T pair, because losing the stronger pairing destabilizes extension more. See the penalty table in [Amplicon Calling](Amplicon-Calling).

## What is the synthetic gel visualizer?

It is a visual summary of consolidated report results, displayed as gel-like bands. It is useful for reviewing amplicon size patterns across samples.
