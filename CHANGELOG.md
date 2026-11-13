# Changelog

## Unreleased

### Added

- FASTQ mode: products longer than a read or merged pair are now found
  (**read extension**). Until now such a product could only come from the
  mini-assembly, which breaks inside high-copy, variable regions such as rDNA,
  and the assay stayed negative (`NOT_IN_READS`, "not checkable from reads" in
  the HTML report). For an assay that neither the mini-assembly nor a read
  reports although reads carry both of its primers, up to 150 reads per primer
  are now extended through all the baited reads with Tadpole (`mode=extend`,
  `k=31`), always along the better-supported branch, by up to the maximum
  amplicon length each way. The extended reads are searched like contigs, with
  the same hit filters, amplicon rules and probe requirement. A product must
  be held by at least 2 reads extended from the forward primer and at least 2
  extended from the reverse primer (sizes within 10 bp are one product), and
  is then checked against the sample's read pairs: where two places share a
  repeat, the extension takes the same wrong turn from both sides, but no read
  pair spans a junction the sample does not have. Every window of the product
  (the largest of 100, 150, ... bases that the depth still fills) must lie
  inside read pairs, and a product through a repeat longer than that window,
  where a real product and a wrong turn look the same, is withheld. On a
  benchmark of simulated reads with repeat traps the unchecked extension
  reported 1 to 4 false products per run; checked, none, at the price of the
  real products through repeats longer than about 300 bp. On the *Globodera*
  runs behind 0.11.0 this reports the universal 28S control (1074 bp, 2 x 300 bp
  reads) in the five *Globodera* reference samples, held by 70% to 97% of the
  extended reads of each side (over 90% in three of them), and nothing in the
  samples and assays where a primer is missing. Every row that 0.12.1 reports
  for these samples is reported unchanged.
  - Rows are marked `FASTQ read-extension call` in `Contig Description`, with
    the number of extended reads holding the product per side and the window
    the read pairs verified. `Contig` is the extended read, kept in
    `detailed_report/<sample>/<sample>_extendedReads.fasta`.
    **An extended read is the best-supported path through a locus that can
    exist in many slightly different copies; it is not an assembled
    consensus.** Its size is the result; do not use its sequence as the
    sample's sequence.
  - `report_fastq_diagnostics.tsv` has four new statuses:
    `CALLED_BY_EXTENSION`; `NOT_CLOSED_BY_EXTENSION` (both primers in the
    reads, but neither a read nor the extended reads hold a complete
    amplicon); `EXTENSION_NOT_SUPPORTED_BY_PAIRS` and
    `EXTENSION_THROUGH_UNRESOLVED_REPEAT` (extended reads hold a product that
    the read pairs do not confirm; not reported, and warned about). The
    counts, the weakest window or the repeat are in `ReadDetail`.
    `NOT_IN_READS` remains for runs without the extension.
  - The HTML report shows such a product as called from reads, and no longer
    marks a long declared target "not checkable from reads" once the reads
    were extended: it is found, it is "not confirmed by read pairs", or it is
    a negative with the reason.
  - `--read-extension <unreported|target|off>` (GUI: **FASTQ settings…**)
    chooses the assays. `unreported`, the default: assays that nothing
    reports in the sample. `target`: those, and assays that declare `size=`
    and show no product of that size yet, whatever else they show; a product
    the sample already shows is not reported twice. `off`: none. The value is
    recorded in `QAlog.txt` and in the HTML run details. The extension adds
    from a few seconds to a few minutes per sample that has such an assay,
    and nothing otherwise.
- `docs/examples/five-isolates`: the small synthetic data set pictured on the
  Reports page (five genomes, two assays with declared sizes) and its report.
- CI runs each portable ZIP it builds, on Linux and on Windows, on that
  example (`scripts/ci/smoke-test.sh`): two genomes gzipped, the report
  compared with the expected one, and a search `blastn` rejects checked to end
  the run with an `Error:` line. It also runs FASTQ mode on simulated reads of
  one genome (`docs/examples/five-isolates/reads`, written by `make-reads.py`),
  so baiting, merging and the mini-assembly are run on Windows before a
  release.

### Changed

- **FASTQ-mode results can gain rows versus 0.12.1**: products of assays that
  0.12.1 left at `NOT_IN_READS`. Rows that 0.12.1 reports are unchanged, and a
  product in too few reads to be reported (`WEAK_READ_SUPPORT`) stays
  unreported: the extension does not overrule the read-level rules.
