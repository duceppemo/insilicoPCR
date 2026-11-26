# Amplicon Calling

This page explains exactly when insilicoPCR reports an amplicon, and how to tune the rules. The same engine is used by the GUI and the CLI.

## Overview

BLAST+ (`blastn-short`) finds every place a primer matches a sample sequence. Raw BLAST hits are then passed through two stages:

1. **Hit filtering** — is this primer binding good enough to prime extension?
2. **Amplicon calling** — do an accepted forward hit and an accepted reverse hit form a product that would actually amplify?

Only pairs that pass both stages appear in the consolidated report.

These are **sequence rules plus one melting temperature**: a site is judged by its Tm on the template, as Primer3 computes it, and by how well a primer's 3' end pairs. Nothing models primer dimers, hairpins, amplification efficiency, or competition between assays in a multiplex. See [Limitations](Limitations) and, for why the Tm decides, [Why Tm](Why-Tm).

The same rules apply to every oligo of every assay; only the annealing temperature can be the assay's own, the one it was published at (`ta=` in the [primer file](Primer-FASTA-Format#annealing-temperature-optional)). An assay that fails them in silico, because it is poorly designed or because its published conditions differ, gives no product, and the report says why ([report_no_product.tsv](Reports#assays-without-a-product)).

## Stage 1: hit filtering

For every primer or probe hit, insilicoPCR checks:

### Tm on the template (`--anneal-temp`, `--tm-margin`)

The oligo's Tm on the template under it must reach `--anneal-temp` (default **60 °C**) less `--tm-margin` (default **5 °C**): 55 °C by default. Since 2.0 this one rule replaces the mismatch counts of 1.x (`-m`, `--probe-mismatches`, `--5p-truncation`, all removed). An assay whose primer header gives its own annealing temperature (`ta=55`) is judged at it instead, so that assays published at different temperatures run in one file ([Primer FASTA Format](Primer-FASTA-Format#annealing-temperature-optional)).

The Tm is computed as Primer3's `ntthal` computes it (its thermodynamic alignment, ported to Java): the oligo is aligned to the template strand under it, with its mismatches, bulges, internal loops and dangling ends, and the nearest-neighbour free energies give the Tm. The template is the site BLAST aligned, with the bases BLAST left out of its alignment on either side, as far as the sequence goes. So:

- a mismatch costs what it costs where it sits: a G/C pair broken in the middle of a primer lowers the Tm more than an A/T pair at its 5' end;
- bases at a primer's 5' end that do not pair (a tail, an adapter, a SNP) are not free up to a count, as in 1.x: they lower the Tm, and a site still passes when the rest of the primer melts above the minimum;
- oligo bases past the end of a contig or read have no template: they lower the Tm too;
- ambiguous template bases (`N`, IUPAC codes) do not pair.

The Tm depends on the reaction. These optional settings describe it; the defaults are those of a typical qPCR reaction:

| Option | Default | Meaning |
|---|---|---|
| `--na` | 50 mM | monovalent cations (Na+, K+) |
| `--mg` | 3 mM | Mg2+ |
| `--dntp` | 0.8 mM | dNTP (they bind Mg2+) |
| `--oligo-conc` | 250 nM | each primer and probe |

The run's log and `QAlog.txt` list each oligo's Tm on a perfect match (a degenerate oligo's range, on one line). One below the minimum is flagged: it is unlikely to anneal anywhere at this temperature, and its assay to give a product in any sample. A site with a mismatched or unpaired end base can melt a little above the perfect match (its dangling base stacks on the duplex), so the flag is a warning, not a verdict: the report judges each site. To accept weaker sites, lower `--anneal-temp` (or the assay's `ta=`) or raise `--tm-margin`.

### Weighted 3'-end check (`--3p-window`, `--3p-penalty`)

A primer must also pass the 3' rule. DNA polymerase extends from the primer 3' end, so mismatches there matter far more than at the 5' end — and breaking a strong G/C pair near the terminus is worse than breaking an A/T pair. A duplex can melt well above the minimum and still extend poorly from a mismatched 3' end: the Tm does not see that, this rule does. BLAST (`blastn-short`, +1/−3) trims a mismatch near the end of a local alignment together with the matching bases beyond it, so insilicoPCR reads the trimmed 3' bases from the sample sequence and compares them with the primer: one mismatch two bases from the 3' end counts once, not three times. A 3' base beyond the end of a contig or read counts as a mismatch. Within the last `--3p-window` bases (default 5), every imperfection is scored:

| Imperfection | Penalty |
|---|---|
| Mismatch (or unaligned base) on the terminal 3' base | 3 |
| Mismatch 1–2 bases from the terminus | 2 |
| Mismatch 3 or more bases from the terminus | 1 |
| The broken pair involves a primer G or C within the last 3 bases | +1 |
| Insertion or deletion (only where BLAST places one: see below) | the mismatch penalty of its position (3, 2 or 1 as above), +1 if a primer G or C within the last 3 bases has no template base, and +1 for the indel. Each base of an indel is scored. An extra template base between two primer bases is scored at the primer base on its 3' side, without the G/C point |

