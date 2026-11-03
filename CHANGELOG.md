# Changelog

## 0.7.0 (unreleased)

### Amplicon calling is now realistic (breaking behavior change)

Previous versions paired **any** forward/reverse BLAST hits found on the same
contig, regardless of distance or orientation, which reported "phantom"
amplicons (same-strand pairs, divergent pairs, products megabases long) that
would never form in a real reaction. Version 0.7.0 reports a pair only when:

- the primers sit on **opposite strands in convergent orientation** (3' ends
  facing each other) on the same contig;
- the product length is within **`--min-length`/`--max-length`**
  (new options; default maximum 5000 bp, `-l 0` disables the cap);
- each primer passes the **mismatch budget** (`-m`): BLAST mismatches + indels
  + 3' bases BLAST left unaligned (terminal mismatches are trimmed by local
  alignment and used to slip through unnoticed);
- each primer passes a **weighted 3'-end check** over the last `--3p-window`
  bases (default 5): mismatches score 3 on the terminal base, 2 at positions
  -1/-2, 1 beyond; +1 when a G/C pair is broken within the last 3 bases;
  +1 for indels. The sum must not exceed `--3p-penalty` (default 0 = perfect
  3' end). Hydrolysis probes (-P) are exempt from 3' rules.

### Report format changes

- Replaced the `ForwardEndMismatch`/`ReverseEndMismatch` columns (alignment
  length minus primer length) with `Forward3PrimeMismatches`/
  `Reverse3PrimeMismatches` (imperfections within the 3' window) and a new
  `Strand` column.
- `ForwardMismatches`/`ReverseMismatches` now count mismatches + indels +
  unaligned 3' bases, matching the `-m` budget.
- Samples are written in stable alphabetical order.

### New CLI options and GUI field

- `-l, --max-length <bp>` (default 5000, 0 = unlimited), `--min-length <bp>`
- `--3p-window <n>` (default 5), `--3p-penalty <score>` (default 0)
- GUI gains a "Max amplicon (bp)" field.

### Internals and quality

- The GUI and CLI now share one analysis core (`ca.canada.inspection.pcr`):
  identical hit filtering, amplicon calling, and report writing in both modes.
- First unit test suite (JUnit 5; BLAST hit filtering, 3'-penalty scoring on
  both strands, amplicon orientation/size rules, report writer, degenerate
  primer expansion) with JaCoCo coverage uploaded to Codecov from CI.
- Added `CITATION.cff` and `.zenodo.json`; tagged releases are archived on
  Zenodo with a DOI.
- Removed dead code (unused `Find.java`, legacy duplicated BLAST parser and
  report writer in `Methods`), replaced the in-memory TSV header insertion
  with the streaming version, and rewrote degenerate-primer expansion to a
  single shared implementation.
- README/wiki: corrected the workflow diagram (assemblies BLAST directly;
  BBDuk/Tadpole only process reads; amplicon calling happens after BLAST),
  new banner, new "Amplicon Calling" wiki page, documented report columns.