- Application: a failed run is reported in the output area as `Run failed` and
  an `Error:` line (with `Cause:` lines when they add something), like the
  command line, when the failure is one the user can act on: an external tool
  that failed, a file or folder that cannot be read or written, a mistake in
  the primer file. It showed a Java stack trace for every failure. A failure
  that points at the program keeps its stack trace, and the terminal the
  application was started from gets the stack trace in every case.


### Fixed

- Memory on very large contigs. Since 0.12.0 the contig a hit lies on is read
  to compare the hit's trimmed ends with it, and it was built as text and then
  copied: about three bytes per base for a moment. A single contig of 1 Gb
  needed more than a 2 GB Java heap (the default on a machine with 8 GB) and
  the run ended with `OutOfMemoryError`; 0.11.0 ran it. The contig is now held
  at one byte per base and read without building its lines, so a contig written
  on one line costs no more than a wrapped one. Results are unchanged.

## 0.12.1 (2026-11-13)

### Fixed

- Gzipped assemblies (`.fasta.gz`, `.fna.gz`, `.ffn.gz`, `.fsa.gz`, `.fa.gz`)
  were accepted as input and then given to `blastn` as they were. `blastn`
  does not read gzip, so the run stopped with `BLAST query error`, on the
  command line. The application failed as well, with `Unable to read sequence
  file`: it also read the compressed file as text when listing its contigs. A
  gzipped assembly is now decompressed to a temporary file in the sample's
  `detailed_report` folder for the time of the search, and its contigs are read
  through gzip. Results are the same as for the plain file.
- Application: a `blastn` search that failed went unnoticed. The run carried
  on to the end and the sample was shown without any product, as if it had been
  searched and found negative. The run now stops with `Run failed` and the
  message `BLAST failed with exit code ... for query ...`, as a failing BBTools
  step already did. The command line always stopped on a failed search.
- Command line: a failing external tool (BLAST+, BBTools) ended the run with a
  Java stack trace. It now ends with the tool's own message followed by an
  `Error:` line naming the stage and a `Cause:` line with the full command line
  and exit code, like the input mistakes since 0.12.0. The exit code stays 1.

### Changed

- The `-m` help text and the tooltip of the mismatch field say "3' bases that
  do not pair with the template count" where they said "unaligned 3' bases
  count", which described the counting before 0.12.0.

## 0.12.0 (2026-11-12)

### Added

- The report says how much of each primer is bound. `report.tsv` gains two
  columns, `Forward5PrimeUnaligned` and `Reverse5PrimeUnaligned`: the bases at
  the 5' end of each primer that do not pair with the template (of the bases
  BLAST left out of its alignment, the ones that differ from the template, so a
  single SNP at the fourth base counts 1, not 4). The bases BLAST left out are
  allowed up to `--5p-truncation` and were not shown anywhere: the mismatch
  columns do not count them, so a 17-base primer held by its last 9 bases
  (`--5p-truncation 8`) read as a perfect match, `0` mismatches. The columns
  come after the probe columns; `SizeCheck` stays last. Read columns by name.
- HTML report: the amplicon table has a `5′ unpaired F/R` column, with the
  paired length of each primer as a tooltip (`forward primer 9 of 17 bases`).
  A product whose primer binds only partly carries a `◐` mark there, and a
  grid cell carries it when every product that decides its outcome is bound
  that way, so a "target size" resting on primers that may not anneal is
  visible at a glance. Nothing is hidden or reclassified by it.

### Fixed

- Probes: a single mismatch near either end of a probe was miscounted. BLAST
  (`blastn-short`, match +1, mismatch -3) trims a mismatch followed by three or
  fewer matching bases off the end of its local alignment. A mismatch at the
  first or last two probe bases was therefore left out of the alignment and the
  hit reported `0` probe mismatches; one at the third or fourth base from
  either end left 3-4 bases unaligned, more than the 2 a probe may have, so the
  hit was rejected whatever `--probe-mismatches` allowed (no amplicon at all
  for a qPCR assay with its probe, with one mismatch). The bases BLAST trimmed
  are now read from the sample sequence (contig, assembly or read) and
  compared, and the ones that differ count as probe mismatches; `ProbeLocation`
  and `ProbeMismatches` describe the whole probe. Only bases beyond the end of
  the sequence stay unaligned (at most 2, as before).
