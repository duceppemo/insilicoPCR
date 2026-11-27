# Limitations

**insilicoPCR applies sequence rules and one melting temperature.** Each primer and probe site is judged by its Tm on the template (Primer3's thermodynamic alignment) and, for a primer, by its 3' end. Nothing in it models primer dimers, hairpins or other secondary structure, amplification efficiency or product yield, or competition between the assays of a multiplex. A product is reported when its primer sites melt at `--anneal-temp` less `--tm-margin` or above and pass the 3'-end rule, face each other on one sequence, lie within the size limits and, for a qPCR assay, have a probe site between them ([Amplicon Calling](Amplicon-Calling), [Why Tm](Why-Tm)). The same rules apply to every assay: an assay designed for other conditions than the run's may give no product in silico, and `report_no_product.tsv` says why. Whether the reaction then works is for a primer design tool, such as Primer3, and for the bench.

Within those rules, a few cases make the report say less than a real PCR would. Each is named in the output, so a negative can be told apart from "could not tell".

![Six cases where insilicoPCR can miss a product](https://raw.githubusercontent.com/duceppemo/insilicoPCR/master/docs/assets/limitations.svg)

## Assemblies (FASTA)

**1. Fragmented assembly** (`Primers at contig ends`). A product is called on one contig only. When the assembly breaks between the two primers, they bind on different contigs and no product is reported. A circular sequence is searched as a circle since 0.15.3, a product across its origin reported (see [Reports](Reports#circular-sequences)): a FASTA record whose header says `circular=true` (as Unicycler, Flye and Bakta write it) or `[topology=circular]`, and a contig of the FASTQ-mode mini-assembly that closes on itself. Mark the circles of an assembly whose headers do not say so. A break inside a primer's site counts too, since 0.15.3: a primer whose 3' end runs off a contig is not a site a product is called from, but it is named as pointing off that end. Since 0.15.4 it is named only when at least 12 of its bases (or half the primer, if that is more) are aligned on the contig: a few bases at a contig's end match some primer by chance. A primer of 12 bases or fewer is therefore never named so. Since 0.15.0, when an assay has no product but its primers point off the ends of two contigs, or off both ends of one linear sequence (a circular plasmid or chromosome not marked as one), and joining them would give a product within `--max-length`, the run says so: a `Warning:` line in the log, a `Primers at contig ends:` line in `QAlog.txt`, each with the smallest such product, and the sample's cell marked in the HTML report. That size (`a product of at least N bp`) is the bases of the two contigs from each primer's 5' end to the contig's end, or the longer primer's length if that is more (since 1.0; 0.15.4 added the primer bases past the contig ends, which may lie on the other contig where contigs overlap, and could overstate it). Use a more complete assembly, or run the reads.

## Reads (FASTQ)

The reads are baited and mini-assembled; an assay the mini-assembly misses is called from the reads themselves, and a product longer than a read is looked for by extending the reads ([FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives)). What remains is listed, with the reason, in `report_fastq_diagnostics.tsv`:

**2. Long repeat in the product** (`EXTENSION_THROUGH_UNRESOLVED_REPEAT`). The product passes through a repeat that has another copy in the sample and is longer than the read pairs can bridge (about 300 to 350 bp at 60x, 200 to 250 bp at 20x). A real product and a wrong turn into the other copy look the same there, so the product is withheld, real or not. An assembly of long reads settles it.

**3. The extended reads do not meet** (`NOT_CLOSED_BY_EXTENSION`). The product is longer than a read, and the reads extended from each side do not meet within the maximum product length: too few reads carry the primers, or the extension stops (a gap in coverage, a branch it cannot follow). A negative, weaker than one on an assembly. Sequence deeper, or run the assay on an assembly. Before 0.15.3 the extension reached only about a fragment past the reads that carry the primers, and a product longer than 1.5 to 2 kb ended here whatever the depth; it now baits more reads in rounds (see [Reports](Reports#read-extension-products-longer-than-a-read)). A product through a tandem repeat longer than about 30 bases ((AT)n, (CAG)n, a VNTR) is not joined either: the extended reads loop in the repeat, and the assay is `EXTENSION_IN_TANDEM_REPEAT`, inconclusive, since 0.15.3.

**4. Too few read pairs to confirm** (`EXTENSION_NOT_SUPPORTED_BY_PAIRS`). The extended reads hold a product, but some stretch of it lies inside no read pair, or the pairs are too few (about 20x and less). Withheld; confirm it on an assembly.

**5. Product in few of the reads** (`WEAK_READ_SUPPORT`). Complete products are in the reads, but in fewer than 2 or under `--read-min-fraction` (default 30%) of the informative reads (reads carrying a primer at the product's locus): contamination, index hopping, a mixed sample, or a locus of which only some copies match the assay. Withheld and warned about; lower `--read-min-fraction` if the sample is known to be mixed.

With `--read-extension off`, products longer than a read or merged pair (about 580 bp for 2 x 300 bp reads) are not looked for, and such an assay shows as `NOT_IN_READS`.

## Both

**6. Not simulated.** As above: a site's Tm, but no dimers or hairpins, no efficiency or yield, no multiplex competition, and no temperature profile beyond the one annealing temperature. A product the rules accept may still fail in the tube, and the synthetic gel is drawn from the report, not from a simulated reaction ([Synthetic Gel Visualizer](Synthetic-Gel-Visualizer#limitations)).

## Known gaps

One case makes the report miss a product that a real PCR would give. Unlike the six above, **nothing in the output names it**: the assay simply shows no product. It is known and will be revisited; until then, keep it in mind when a negative matters. (Products of one primer alone, and of the primers of two assays in one reaction, are reported since 0.15.3: see [Amplicon Calling](Amplicon-Calling#stage-2-amplicon-calling).)

**FASTQ: a primer site with several differences may bait no read.** The first bait keeps a read, with its mate, when one of its k-mers matches a k-mer of a primer or probe within 1 edit (since 2.0):

- **k** is the length of the shortest oligo less 5, but at least 17, and at most the shortest oligo's length less one (so that a deletion can match). For primers of 18 to 22 bases, k is 17; for a 25-base primer, 20.
- **Short oligos bait in a pass of their own** (since 2.0) when they are too short for that floor, and would otherwise bring every oligo's k down to theirs: the longer oligos then bait with the k of the shortest of them. The reads of both passes are pooled, each pair once. A 23-primer *Globodera* panel with 15-base probes, baited with 1 edit, took 59% of a sample's reads in one pass with k=14 (1.0.3 at `-m 1`), and 4.3% in two. The run took 38 minutes instead of 3 hours.
- **The budget** is 1 edit in a k-mer: a substitution, an insertion, or a deletion (a primer base the read lacks). BBDuk also takes the middle base of each k-mer as a wildcard (two for an even k). The bait only takes reads in; the Tm rule then decides which sites are accepted. Up to 1.x the budget was `-m` edits, at most 2, with a 3-substitution pass at `-m 3`.
- **A second pass** (since 2.0) keeps the reads within **2 substitutions** of an 18-base k-mer of an oligo of **18 to 21 bases**. One edit missed sites the Tm rule accepts, most of all in short primers, which have the fewest k-mers: 2 mismatches among bases 2 to 5 of an 18-base primer, whose k is 17, leave neither of its two 17-mers within 1 edit. On a *Globodera* sample (8.3 million read pairs, a 46-oligo panel), the pass took the baited pairs from 185,312 to 231,347 and the run from 310 to 357 s. For every oligo of 18 bases or more it took 588,550 pairs and 588 s: the 18-mers of the longer oligos match far more of a genome.

A primer site therefore baits its reads when some k consecutive bases of the primer differ from it by 1 edit at most, or, for an oligo of 18 to 21 bases, some 18 by 2 substitutions. A site that an assembly would give falls outside both when it has **2 or more differences in every k-mer of the first bait**, and, in an oligo of 18 to 21 bases, **3 or more (or an indel) in every 18-mer**. Such a site can still melt above the minimum Tm, mostly in a long or G/C-rich oligo: the Tm rule accepts sites that no k-mer bait that takes in a sensible share of the reads would match.

How often, measured on 9 real panels (140 oligos: the *Brucella*, potato cyst nematode, EPPO, endosymbiont and microsatellite panels and the two examples), with BBDuk's own matching checked on 6,000 synthetic reads. Of random sites the Tm rule accepts at 60 °C (and BLAST can find), the bait misses:

| Substitutions in the site | 1 edit alone | With the second pass (2.0) | Were the second pass for every oligo |
|---|---|---|---|
| 2 | 7% | 1.3% | 0.4% |
| 3 | 19% | 13% | 7% |
| 4 | 29% | 24% | 12% |

At worst, placed to dodge the bait, most oligos can carry a site above 55 °C that no pass takes in (98 of the 140 with 1 edit alone, 75 with the second pass for every oligo). A product is lost only when every primer's site is missed, or when the product is longer than the reads the baited ones bring in: the second bait takes the pairs that share a k-mer with reads holding an accepted site, so one baited primer usually brings the region of a short product. On 48 *Brucella* read sets and their assemblies, the bait lost no product.

When a site baits no read, the same sample gives the product as an assembly and none as reads. Because no read carries the primer, `report_fastq_diagnostics.tsv` does not list the assay either. When only one primer's site is missed, it lists the assay as `PARTIAL_BAIT`, which is then this gap, not a true negative. (`PARTIAL_BAIT` also lists a primer the reads carry at a site whose Tm is too low; its `ReadDetail` then says the mini-assembly holds a site the rules reject, and that is a call of the Tm rule, not this gap. It says so only of a site within 10 °C of the Tm it needs, or that fails the 3' rule alone and differs from the primer by 3 bases at most: not of a chance match of a few bases.) Before 0.15.3 the k-mers were as long as the shortest primer and substitutions only, so any indel kept a site from baiting. When such sites are expected (divergent strains, degenerate or tailed primers, a large `--tm-margin`), run the assay on an assembly of the reads as well.

See also [Why is an amplicon I expected missing from the report?](FAQ#why-is-an-amplicon-i-expected-missing-from-the-report) for products the rules reject on purpose (a site's Tm, 3'-end mismatches, size limit, orientation, probe); `report_no_product.tsv` names the rule for each assay.

**Gap characters in an assembly shift the locations.** BLAST drops `-` (and other characters that are not bases) from a sequence it reads, and positions are counted on what it keeps: in a FASTA file holding `-` characters, as an aligned FASTA does, `GenomeLocation` is off by their number before the product from a position counted with them. Sizes and mismatches are not affected.
