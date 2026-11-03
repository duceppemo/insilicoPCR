# Changelog

## 0.7.0 (unreleased)

### Mixed PCR + qPCR runs

Standard PCR assays and qPCR/probe assays can now share one primer FASTA
file and one run. The probe requirement is decided per assay from the
primer file: an assay that defines a `-P` probe is reported only when the
probe hits inside the amplicon (a defined-but-missing probe keeps the assay
negative), while probe-less assays are reported on their primer pair alone.
When any assay defines a probe, the report carries the probe columns, left
empty on probe-less rows. Previously, a single `-P` primer switched the
whole report to qPCR mode and silently dropped all probe-less assays.

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

### Tutorial, gel export, and read-pair fixes

- New wiki tutorial using downloaded public data: mixed conventional +
  TaqMan assays (hly, *Listeria monocytogenes*) against the EGD-e
  reference assembly and real Illumina reads, with *L. innocua* assembly
  and reads as negative controls.
- New `-g/--gel` CLI option renders the synthetic gel PNG without the GUI;
  lanes are kept for samples with no amplicons, so negative controls show
  as empty lanes like on a real gel. The exported image is cropped to its
  content and, like the interactive viewer, colors bands by gene and
  includes a gene legend (the palette is now shared between both).
- ENA/SRA-style `_1`/`_2` paired FASTQ files are now grouped into one
  paired-end sample (previously only `_R1`/`_R2` were recognized, so each
  mate was treated as its own single-end sample). The bare-digit form is
  only applied to FASTQ, never FASTA.
- The 5'-end truncation allowance is now configurable (`--5p-truncation`)
  and defaults to 5 (was a fixed 2): BLAST trims the whole 5' tail at a
  single 5'-proximal SNP, which used to cause false-negative primer hits
  for strain variants that real PCR amplifies without trouble.

### Review-pass fixes

- Amplicon coordinates now include primer bases BLAST left unaligned at the
  5' end, so `GenomeLocation`/`AmpliconSize` always reflect the full primer
  footprint (previously up to 2 bp short per primer).
- Primer IDs are validated when the primer file is loaded: an ID that does
  not follow `<assay>-F/-R/-P` fails fast with a clear message instead of
  either aborting after the BLAST stage or being silently unreportable.
- The GUI validates the E-value (and max amplicon length) before switching
  into the running state, so a typo can no longer freeze the window, and the
  Mismatches selector gained a tooltip explaining the 0.7.0 semantics.
- BLAST reports with no matching sample in the run now print a warning
  instead of being silently ignored; CI fails if the coverage report is
  missing.

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