- Primers: one mismatch near the 3' end was counted once per base BLAST
  trimmed, not once. A mismatch at distance 1-3 from the 3' terminus is cut
  off with the 1-3 matching bases after it, and every trimmed base counted as a
  mismatch, in the `-m` budget and in the 3' penalty alike: one mismatch two
  bases from the 3' end weighed 3 mismatches and a penalty of 7-10 instead of 1
  and 2-3, so `-m 3 --3p-penalty 2` rejected a primer it should accept. The
  trimmed 3' bases are now compared with the sample sequence and only those
  that do not pair count; a 3' base beyond the end of a contig or read still
  counts as a mismatch. With `--3p-penalty 0` (the default) a mismatch inside
  the 3' window still rejects the primer, as before. Runs with `-m 2` or more
  can gain amplicons all the same: two mismatches close together just outside
  the window (5 to 7 bases from the 3' terminus) make BLAST trim past them and
  the matching bases after them, every trimmed base counted, and the primer
  was rejected; they now count as the two mismatches they are. The 5' rule is
  unchanged: `--5p-truncation` still counts the bases BLAST left out, paired
  or not.
- The sequences that trimmed hit ends are compared with are read alongside the
  BLAST report, one contig or read at a time (BLAST reports the hits of each
  query together, in file order), not loaded up front, so a primer that hits a
  large assembly millions of times does not load the assembly into memory.
- Command line: a mistake in the input is reported as its message instead of a
  Java stack trace. A primer file that declares two different sizes for one
  assay, a size that cannot be read, a primer ID without `-F`/`-R`/`-P` or a
  sequence with characters that are not bases now ends the run with one
  `Error:` line (and a `Cause:` line when the underlying error says more),
  exit code 1 as before. A failure inside the program keeps its stack trace.

## 0.11.0 (2026-11-10)

Four FASTQ-mode problems found on a 16-assay, 46-oligo *Globodera* panel run over
2 x 300 bp reads with permissive settings (`-m 1 --5p-truncation 8`). Replayed on
the 48 *Brucella* read sets behind #7 (default settings), no call changes.

### Fixed

- FASTQ mode: the read-level search could take hours after BLAST had finished.
  For every amplicon found on a read, the report code copied the table of all
  baited reads, and it compared every forward hit of the sample with every
  reverse hit. Hits are now looked up per read (or contig). On a sample with
  940,000 baited reads and 27 million BLAST hits this step went from 2 h 05 min
  to 19 s, with identical output. Assembly input with very many contigs gains
  from the same change.
- FASTQ mode: reads carrying a primer somewhere else in the genome no longer
  count against a read-level call. The share of informative reads was taken over
  every read holding one of the assay's primers, wherever it matched; a primer
  that also lands at many unrelated places (hundreds, under permissive settings)
  drowned a real product: a *G. pallida*-specific assay had 16 supporting reads
  out of 617 "informative" ones (3%) in a *G. pallida* reference sample. A read
  is now informative only when the bases following the primer resemble those of
  a read holding the product (up to 30 bases, at most 20% different), so the
  reads of a related organism at the same locus still count. The same assay is
  now at 16 of 63 (25%) and 13 of 42 (31%) in two reference samples; what is
  left is real variation at the reverse primer and probe sites, and is reported
  as such. The diagnostics give the reads set aside
  (`N more read(s) carry a primer elsewhere in the genome`).
- FASTQ mode: an assay with amplicons of several sizes in the reads was called
  as one, on pooled support, with the size of whichever read matched best. The
  smallest size also set which reads were long enough to count, so a product
  was diluted by reads too short to hold it (a 266 bp product at 22% because of
  three reads holding a 119 bp one; it is now called at 86%). Each product
  (sizes within 10 bp of each other) is now called on its own support and gets
  its own row.
- FASTQ mode: a product called from reads is added even when the mini-assembly
  reports the same assay at another size; before, any assembly row for the
  assay, including one where both primers land elsewhere, hid it. The
  diagnostics list it with `Assembly` = `OTHER_PRODUCT_ONLY`.
- Amplicon size on a contig that starts inside a primer was too small by the
  primer bases lying before the contig (a 72 bp product on a 61 bp contig was
  reported as 66 bp). The size now counts both primers in full, as it already
  did at the other contig end, and `--min-length`/`--max-length` apply to that
  size. `GenomeLocation` is unchanged. Affects FASTA input too, for contigs
  ending inside a primer site.
- A primer FASTA header with text after the ID (`>assay-F forward primer`)
  made the assay silently negative: the whole line was used as the primer ID,
  which no BLAST hit matched. The ID now ends at the first blank and the rest
  is a description. The GUI reads the primer file with the same code as the
  command line.

### Added

- HTML report: every run writes `consolidated_report/report.html` next to the
  TSV, one self-contained file (no network needed, light and dark themes,
  printable). A sample-by-assay grid shows which samples are positive for
  which assay, with the product size in each cell and samples without a
  product included; then a summary per assay with the names of the positive
  samples, every amplicon of the TSV with a filter, the read-level evidence
  for FASTQ input, and the run settings. When the primer file declares
  product sizes, only a product of the target size counts as positive; a
  product of another size is marked as such and never hidden. For FASTQ
  input, a negative for an assay whose declared product is longer than the
  reads could hold is marked "not checkable from reads" instead of negative.
  Command line and GUI; in the GUI, **View HTML Report** opens the page of
  the last run in the web browser.
- Primer file: an assay can declare the product sizes it is known to give, on
  the header of any of its primers: `>assay-F size=434 other=575,960-980`
  (`size=` the product it is designed for, `other=` other known products; a
  single size matches give or take 10 bp, a range exactly). The report then
  ends with a `SizeCheck` column: `target`, `other`, `unexpected`, or empty for
  an assay that declares nothing. **It labels rows and removes none**: every
  product is reported whatever its size, as before. The column is absent when
  no assay declares a size, and the ranges used are recorded in `QAlog.txt`.
  Command line and GUI.

### Changed

- `report_fastq_diagnostics.tsv`: `ReadDetail` lists each product seen in reads,
  strongest first (`…; also complete amplicon in 3 of 3924 informative read(s)
  (0%), size(s) 119 bp, not reported`). The first product keeps the 0.10
  wording.
- **FASTQ-mode results can change versus 0.10.1**: products that the old
  counting left at `WEAK_READ_SUPPORT` can now be called, including products of
  unexpected size where both primers of a probe-less assay land elsewhere under
  permissive settings. Check `AmpliconSize`.
- Docs: Reports explains which reads are informative, how several products of
  one assay are handled, and the size limit of read-level calls (an amplicon
  must fit in a read or merged pair).

## 0.10.1 (2026-11-07)

### Fixed

- FASTQ mode: a single-end sample rerun into an output folder that held an
  earlier paired run of the same sample name could be reported positive from
  the earlier run's merged reads. The reads searched are now chosen from the
  sample's input files, not from what is on disk.
- FASTQ mode: read-level support is counted per DNA fragment, so the two
  unmerged reads of one pair can no longer meet the 2-read minimum alone. On
  ERR2938678 (#7) BA's support becomes 17 of 25 informative reads (68%,
  was 17 of 28); calls on the #7 read sets are unchanged.
- FASTQ mode: the diagnostics' reads-per-primer counts no longer count a read
  twice when it matches two expansions of a degenerate primer.
- GUI: **Open Previous Run** read the FASTQ-mode lines of `QAlog.txt` as input
  files (fake gel lanes; on Windows the run would not open). Both QA log
  writers now end the input list with a blank line, and the reader stops
  there or at the first `FASTQ mode` line of logs written by 0.10.0.
- GUI: `QAlog.txt` is rewritten on every run, like on the command line,
  instead of collecting every later run's FASTQ lines under the first run's
  header.
- GUI: the read-level BLAST search logs to the window and is stopped when the
  window is closed during a run, like the other external tools; a process
  that starts while the window is closing is killed too, so it can no longer
  keep the application alive.
- GUI: a path typed or pasted into the input, primer or output field is
  used (a leading `~` is the home folder; relative paths are made absolute);
  before, only a chosen or dropped path was, even after the text was edited,
  and the output field did not accept typing at all.
- GUI: closing the window writes `QAlog.txt` and `log.txt` to the folder of
  the last run started, and **View Gel Image** opens that run's report,
  rather than whatever the output field holds by then. Closing without
  having run anything no longer writes logs.
- GUI: **Open Previous Run** showed extra empty gel lanes: one per read file
  for paired FASTQ input (e.g. `S_1` and `S_2` next to `S`), and one per
  non-sequence file or subfolder entry of a folder input. It now keeps only
  sequence files, as the pipeline does, and pairs mates using the run's own
  file list, so the lanes stay right after the inputs are moved.
- GUI: **Run** is disabled while a run is in progress, so two runs can no
  longer write to the same output folder at once.

### Added

- FASTQ mode: `--read-min-fraction <0-1>` sets the share of informative reads
  that must hold a complete amplicon for a read-level call (default 0.3, as
  in 0.10.0). The value used is recorded in `QAlog.txt`.
- GUI: **Amplicon settings…** holds probe mismatches, the 3'-end window and
  penalty, the 5'-truncation allowance and the min/max amplicon length (max
  moved off the main window, which also fixes its label overlapping the
  field); **FASTQ settings…** holds the Tadpole settings and the read-level
  call threshold. A ` *` marks a button whose settings differ from the
  defaults. Mismatches above 3, running on every core and the `--gel` PNG
  export remain command-line only.