The sum must not exceed `--3p-penalty` (default **0**: a perfect 3' window is required).

Worked examples (with a window of 5):

| Situation | Penalty | Passes default (0)? |
|---|---|---|
| Perfect match over the whole window | 0 | yes |
| A/T mismatch 4 bases from the terminus | 1 | no — needs `--3p-penalty 1` |
| A/T mismatch on the terminal base | 3 | no — needs `--3p-penalty 3` |
| G/C mismatch on the terminal base | 4 | no — needs `--3p-penalty 4` |
| 1-base deletion in the template 2 bases from the terminus (A/T) | 7 (3 mismatches: 2 + 2 + 3) | no — needs `--3p-penalty 7` |

A site must pass both rules: a tolerated 3' mismatch also lowers the site's Tm, which must still reach the minimum.

**Indels near a primer end.** BLAST (`blastn-short`) keeps a gap in an alignment only when about 8 matching bases follow it, so it never places an indel within about 7 bases of either end of a primer: it trims the alignment there instead. Near the 3' end, the trimmed bases are then compared with the template base for base, and an indel shows in the 3' penalty as one mismatch for every base it shifts (the deletion above: 3 mismatches, penalty 7). An indel further in is placed by BLAST; the indel row of the table applies there, which with the default window of 5 is never inside it. The Tm does not depend on where BLAST places an indel: the thermodynamic alignment finds its own, with the bases BLAST left out.

### Probes are different

Hydrolysis probes (`-P`) report by 5'→3' exonuclease cleavage while hybridized, not by extension, so the primer 3' rule does not apply to them (their 3' end is blocked anyway). A probe site is accepted by the Tm rule alone, with the same minimum as the primers. Probes are typically longer and designed ~8–10 °C hotter than the primers, so a site with a mismatch or two usually still melts above the minimum: the Tm gives them the tolerance that 1.x gave through a separate budget (`--probe-mismatches`, removed in 2.0).

- Every probe base is compared with the template: the thermodynamic alignment reads the bases BLAST left out of its alignment too. Ambiguous template bases (`N`, IUPAC codes) do not pair.
- Probe bases beyond the end of the template (a probe hanging off a contig or read end) have no template: they lower the Tm.
- An accepted probe hit must lie entirely within the amplicon.

## Stage 2: amplicon calling

Two accepted primer hits are reported as an amplicon only when **all** of the rules below hold. The pairs looked at (since 0.15.3; before, the first only):

