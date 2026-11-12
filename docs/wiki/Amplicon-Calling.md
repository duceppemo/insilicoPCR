# Amplicon Calling

This page explains exactly when insilicoPCR reports an amplicon, and how to tune the rules. The same engine is used by the GUI and the CLI.

## Overview

BLAST+ (`blastn-short`) finds every place a primer matches a sample sequence. Raw BLAST hits are then passed through two stages:

1. **Hit filtering** — is this primer binding good enough to prime extension?
2. **Amplicon calling** — do an accepted forward hit and an accepted reverse hit form a product that would actually amplify?

Only pairs that pass both stages appear in the consolidated report.

## Stage 1: hit filtering

For every primer hit, insilicoPCR checks:

### Total mismatch budget (`-m, --mismatches`, default 0)

BLAST mismatches + insertions/deletions + primer bases at the 3' end that do not pair with the template must not exceed the budget. BLAST (`blastn-short`, +1/−3) trims a mismatch near the end of a local alignment together with the up to three matching bases beyond it, so a primer whose last bases do not all pair shows up as a shortened alignment, not as a reported mismatch. insilicoPCR reads the trimmed 3' bases from the sample sequence and counts the ones that do not pair: one mismatch two bases from the 3' end counts once, not three times. Ambiguous template bases (`N`, IUPAC codes) do not pair, and a 3' base beyond the end of a contig or read counts as a mismatch.