- GUI: the "Input is a directory" radio button is gone. Input type was
  already detected automatically; **Select ▾** now offers **File…** or
  **Folder…**. The primer and output rows moved up to make room.

### Changed

- FASTQ mode: the read-level BLAST search splits the thread budget between
  samples instead of using one thread per sample.
- Docs: the e-value help, README, GUI tooltip and a new "BLAST e-value"
  section of Amplicon Calling explain why the 1e5 default is deliberate.

## 0.10.0 (2026-11-06)

### FASTQ mode: targets the mini-assembly misses are called from reads (#7)

An assay could be reported negative in FASTQ mode although its oligos were in
the baited reads, because the Tadpole mini-assembly omitted the target region
or broke inside the amplicon. Nothing in the reports told this apart from
"target absent". On the two read sets in #7 (SRR642185, ERR2938678), FASTQ
mode now reports the same assays as a SKESA assembly of the same reads.

- The first-pass baited reads are searched directly: pairs are merged with
  BBMerge, and each read or merged pair goes through the same hit filters and
  amplicon/probe rules as a contig, counting only reads that hold the whole
  product. An assay the assembly missed is reported from reads when at least 2
  reads, and at least 30% of the reads that could have shown it, hold a
  complete amplicon. Such rows are marked `FASTQ read-level call` in
  `report.tsv`, with their support.
