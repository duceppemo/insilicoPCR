# Limitations

**insilicoPCR applies sequence rules only.** Nothing in it models melting or annealing temperature, primer dimers, hairpins or other secondary structure, amplification efficiency or product yield, or competition between the assays of a multiplex. A product is reported when its primers bind the template within the mismatch budget and the 3'-end rules, face each other on one sequence, lie within the size limits and, for a qPCR assay, have the probe between them ([Amplicon Calling](Amplicon-Calling)). Whether the reaction then works at your annealing temperature is for a primer design tool, such as Primer3, and for the bench.

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

**6. Not simulated.** As above: no temperatures, no dimers or hairpins, no efficiency or yield, no multiplex competition. A product the rules accept may still fail in the tube, and the synthetic gel is drawn from the report, not from a simulated reaction ([Synthetic Gel Visualizer](Synthetic-Gel-Visualizer#limitations)).

## Known gaps

One case makes the report miss a product that a real PCR would give. Unlike the six above, **nothing in the output names it**: the assay simply shows no product. It is known and will be revisited; until then, keep it in mind when a negative matters. (Products of one primer alone, and of the primers of two assays in one reaction, are reported since 0.15.3: see [Amplicon Calling](Amplicon-Calling#stage-2-amplicon-calling).)

**FASTQ: a primer site with many differences may bait no read.** The first bait keeps a read, with its mate, when one of its k-mers matches a k-mer of a primer or probe within an edit budget, and at `-m 3` a second pass keeps those within 3 substitutions:

- **k** is the length of the shortest oligo less `--5p-truncation`, but at least 13 at `-m 0` (exact k-mers) or 16 plus `-m` (with edits), and at most the shortest oligo's length (one base less when `-m` is 1 or more, so that a deletion can match). For a 20-base primer at the default `--5p-truncation 5`, k is 15 at `-m 0`, 17 at `-m 1`, 18 at `-m 2` and 19 at `-m 3`. Up to 1.0.3 the floor was 16 at `-m 0` too.
- **Short oligos bait in a pass of their own** (since 1.0.4) when they are too short for that floor, and would otherwise bring every oligo's k down to theirs: the longer oligos then bait with the k of the shortest of them. The reads of both passes are pooled, each pair once. With 15-base probes and `-m 1 --5p-truncation 8`, a 23-primer *Globodera* panel baited 59% of a sample's reads in one pass with k=14 (up to 1.0.3), and 4.3% in two (oligos of 15 to 17 bases with k=14, the others with k=17). The run took 38 minutes instead of 3 hours. The assays with a product of the expected size stayed the same, but the run gave 1,014 products instead of 2,511. Every product it no longer gives had a mismatch or unpaired 5' bases in both primer sites. Their reads had been baited only because the single pass took most of the genome. At `-m 3`, 15-base k-mers with 2 edits still match most reads, and two passes bait about 91% of them instead of 99.7%.
- **The budget** is `-m` edits, at most 2: substitutions, insertions, and **at most one deletion** (a primer base the read lacks). At `-m 3` or more it is still 2: BBDuk matches 3 substitutions (`hdist=3`) only when it allows no indels (`edist=0`), and this bait allows both, so BBDuk uses the edit budget alone.
- **At `-m 3` or more, a second pass** (since 1.0.3) keeps the reads with a 19-base k-mer within 3 substitutions, and no indel, of an oligo of 19 bases or more. Its reads are added to the first bait's, each pair once. Up to 1.0.2 there was no such pass, and `-m 3` never baited a site by its 3 substitutions. 19 is 16 bases plus one for each substitution: 3 substitutions in 16-mers baited half the reads of SRR642185; in 19-mers, 0.4%. The pass reads the reads once more; on SRR642185 at `-m 3` the run took the same time.

A primer site therefore always baits its reads when some k consecutive bases of the primer differ from it by no more than that budget, or, at `-m 3`, when the primer is 19 bases or longer and the site differs from it by 3 substitutions at most. A site that an assembly would give can fall outside these in three ways:

1. **3 or more differences, at `-m 3` or more,** with 3 in every k-mer of the primer, when they are not all substitutions (a deletion and 2 substitutions, say), or when the primer is shorter than 19 bases.
2. **Two deletions,** with both in every k-mer.
3. **5' mismatches that `--5p-truncation` lets an assembly hit leave unaligned,** beyond the bases the k-mer leaves out of the primer (its length less k). These count against the budget. At `-m 0` a primer of 18 bases or more leaves out all 5 bases that `--5p-truncation 5` frees; a 17-base primer has k = 13 and leaves out 4, so a mismatch at its 5th base from the 5' end is in every k-mer, and the site baits no read. With edits the floor is higher (17 at `-m 1`), and more primers are too short for the 5' window. Up to 1.0.3, k was at least 16 at `-m 0`, and a 20-base primer with a mismatch at its 5th base baited no read.

Measured on 300 random primers of each length, with the differences placed at random outside the 5' window, the share of sites that baited reads was as follows. A site within the budget baited in every case (1 or 2 substitutions; an insertion or deletion, with or without a substitution; two insertions). Some sites outside the rule bait too: an indel can stand in for a substitution, and BBDuk takes the middle base of each k-mer as matching any base (`maskmiddle`). In brackets, the share before the substitution pass (1.0.2), measured on the same sites.

| Primer length (k at `-m 2` / `-m 3`) | 2 deletions, `-m 2` | 2 deletions, `-m 3` | 3 substitutions, `-m 3` | deletion + 2 substitutions, `-m 3` | insertion + 2 substitutions, `-m 3` |
|---|---|---|---|---|---|
| 18 (17 / 17) | 37% | 43% | 65% | 50% | 67% |
| 20 (18 / 19) | 47% | 29% (23%) | 100% (51%) | 46% (38%) | 64% (58%) |
| 22 (18 / 19) | 69% | 52% (46%) | 100% (82%) | 71% (66%) | 85% (80%) |
| 25 (20 / 20) | 64% | 75% (66%) | 100% (97%) | 93% (86%) | 97% (95%) |

When a site baits no read, the same sample gives the product as an assembly and none as reads. Because no read carries the primer, `report_fastq_diagnostics.tsv` does not list the assay either. When only one primer's site is missed, it lists the assay as `PARTIAL_BAIT`, which is then this gap, not a true negative. Before 0.15.3 the k-mers were as long as the shortest primer and substitutions only, so any indel, or 5' mismatches beyond `-m`, kept a site from baiting. When such sites are expected (divergent strains, degenerate or tailed primers, `-m 3`), run the assay on an assembly of the reads as well.

See also [Why is an amplicon I expected missing from the report?](FAQ#why-is-an-amplicon-i-expected-missing-from-the-report) for products the rules reject on purpose (size limit, 3'-end mismatches, orientation, probe).

**Gap characters in an assembly shift the locations.** BLAST drops `-` (and other characters that are not bases) from a sequence it reads, and positions are counted on what it keeps: in a FASTA file holding `-` characters, as an aligned FASTA does, `GenomeLocation` is off by their number before the product from a position counted with them. Sizes and mismatches are not affected.
