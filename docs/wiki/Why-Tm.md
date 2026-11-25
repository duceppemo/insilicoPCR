# Why Tm

insilicoPCR 1.x accepted a primer site when it had few enough mismatches (`-m`), with a few 5' bases free (`--5p-truncation`). Since 2.0 a site is accepted when its **melting temperature on the template** reaches the annealing temperature less a margin (60 − 5 = 55 °C by default), and a primer's 3' end still has to pair ([Amplicon Calling](Amplicon-Calling)). The Tm is Primer3's thermodynamic alignment (`ntthal`), ported to Java. This page shows why the Tm is the better rule, on data, and where the two disagree.

In short: a mismatch count says how many bases differ, not how well the oligo binds. On real data the count lets through sites that cannot bind at the reaction's temperature and turns away sites that can. The Tm puts the sites in the order a reaction does, with one rule for every assay: its sites judged at its annealing temperature, the run's or the one its primer header gives (`ta=`, [Primer FASTA Format](Primer-FASTA-Format#annealing-temperature-optional)).

## A count does not weigh a mismatch

One mismatch, or five unpaired 5' bases, counted the same in 1.x wherever they were and whatever the primer. Their cost in Tm (perfect site against changed site, 60 °C reaction defaults; mismatches outside the 3' window, which the 3' rule judges anyway):

| Primer | Length | Tm, perfect site | Tm, one mismatch | Tm, 2 / 5 / 8 unpaired 5' bases |
|---|---|---|---|---|
| `GGAAGTAAAAGTCGTAACAAGG` (ITS5) | 22 | 60.1 °C | 43.6 to 58.9 °C | 57.5 / 51.6 / 48.3 °C |
| `ACGGACACATGCCCGCTA` | 18 | 66.3 °C | 56.0 to 67.5 °C | 61.3 / 55.5 / 42.1 °C |
| `GTGTCTCTAATTTGCCATT` | 19 | 55.0 °C | 35.0 to 53.7 °C | 51.1 / 41.7 / 35.1 °C |
| `TTTCCTCATGCAATTCAAAACC` | 22 | 60.4 °C | 43.2 to 60.5 °C | 59.1 / 52.6 / 48.2 °C |
| `CCAGCAGATTCTTTTTTACATCA` | 23 | 60.1 °C | 52.1 to 59.4 °C | 55.9 / 48.3 / 43.3 °C |

- **Where a mismatch sits matters.** In the ITS5 primer, one mismatch costs 1 °C at its 5' end and 17 °C at its 8th base. To 1.x both were "1 mismatch".
- **The primer matters.** Five unpaired 5' bases leave a GC-rich 18-mer at 55.5 °C, still a site, and an AT-rich 19-mer at 41.7 °C, not one. To 1.x at `--5p-truncation 5` both were perfect.
- **A perfect site is not always a site.** `GTGTCTCTAATTTGCCATT` melts at 55.0 °C on its own perfect complement: at 60 °C it barely binds anywhere. A count of 0 cannot say so; the run's log lists every oligo's own Tm and flags one below the minimum.

## On real data: every primer site in an assembly

Every primer site in the mini-assembly of a *Globodera pallida* cyst sample (Illumina reads, CFIA; 16 PCR and qPCR assays for potato cyst nematodes, with the EPPO PM 7/40 tests), sorted by the smallest `-m` at which 1.x accepts it, against its Tm:

![Tm of each primer site, by the smallest -m at which 1.x accepts it](https://raw.githubusercontent.com/duceppemo/insilicoPCR/master/docs/assets/tm/site-tm-by-mismatches.png)

| 1.x accepts the site at | Sites | Melting at 55 °C or more |
|---|---|---|
| `-m 0` (1.x default) | 74 | 37 (50%), the others from 47 °C |
| `-m 1` | 906 more | 27 (3.0%) |
| `-m 2` | 4,517 more | 20 (0.4%) |
| `-m 3` | 12,416 more | 3 |
| `-m 4` | 21,359 more | none |

The count fails both ways:

- **It lets through sites that cannot bind.** Half of the sites 1.x accepts by default melt below 55 °C, down to 47 °C. Each mismatch allowed then lets in thousands of sites for a few dozen that bind: at `-m 2`, 5,497 sites, 84 of them at 55 °C or more.
- **It turns away sites that can.** 47 sites that need `-m 1` or `-m 2` melt above 55 °C, up to 62.4 °C, above half of the sites `-m 0` accepts. No `-m` keeps them and drops the others.

## On whole runs

The products of the same data, as 1.x calls them at its default and with mismatches allowed, and as 2.0 calls them at its default (60 °C, margin 5):

| Data | 1.x, `-m 0` (default) | 1.x, mismatches allowed | 2.0 default |
|---|---|---|---|
| 46 *Brucella* genome assemblies (NCBI), 4-assay qPCR panel | 21 | 59 (`-m 3 --5p-truncation 8`) | 22 |
| *G. pallida* sample, mini-assembly as FASTA | 0 | 241 (`-m 1 --5p-truncation 8`) | 0 |
| *G. pallida* sample, reads (FASTQ mode) | 3 | 1,021 (`-m 1 --5p-truncation 8`) | 3 |

- ***Brucella*.** 2.0 gives the 21 products of strict 1.x, and one more: BM_10's 147 bp product on GCA_000292205.2, whose primers pair perfectly and whose probe has one mismatch in a site that still melts above 55 °C. `-m 0` rejects it on the probe's count. Allowing mismatches added 37 products, all of BA and Bru_genus, from sites with mismatches and unpaired 5' bases; 2.0 gives none of them.
- ***G. pallida* reads.** 2.0 and strict 1.x give the same three *G. pallida* products: NakhlaPal, NakhlaPalPCR and ReidPal, each at its target size, called from reads whose primer and probe sites pair perfectly. Allowing one mismatch and 8 free 5' bases in 1.x gave 1,018 more, 980 of them from the *G. rostochiensis* and *G. tabacum* tests NakhlaRosPCR and NakhlaTabPCR (33 at their target size), on a *G. pallida* sample. 2.0 gives none of them.

So 2.0 at its default is as strict as 1.x at `-m 0` where that was right, and gives what `-m 0` missed when the site still binds (BM_10). 1.x users who needed that loosened `-m` and `--5p-truncation`, and got the false products with it: the Tm needs no such choice. For every assay without a product, `report_no_product.tsv` gives the reason. On the *G. pallida* reads it also names GamelRos-R, whose own Tm on a perfect match is 54.6 °C: at 60 °C it anneals nowhere, whatever the sample.

## Where the Tm says no

The Tm rule is the same for every assay, and some published assays fail it in silico, even at their own annealing temperature. On the *G. pallida* reads, the EPPO PM 7/40 test of Bulman and Marshall (1997), run at its published 60 °C, gives no product: its universal ITS5 primer differs from *G. pallida* at 5 of its first 6 bases, and its site melts at 51.5 °C. `report_no_product.tsv` says so:

```text
BMpal  BMpal-F  no site reaches 55 °C in the mini-assembly (the FASTQ diagnostics say what the reads
                show); the best melts at 51.5 °C (1 mismatch or indel, 4 unpaired 5' bases)
```

1.x at `-m 0` gave no product either: the mismatch at the 6th base is past its 5 free bases.

The test works at the bench: from the second cycle on, the primer anneals to copies of the product, which carry its whole sequence. insilicoPCR does not try to model that for one assay: with `--tm-margin 10` (sites at 50 °C), the reads give its 266 bp product, from 1,535 of 1,696 informative reads (91%). This is the trade: one rule for every assay, a stated reason when an assay fails it, and the user decides whether to relax it.

## What the Tm does not do

- It is the Tm of the duplex at equilibrium, for the reaction the settings describe (`--na`, `--mg`, `--dntp`, `--oligo-conc`; the defaults are a typical qPCR mix). It is not an amplification efficiency, and it does not see primer dimers, hairpins or competition between assays.
- The 3' rule is still needed: a duplex can melt above the minimum with a mismatched 3' end that the polymerase does not extend.
- The comparisons above are in silico: they show that the Tm and a mismatch count disagree, and on which sites, not wet-lab outcomes. The *G. pallida* data are not public; the *Brucella* genomes are on NCBI.
- It costs time: 46 *Brucella* genomes take 29 s instead of 18 s, and the *G. pallida* reads 30 min instead of 26 min at `-m 0` (44 min at `-m 1 --5p-truncation 8`, which has many more weak products to follow in the reads).

See also [Amplicon Calling](Amplicon-Calling) for the rules, [Reports](Reports) for the `ForwardTm`/`ReverseTm`/`ProbeTm` columns and `report_no_product.tsv`, and [Limitations](Limitations).