- Every assay the assembly missed whose oligos are in the reads is listed in
  `consolidated_report/report_fastq_diagnostics.tsv`: `CALLED_FROM_READS`,
  `WEAK_READ_SUPPORT` (amplicon in too few reads: contamination or index
  hopping? not reported, warned about), `NOT_IN_READS` (with the longest
  amplicon the reads could have held) or `PARTIAL_BAIT`, plus why the assembly
  missed it (`NOT_ASSEMBLED`, `SPLIT_ACROSS_CONTIGS` with each primer's contig
  and distance to the contig end, `NO_VALID_PAIR`). Summarized in `QAlog.txt`
  and on the console/GUI log; per-oligo read counts are exact.
- The bundled BBTools now includes `networks/bbmerge.bbnet.gz`: BBMerge 40.04
  loads it at startup and crashed without it (the 40.04 update synced only
  `config/` and `resources/`). The release layout checks require it.
- Tadpole defaults are now `mincountseed=2 mincontig=60` (Tadpole's own are
  3 and 500; a contig over a short amplicon is often under 500 bp), and
  `--tadpole-k`, `--tadpole-min-seed`, `--tadpole-min-cov` and
  `--tadpole-min-contig` expose the settings on the command line. The
  settings used are recorded in `QAlog.txt`. **FASTQ-mode results can change
  versus 0.9.0**: more of the baited region is now assembled.
- The docs now state that a FASTQ-mode negative is weaker evidence than an
  assembly-mode negative.

### GUI FASTQ mode now baits and assembles exactly like the command line

The GUI ran its own copy of the BBDuk/Tadpole commands, which had drifted
from the command-line pipeline:

- The first baiting pass pointed at `<output>/primer_tmp.fasta`, a file only
  the command-line pipeline writes; the GUI writes its primers to
  `<output>/.tmp/primer_tmp.fasta`. GUI FASTQ runs therefore failed in BBDuk,
  or silently baited with a previous command-line run's primers when one had
  used the same output folder. The GUI now baits with the primers of the run.
