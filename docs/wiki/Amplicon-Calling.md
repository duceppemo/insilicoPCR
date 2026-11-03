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

BLAST mismatches + insertions/deletions + primer bases BLAST left unaligned at the 3' end must not exceed the budget. Unaligned 3' bases count because BLAST trims terminal mismatches from local alignments — a primer whose last base does not pair shows up as a shortened alignment, not as a reported mismatch.

Up to 2 unaligned bases at the harmless 5' end are tolerated without consuming the budget.

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

Hydrolysis probes (`-P`) report by 5'→3' exonuclease cleavage, not by extension, so the 3' rules do not apply to them. A probe hit only needs to fit the total mismatch budget and cover (nearly) the whole probe.

## Stage 2: amplicon calling

An accepted forward hit and an accepted reverse hit are reported as an amplicon only when **all** of these hold:

1. **Same contig.** Both primers hit the same input sequence.
2. **Opposite strands, convergent.** One primer anneals to the plus strand and extends rightward; the other anneals to the minus strand and extends leftward — and they must face each other. Divergent (back-to-back) and same-strand pairs never amplify and are rejected. The assay may legitimately sit on either strand of your genome: `Strand` in the report shows `+` when the forward primer matched the plus strand, `-` when the assay matched the reverse strand.
3. **Product size limits.** The product (5' end of one primer to the 5' end of the other, inclusive) must be at least `--min-length` (default 0) and at most `--max-length` (default **5000 bp**). This suppresses "phantom" products from primer sites that are tens of kilobases — or megabases — apart, which would never amplify in a real reaction. Set `-l 0` to disable the cap (e.g., for long-range PCR assessments).
4. **qPCR mode only: probe inside the product.** When the primer file contains `-P` entries, a row is reported only if an accepted probe hit lies entirely within the amplicon.

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