- an assay's forward primer with its reverse primer, as designed;
- **one primer alone**: an assay's forward primer with a forward primer of the same assay (itself, or another variant), or a reverse with a reverse. A primer that binds the plus strand at one place and the minus strand at another, facing within the size limit, as where a sequence repeats inverted, amplifies by itself in a real reaction. Such a product is reported under its assay, with its size check, and marked: `ForwardPrimer` and `ReversePrimer` name the same role (`toxA-F` and `toxA-F`, the first the one on the plus strand), `Pairing` is `one-primer`, and the HTML report tags it **one primer**;
- **two assays that share a reaction**: when the primer file says assays are run in one tube (`mix=`, see [Primer FASTA Format](Primer-FASTA-Format#assays-in-one-reaction-multiplex)), any primer of one with any primer of the other, either role. Such a product is reported under the two assays' names joined by `+` (`stxQ+toxA`, its own column of the HTML report, with no size check and no probe asked for), with `Pairing` `two-assays`, and tagged **two assays**. Assays without `mix=` are run on their own and pair with no other.

In one reaction, a product at one place (contig, start, end, size) is reported once: two assays may repeat a shared primer under each one's ID, and the one molecule it makes would otherwise be listed under each assay and again as a product of the two. The row kept is the assay's designed product if one is, else a product of one primer, else of two assays; between equals, the first assay by name. Assays run on their own each report what their primers make, since each is its own tube.

FASTQ input: products called from the reads themselves ([read-level calls](Reports#fastq-mode-and-false-negatives)), and from extended reads, are an assay's forward with its reverse primer only, and so are the products the FASTQ steps count as the assay's own (in the diagnostics, and to decide which assays reads are extended for): a read folding back on one primer, or two reads of two assays, is no call. The mini-assembly, searched like an assembly, gives the others.

1. **Same contig.** Both primers hit the same input sequence.
2. **Opposite strands, convergent.** One primer anneals to the plus strand and extends rightward; the other anneals to the minus strand and extends leftward — and they must face each other. Divergent (back-to-back) and same-strand pairs never amplify and are rejected. Primers may overlap head to head (a product shorter than the two primers together), as long as each primer's 3' end lies on bases of the other primer that pair with the template, unbroken, up to that primer's 3' end: past every base of it that does not pair, wherever it is: a 5' base BLAST left out of its alignment (and that differs from the template), a mismatch or indel BLAST kept in the alignment, or a 3' base left out (then no primer may overlap it). A 3' end on such a base would sit on a mismatch in the strand the other primer made. Since 1.0; 0.15.4 already kept a 3' end off 5' bases BLAST left out, but let one sit on a mismatch BLAST kept aligned. The assay may legitimately sit on either strand of your genome: `Strand` in the report shows `+` when the forward primer matched the plus strand, `-` when the assay matched the reverse strand.
3. **Product size limits.** The product (one primer, the template between the two primers' 3' ends, and the other primer) must be at least `--min-length` (default 0) and at most `--max-length` (default **5000 bp**). Both primers count in full, including 5' bases left unaligned and bases that lie beyond a contig end, so a product on a contig that stops inside a primer is sized as on a complete template: at the contig's start, `AmpliconSize` then exceeds the span of `GenomeLocation`, which starts at base 1; at its end, `GenomeLocation` runs past the contig's last base. An indel in a primer site does not change `AmpliconSize`: the primer, not the site, is copied into the product (before 0.15.3 it changed the size by its length). This holds for an indel BLAST places in its alignment, and for one near the 5' end; one within about 7 bases of the 3' end, which BLAST does not place and which is compared base for base (above), still shifts the size by its length — such a site passes only permissive 3' settings, and seldom primes. `GenomeLocation` is the template's, from one primer site's 5' end to the other's: for an indel BLAST places inside a primer site, its span differs from `AmpliconSize` by the indel's length; an indel near either end of a primer, which BLAST does not place, is not seen in the location, which is worked out as if the site had none, and its span is the size. This suppresses "phantom" products from primer sites that are tens of kilobases — or megabases — apart, which would never amplify in a real reaction. Set `-l 0` to disable the cap (e.g., for long-range PCR assessments).
4. **Probe assays only: probe inside the product.** For an assay that defines a `-P` probe, a row is reported only if an accepted probe hit lies entirely within the amplicon (a product of one primer alone too; a product of two assays is reported whatever probe sees it). Probe-less assays in the same primer file are reported on their primer pair alone (per-assay decision; mixing both kinds in one run is supported since 0.8.0).

## Typical settings

| Goal | Suggested options |
|---|---|
| Assay validation (default) | none: sites melting at 55 °C or more, perfect 3' window |
| The reaction anneals at another temperature | `--anneal-temp 58` (sites then need 53 °C) |
| Assays published at different temperatures | `ta=<°C>` on a primer header of each ([Primer FASTA Format](Primer-FASTA-Format#annealing-temperature-optional)) |
| Accept weaker sites, perfect 3' end | `--tm-margin 8` (or a lower `--anneal-temp`) |
| Also tolerate one distal (positions 4–5) 3'-window mismatch | `--3p-penalty 1` |
| A master mix with other salt or oligo amounts | `--na`, `--mg`, `--dntp`, `--oligo-conc` |
| Long-range PCR | `-l 20000` (or `-l 0` for no limit; FASTQ read extension still reaches 5000 bases each way then) |
| Check product sizes (qPCR or PCR) | declare them in the primer file (`size=`, `other=`, see [Primer FASTA format](Primer-FASTA-Format)): every product is still reported, labelled by its size |

## Degenerate primers

IUPAC degenerate bases are expanded into every explicit combination before BLAST (e.g. `...R...Y...` becomes 4 primers, suffixed `_0`, `_1`, ...). The Tm and the 3' check are worked out for each expanded variant, so a degenerate position never counts as a mismatch. Several variants (or several primers of one role, `assay-F_1`, `assay-F_2`) can make the same product; it is reported once, in one row naming the variants that bind best (fewest primer mismatches, then fewest probe mismatches, then fewest primer bases unpaired at the 5' end: a mismatch at a primer's first bases is left out of BLAST's alignment, and counts there, since 0.15.3), as for a product called from reads. Before 0.15.2, each variant pair that made it gave a row of its own: up to 64 rows for one product of two primers with three degenerate bases each.

## BLAST e-value

The default `-e`/`--evalue` of `1e5` is deliberately permissive, and lowering it is not a way to make results
stricter. Each contig (or read) is the BLAST query and the primers are a tiny database, so a primer hit's e-value
grows with the length of the contig it sits on and climbs steeply when a few bases are trimmed or mismatched.
Hits are filtered afterwards by the rules on this page, which do not depend on contig length. A sequence longer than
1 Mb (a chromosome) is searched in overlapping 1 Mb pieces since 0.15.3, its hits put back on it: on a whole
chromosome, a primer site with 2 or 3 mismatches could get an e-value over the cut-off and be dropped, though a
draft of the same genome kept it (see [Reports](Reports#detailed-per-sample-reports)).

On two *Brucella* genome assemblies with a four-assay qPCR panel ([#7](https://github.com/duceppemo/insilicoPCR/issues/7)),
the weakest hit the 1.x rules accepted had an e-value of 0.79 with no mismatch allowed (a 17-nt primer aligned over
12 bases, within the 5' allowance of 1.x) and 47 with 2 allowed. A cutoff of `1e-5` lost two assays that the
assemblies carry; `0.05` dropped 6 accepted hits with no mismatch allowed and 31 with 2. Above `1000` (BLAST's own default for `blastn-short`) nothing changes:
`1e5` and `1000` returned identical hits. Keep the default unless runtime matters more than sensitivity.