- The second baiting pass still used `-m` as `hdist` (the 0.9.0 out-of-memory
  fix only reached the command line); it now runs with `hdist=0`.
- Children were started with a fixed `-Xmx7g` instead of the heap-scaled
  value the command line uses, and without `overwrite=t`.

Both pipelines now build these commands from one shared class
(`BbtoolsCommands`), so they cannot diverge again.

## 0.9.0 (2026-11-03)

### Bundled BBTools updated to 40.04 (fixes silent truncation of bcl-convert FASTQs)

BBTools 39.94's multithreaded reader stopped silently at a gzip member
boundary partway into multi-member (concatenated) `.fastq.gz` files — the
default output of Illumina bcl-convert/DRAGEN — whenever BBDuk ran with more
than one thread. FASTQ-mode runs failed with the misleading
`truncated or corrupt input` / `There appear to be different numbers of reads
in the paired input files` errors on intact inputs (#5). The bundled BBTools
is now 40.04, where upstream fixed the threaded reader; multi-member FASTQs
are read completely with no repacking needed.

### FASTQ mode: second baiting pass no longer applies the mismatch budget as hdist

The read-recruitment (second BBDuk) pass used `-m` as `hdist` while loading
every first-pass matching read as a k-mer reference. The mutant k-mer
expansion (~3,200x per k-mer at `hdist=2`, ~26,000x at 3) made the table
exceed any configurable child heap on large samples, aborting the run with
an `OutOfMemoryError`. Recruiting reads that overlap already-matched reads
only needs exact k-mers, so this pass now runs with `hdist=0`; memory use is
bounded and `-m` still applies to primer baiting and amplicon calling.

## 0.8.0

> Note: this release was developed as "0.7.0" but a v0.7.0 tag and GitHub
> release (2026-07-09) already existed from earlier work, so it ships as
> 0.8.0. All "0.8.0" behavior notes below refer to this release.

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
would never form in a real reaction. Version 0.8.0 reports a pair only when:

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

### Tests, coverage, and community files

- Test suite grown from 0 to 105 unit tests with edge cases across the
  analysis core, gel data model (lane ordering, size rounding, palette),
  sample/file utilities (gzip detection, pair grouping heuristics),
  BLAST report scanning, CLI option parsing and validation, and input
  validation. Headless (non-GUI) line coverage is 45%; the amplicon
  core classes sit at 86-100%.
- Added CONTRIBUTING.md, CODE_OF_CONDUCT.md (Contributor Covenant 2.1),
  and SECURITY.md (private vulnerability reporting via GitHub).

### Second review pass and probe model

- Hydrolysis probes get their own mismatch budget (`--probe-mismatches`,
  default: same as `-m`): probes are longer and bind hotter than primers,
  so screens can legitimately allow them one extra mismatch. Probe
  coverage is now a fixed rule (at most 2 unaligned terminal bases) and
  no longer silently follows the primer `--5p-truncation` setting.
- Bare `_1`/`_2` FASTQ pairing now requires the mate file to exist and
  backs off when a `_3` sibling suggests sequential sample numbering, so
  distinct single-end samples are never merged into a fake pair.
- An all-negative run raises "No amplicons were found" again in the GUI
  gel viewer instead of rendering a blank gel; in the exported PNG,
  same-size bands from different assays are drawn as separate offset
  bands so no gene is hidden; the band intensity model is shared between
  the viewer and the export; `--gel` failure handling no longer swallows
  JVM errors and reports a truthful message if export is attempted twice
  in one process.

### Review-pass fixes

- Amplicon coordinates now include primer bases BLAST left unaligned at the
  5' end, so `GenomeLocation`/`AmpliconSize` always reflect the full primer
  footprint (previously up to 2 bp short per primer).
- Primer IDs are validated when the primer file is loaded: an ID that does
  not follow `<assay>-F/-R/-P` fails fast with a clear message instead of
  either aborting after the BLAST stage or being silently unreportable.
- The GUI validates the E-value (and max amplicon length) before switching
  into the running state, so a typo can no longer freeze the window, and the
  Mismatches selector gained a tooltip explaining the 0.8.0 semantics.
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
