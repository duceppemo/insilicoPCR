# FAQ

## Do I need to install Java?

No. Portable releases include the Java runtime.

## Do I need to install BBMap or BLAST+?

No. Portable releases include BBMap and NCBI BLAST+.

## Can I run insilicoPCR from the terminal?

Yes. Use the launcher with arguments, or the bundled Java runtime:

```bash
./run-insilicoPCR.sh -h
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

## Can I use FASTQ reads?

Yes. insilicoPCR supports FASTA assemblies and FASTQ reads. Reads are baited and mini-assembled before the
search; targets the mini-assembly misses are called from the reads themselves when single reads or merged
pairs hold the whole amplicon, and a product longer than a read is looked for by extending the reads that
carry its primers. A FASTQ-mode negative is still weaker evidence than one on a genome assembly;
assays whose oligos were found in the reads are explained in `report_fastq_diagnostics.tsv` and `QAlog.txt`. See
[FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives).

## Can I use a folder as input?

Yes. The input can be a single sequence file or a directory containing sequence files.

## What primer format is required?

The primer file must be FASTA. Primer IDs must end with `-F`, `-R`, or `-P`, or have one of them before an underscore (`toxA-F_2`). Text after the ID on the header
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

Yes. [primer-finder](https://github.com/duceppemo/primer-finder) finds the sequences that tell one group of genomes from another, designs selective (q)PCR assays on them with Primer3, and writes insilicoPCR-ready primer FASTA files. Its `design --insilico-pcr` option also runs insilicoPCR itself against both genome groups and reports which assays amplify every target genome and none of the others (primer-finder 1.14.0+ pairs with insilicoPCR 2.1.0+; primer-finder 1.13.0 with insilicoPCR 0.12.0 to 0.14.0).

## Can I mix PCR assays with qPCR/probe assays?

Yes (since version 0.8.0). The probe requirement is decided per assay: assays with a `-P` probe are only reported when the probe hits inside the amplicon, while probe-less assays are reported on their primer pair alone. See [Standard PCR vs qPCR](Standard-PCR-vs-qPCR).

## Why is Linux launched from a terminal?

Launching from a terminal makes startup messages and errors visible, which helps troubleshooting.

## Why is an amplicon I expected missing from the report?

Since 2.0, `consolidated_report/report_no_product.tsv` (and **Why no product** in the HTML report) says, for each sample and assay without a product, which oligo has no accepted site, its best site and the rule it fails; see [Assays without a product](Reports#assays-without-a-product). The most common reasons, in order:

1. **A site's Tm is too low.** Every primer and probe site must melt at `--anneal-temp` (default 60 °C) less `--tm-margin` (default 5 °C) or above: 55 °C. Mismatches, unpaired 5' bases and bases past a contig end lower a site's Tm. An oligo whose Tm on a perfect match is below that minimum (the run's log and `QAlog.txt` flag it) is unlikely to anneal anywhere, and its assay to give a product in any sample. If the assay runs at another temperature, give it with `--anneal-temp`, or with [`ta=`](Primer-FASTA-Format#annealing-temperature-optional) on its primer header when the file's assays differ; to accept weaker sites, raise `--tm-margin`. See [Amplicon Calling](Amplicon-Calling) and [Why Tm](Why-Tm).
2. **Product too long.** The default maximum amplicon length is 5000 bp. Raise it with `-l <bp>` or disable it with `-l 0`.
3. **3'-end mismatch.** By default the last 5 bases of each primer must match perfectly (the 3' bases BLAST trims off its alignment are compared with the template, and those that do not pair count as mismatches). Relax with `--3p-penalty` — see [Amplicon Calling](Amplicon-Calling#weighted-3-end-check---3p-window---3p-penalty).
4. **Wrong orientation.** Primer pairs on the same strand, or pointing away from each other, are never reported because they cannot amplify.
5. **The assembly breaks inside the product.** A product is called on one contig only. A sequence marked circular (`circular=true` in its header) is searched as a circle since 0.15.3. When the primers point off the ends of two contigs (or both ends of a circular one not marked as such), the run warns (`Warning: sample ..., assay ...: no product, but the primers bind near the ends of two contigs ...`; `Primers at contig ends:` in `QAlog.txt` and in the HTML report) (since 0.15.0); see [Limitations](Limitations).
6. **A known gap.** In FASTQ mode, the reads are baited on k-mers of the primers within 1 edit, and, for oligos of 18 to 21 bases, on 18-base k-mers within 2 substitutions: a primer site with more differences in each of its k-mers may bait no read, though its Tm would pass. See [Limitations](Limitations#known-gaps). (Since 0.15.3, products of one primer alone, at an inverted repeat, and of the primers of two assays of one reaction, `mix=`, are reported, marked as such.)
7. **Probe assay without a probe hit.** An assay that defines a `-P` probe is reported only when an accepted probe hit lies inside the amplicon. A probe site, like a primer site, must melt at the minimum Tm or above.

## Why does the report show huge amplicons from old versions but not anymore?

Versions before 0.8.0 paired any forward/reverse hits on the same contig, regardless of distance or orientation. Those "phantom" products would not amplify in a real reaction and are now filtered out.

## Does insilicoPCR model melting temperature, primer dimers or hairpins?

Melting temperature, yes, since 2.0; dimers and hairpins, no. Each primer and probe site is judged by its Tm on the template under it, computed as Primer3's `ntthal` computes it (ported to Java), in the reaction the run assumes (`--na`, `--mg`, `--dntp`, `--oligo-conc`). The site is accepted when its Tm reaches `--anneal-temp` less `--tm-margin`, and a primer's 3' end must also pair (`--3p-penalty`). Then come the sequence rules: whether the primers face each other on one sequence, the product size, and for qPCR assays whether the probe lies inside the product. Nothing models primer dimers, hairpins or other secondary structure, amplification efficiency, or competition between assays in a multiplex. Check those with a primer design tool such as Primer3. Before 2.0, sites were accepted by their mismatch counts (`-m`), not their Tm; see [Why Tm](Why-Tm). See [Limitations](Limitations) for this and the cases where the report can miss a product.

## Are G/C mismatches treated differently from A/T mismatches?

Yes. Along the whole site, the Tm weighs each mismatch by the pairs around it: breaking a G/C pair lowers the Tm more than breaking an A/T pair. Near the 3' end, the 3' rule adds its own weight: within the last 3 bases of a primer, a mismatch that breaks a G/C pair scores one extra penalty point compared to an A/T pair, because losing the stronger pairing destabilizes extension more. See the penalty table in [Amplicon Calling](Amplicon-Calling).

## What is the synthetic gel visualizer?

It is a visual summary of consolidated report results, displayed as gel-like bands. It is useful for reviewing amplicon size patterns across samples.