Unaligned bases at the harmless 5' end are tolerated without consuming the budget, up to `--5p-truncation` (default 5). The allowance counts the bases BLAST left out of the alignment, so a single SNP at the fourth base from a primer's 5' end uses 4 of it — and 5'-proximal mismatches barely affect priming in a real reaction, so the allowance is generous. These bases are never counted as mismatches, whether they pair or not: a primer with a 5' tail (an adapter, a GC clamp) does not pair there on purpose. The allowance is a number of bases, whatever the primer's length: raising it for one long primer (say to 8) also lets a 17-base primer through on a 9-base match. The report gives, of those bases, the ones that do not pair with the template (`Forward5PrimeUnaligned` / `Reverse5PrimeUnaligned`, see [Reports](Reports#primer-binding-columns)): 1 for the single SNP above, 8 for a 17-base primer held by its last 9 bases, so such products can be told from fully bound ones. See the [tutorial](Tutorial-Mixed-PCR-qPCR) for a real-data example.

### Weighted 3'-end check (`--3p-window`, `--3p-penalty`)

DNA polymerase extends from the primer 3' end, so mismatches there matter far more than at the 5' end — and breaking a strong G/C pair near the terminus is worse than breaking an A/T pair. Within the last `--3p-window` bases (default 5), every imperfection is scored:

| Imperfection | Penalty |
|---|---|
| Mismatch (or unaligned base) on the terminal 3' base | 3 |
| Mismatch 1–2 bases from the terminus | 2 |
| Mismatch 3 or more bases from the terminus | 1 |
| The broken pair involves a primer G or C within the last 3 bases | +1 |
| Insertion or deletion | +1 |

The sum must not exceed `--3p-penalty` (default **0**: a perfect 3' window is required).

Worked examples (with a window of 5):

| Situation | Penalty | Passes default (0)? |
|---|---|---|
| Perfect match over the whole window | 0 | yes |
| A/T mismatch 4 bases from the terminus | 1 | no — needs `--3p-penalty 1` |
| A/T mismatch on the terminal base | 3 | no — needs `--3p-penalty 3` |
| G/C mismatch on the terminal base | 4 | no — needs `--3p-penalty 4` |
| 1-base deletion 2 bases from the terminus (A/T) | 3 | no — needs `--3p-penalty 3` |

Remember that any tolerated 3' imperfection also consumes the total budget, so rescuing a terminal mismatch needs both, e.g. `-m 1 --3p-penalty 4`.

### Probes are different

Hydrolysis probes (`-P`) report by 5'→3' exonuclease cleavage while hybridized, not by extension, so the primer 3' rules do not apply to them (their 3' end is blocked anyway). Probes are also typically longer and designed ~8–10 °C hotter than the primers, which makes them more resilient: a single mismatch that would kill a primer's 3' end usually only delays Cq. Mismatch position still matters — central mismatches destabilize the duplex most, the 5' end (the flap Taq cleaves) is the most tolerant — but insilicoPCR models this with a simple separate budget:

- `--probe-mismatches <n>`: mismatches + indels allowed per probe (default: same as `-m`). For inclusivity screens, a probe budget of `-m` + 1 is a reasonable reflection of the higher melting temperature, e.g. `-m 1 --probe-mismatches 2`.
- Every probe base is compared with the template. BLAST (`blastn-short`, +1/−3) trims a mismatch near an alignment end together with the up to three matching bases beyond it, so insilicoPCR reads the trimmed bases from the sample sequence and counts the ones that differ: one mismatch at probe position 3 counts as 1 mismatch, wherever it sits. Ambiguous template bases (`N`, IUPAC codes) count as mismatches.
- Probe bases beyond the end of the template (a probe hanging off a contig or read end) cannot be compared; at most 2 such bases are allowed across both ends — intentionally independent of the primer `--5p-truncation` setting.
- An accepted probe hit must lie entirely within the amplicon.

## Stage 2: amplicon calling

An accepted forward hit and an accepted reverse hit are reported as an amplicon only when **all** of these hold:

1. **Same contig.** Both primers hit the same input sequence.
2. **Opposite strands, convergent.** One primer anneals to the plus strand and extends rightward; the other anneals to the minus strand and extends leftward — and they must face each other. Divergent (back-to-back) and same-strand pairs never amplify and are rejected. The assay may legitimately sit on either strand of your genome: `Strand` in the report shows `+` when the forward primer matched the plus strand, `-` when the assay matched the reverse strand.
3. **Product size limits.** The product (5' end of one primer to the 5' end of the other, inclusive) must be at least `--min-length` (default 0) and at most `--max-length` (default **5000 bp**). Both primers count in full, including 5' bases left unaligned within `--5p-truncation` and bases that lie beyond a contig end, so a product on a contig that stops inside a primer is sized as on a complete template (`AmpliconSize` can then exceed the span given in `GenomeLocation`). This suppresses "phantom" products from primer sites that are tens of kilobases — or megabases — apart, which would never amplify in a real reaction. Set `-l 0` to disable the cap (e.g., for long-range PCR assessments).
4. **Probe assays only: probe inside the product.** For an assay that defines a `-P` probe, a row is reported only if an accepted probe hit lies entirely within the amplicon. Probe-less assays in the same primer file are reported on their primer pair alone (per-assay decision; mixing both kinds in one run is supported since 0.8.0).

## Typical settings

| Goal | Suggested options |
|---|---|
| Strict assay validation (default) | `-m 0` |
| Tolerate one internal mismatch, perfect 3' end | `-m 1` |
| Also tolerate one distal (positions 4–5) 3'-window mismatch | `-m 1 --3p-penalty 1` |
| Inclusivity screening across divergent genomes | `-m 2 --3p-penalty 3` |
| Long-range PCR | `-l 20000` (or `-l 0` for no limit) |
| qPCR amplicon size sanity check | `-l 300 --min-length 50` |

## Degenerate primers

IUPAC degenerate bases are expanded into every explicit combination before BLAST (e.g. `...R...Y...` becomes 4 primers, suffixed `_0`, `_1`, ...). Mismatch counting and the 3' check are applied against each expanded variant, so a degenerate position never counts as a mismatch.

## BLAST e-value

The default `-e`/`--evalue` of `1e5` is deliberately permissive, and lowering it is not a way to make results
stricter. Each contig (or read) is the BLAST query and the primers are a tiny database, so a primer hit's e-value
grows with the length of the contig it sits on and climbs steeply when a few bases are trimmed or mismatched.
Hits are filtered afterwards by the rules on this page, which do not depend on contig length.

On two *Brucella* genome assemblies with a four-assay qPCR panel ([#7](https://github.com/duceppemo/insilicoPCR/issues/7)),
the weakest hit those rules accept had an e-value of 0.79 at `-m 0` (a 17-nt primer aligned over 12 bases, within
the 5' allowance) and 47 at `-m 2`. A cutoff of `1e-5` lost two assays that the assemblies carry; `0.05` dropped
6 accepted hits at `-m 0` and 31 at `-m 2`. Above `1000` (BLAST's own default for `blastn-short`) nothing changes:
`1e5` and `1000` returned identical hits. Keep the default unless runtime matters more than sensitivity.
