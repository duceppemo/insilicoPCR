# Changelog

## Unreleased

### Added

- Gel viewer: **Color: By size check** draws each band by what its size is for
  its assay, as the report's `SizeCheck` column says when the primer file
  declares sizes: green for the target size, amber for another known product,
  red for an unexpected size, blue when the assay declares no size, with a
  legend. The hover text and band details give the size check, and searching
  `unexpected`, `other` or `target` finds those products. It replaces the
  **Color by gene** check box with a choice of black, by gene or by size check.
  Every product is still drawn.

### Fixed

- An output folder whose path holds a space (`C:\Users\Jane Doe\run`, `/data/my
  results`) stopped every run at `makeblastdb`: BLAST+ splits database paths at
  their spaces itself. The BLAST programs now run in the output folder and are
  given the database by its name; the application's "Path MUST NOT contain
  spaces" warning is gone. There, makeblastdb still prints an error from a check
  of its own, followed by a `Note:` that the database is complete; the run goes on.
- The BLAST e-value of a run was recorded nowhere, and each record of a run
  left out some of its settings: `QAlog.txt` its mismatch, 3'/5' and length
  rules, the HTML report its e-value, threads and Tadpole settings. They now
  list the same settings, each with its command-line option, and the run's log
  lists them at its start.
- FASTQ mode: an output folder whose path holds a comma (`results, run 2`)
  stopped the run at Tadpole: BBTools reads a comma in a file name as a list of
  files. The BBTools programs now run in the sample's folder, and a file whose
  path has a comma is given by its path relative to that folder, worked out from
  the folders as they are on disk (an output folder reached through a symbolic
  link, as `/scratch` often is, works too). A comma that is left (in the name of
  a read file, or of a folder holding the reads but not the output) stops the
  run with a message naming the file to rename. A program that fails is named
  with the folder it ran in, to run it again by hand.
- Command line: an input with no FASTA or FASTQ file at all (an empty folder, a
  mistyped `-i`) ended with empty reports and exit code 0, which a workflow
  manager took for success; it stops with exit code 1. Arguments the command
  line does not expect are refused (exit code 2): `-i reads/*.fastq.gz`,
  expanded by the shell, gave `-i` its first file and silently left the others
  out.
- The BLAST hit files of a run (`<sample>.tsv`, `*.blast.txt`) were readable by
  their owner only, unlike the other files; colleagues sharing the output folder
  could not open them.
- An output folder that is the input folder through a symbolic link was not
  refused, and the run analysed its own primer database as a sample.
- An e-value of `Infinity` (or `1e999`) was accepted, and the run failed at
  blastn, after the FASTQ steps. It is refused at once.
- A file or folder name with an accented letter could not be opened under a
  non-UTF-8 locale (`LANG=C`: cron jobs, batch schedulers, containers), with a
  stack trace in one case. The Linux launcher switches to UTF-8 itself, and the
  error is a message saying to.
- An error from a file the system refused (a folder that cannot be written to)
  gave the file without the reason; it says `permission denied`, or whatever
  the reason is.
- Cancelling a run waited for the work under way in a step that handles several
  samples at once (writing a sample's baited reads, for one) to finish; that work
  is stopped now.
- FASTQ mode: single-end reads (one FASTQ file) were given to BBDuk as
  interleaved pairs. A file with an odd number of reads stopped the run
  (`Incomplete interleaved FASTQ pair`), and with an even number each baited
  read brought its unrelated neighbour into the mini-assembly. They are read as
  single reads now.
- Every run on Linux made the bundled BLAST+ executable (`chmod +x`), and
  stopped when that failed: a user who does not own a shared installation
  (`/opt/insilicoPCR`) could not run it. It is now done only when needed, and a
  failure is a warning.
- Primer file: a record without a sequence was left out, so a probe written
  without one turned its qPCR assay into a PCR assay, reported on its primers
  alone; and of two records with one ID, only the last was searched. Both now
  stop the run with a message, as does a primer too degenerate to search (more
  than 65,536 sequences).
- Primer file: a primer named like a sequence of a degenerate primer (`X-F_1`
  beside a degenerate `X-F`, whose sequences are named `X-F_0`, `X-F_1`, ...)
  replaced that sequence without a word, and a file without a `>` line left the
  run with no primer. Both stop the run with a message now.
- Application: a primer file not named like a FASTA file (`primers.txt`) was
  refused, although the command line reads it, and a gzipped one
  (`primers.fasta.gz`) was accepted, then could not be read. Both read now, in
  the application as on the command line.
- The output folder may no longer be the input folder: the primer database the
  run writes there was analysed as a sample.
- Application, gel viewer:
  - **Save PNG** and **Save PDF** saved a cropped or shrunken picture when the gel
    was zoomed; they save the whole gel at its own size.
  - A save that failed (a folder that cannot be written to, a full disk) said
    nothing; it is a message, and a save that works says where it went.
  - The last lane's slanted name was cut at the right edge of saved pictures,
    and names longer than about 85 characters at the bottom.
  - Ctrl+A in the search box selected every band instead of the text.
  - Ctrl+A (select the bands that match the search) left bands selected
    before, which did not match it, highlighted and counted as selected.
  - Searching across gels counted samples by name and lanes without a band,
    unlike the search on the gel shown: they match the same way now.
  - Bands of products longer than 25 kb (`--max-length 0`) were drawn under the
    gel's top border; they sit just below the wells.
- `--gel` image: a gene legend of more than about 33 assays was cut off, and
  beyond 10 assays genes shared colours (and legend entries); every gene has a
  colour of its own and the picture is tall enough for the legend.
- Application, gel viewer: **Save PNG** and **Save PDF** saved what the window
  showed, with the selection outlines and the fading of bands a search does not
  match, while **Save SVG** did not; none had the gene legend of a gel coloured by
  gene. The three now save the gel as `--gel` draws it, legend included, and so
  does `--gel` itself (one drawing of the bands, where the window and the picture
  differed in band strength and in how bands of one size from two assays are
  drawn).
- `--gel` drew every sample on one picture, too large for a run of a few hundred
  samples; it writes a picture per 100 samples (`..._page_001.png`), as the gel
  viewer pages them.
- `--gel` on a run without a product warned that a graphical environment was
  required; it draws the gel with empty lanes, as the gel viewer does. An image
  that cannot be written (a full disk) now says so, instead of blaming the
  graphical environment.
- **Open Report TSV** read a BLAST TSV of `detailed_report` as a report and drew
  a gel of meaningless 0 bp bands; it now says the file is not a report. A
  report saved by Excel as "Text (Tab delimited)" with an accented letter did
  not open; it does.
- A tab in a sample's file name split its lane in two on the gel, and shifted
  the columns of the FASTQ diagnostics TSV.
- Application: a failure during a run stopped from the window was shown as
  "Run cancelled" without its stack trace, even when it was a fault of the
  program; only the stop itself is shown so now.
- Windows: the launcher failed in a folder whose name holds a parenthesis (a
  second download, `... (1)`, or `Program Files (x86)`). The release check now
  runs the launchers users run, into a folder with a space in its path, and
  checks that the program reports the version of its ZIP.
- Application: a run of more than 100 samples opened another gel viewer, without
  band selection, the band menu or selection by dragging, which drew its
  bands with other spacing and strength, so the same report looked different
  at 100 and at 101 samples. There is one viewer now: it shows 100 samples a
  gel, with **Previous gel** / **Next gel** (Page Up / Page Down; on a gel of one
  page they scroll it) for more, and every gel has all the tools; a search that finds nothing on the gel shown
  turns to the first gel with a match.
- The warning that trimmed primer or probe hits lay on sequences missing from the
  searched file (their trimmed bases then go uncompared) went to the console
  only, so the application's window and `log.txt` never showed it, and was
  given for assemblies only; it is now a `Warning:` line of the run for
  assemblies, baited reads and extended reads alike.
- The rows of a degenerate primer's or probe's variants were listed in
  `report.tsv` and the HTML report in an order that could change from one run
  of the same data to the next (also in 0.15.0 and 0.15.1); they now follow the
  primer file.
- A read pair whose mate suffixes differ in case (`s_r1.fastq.gz`,
  `s_R2.fastq.gz`) was left out as two files with the same sample name: read 2
  sorted first. A sample's files are now in name order whatever the case.
- Application: closing the window while a run wrote its reports left a
  `QAlog.txt` naming that stopped run as the folder's last finished one; it now
  writes none, so the folder's log still names the last finished run (reports
  the stopped run had written may remain, unnamed). `QAlog.txt` is written
  whole or not at all: a write that stops partway leaves the previous log.
  Closing the window during the input check is a cancelled run, not a failure
  with a stack trace on the console.
- **Open Previous Run** on a folder whose `QAlog.txt` was written before
  0.15.1 took the newest report in the folder, which could be one a later,
  unfinished run had left; it now takes the newest report no newer than the
  log, or the newest report when none is (a folder copied without its dates).
- Application: closing the window while the read extension ran on the last
  FASTQ sample did not stop the run: the stopped program read as a failed
  one, the read extension went on to the next step, and the stopped run
  wrote its reports and `QAlog.txt`. A stopped program now stops the run, and
  the run checks after each step that it was not stopped, so a stopped run
  writes no report; its `log.txt` always ends with `Run cancelled`.
- Application: after confirming that the window should close during a run,
  the window waited for the run to stop (20 seconds at most) without
  repainting; it now closes at once, and the program ends once the run has
  stopped.
- A build without the HTML report's style or script failed every later run
  with a Java error, not an `Error:` line; it fails each run's HTML report
  with that line.

### Changed

- Gel viewer: the gel is laid out for a fixed size, the one the main window
  opens at, not for the size the main window has when the viewer is opened: a
  maximised main window no longer gives a larger gel, and a page change no
  longer resizes it.
- Docs: two known gaps are described on the Limitations page, to be revisited:
  products of one primer alone (at an inverted repeat) or of two assays'
  primers are not called, and in FASTQ mode a primer site with an indel or 5'
  mismatches may bait no read; neither is named in the output.
- Tests: CI runs the tests under a virtual display, so those that draw the gel
  run there too; the application's form-to-run step (`RunForm`) is tested
  without the window; a test keeps each package importing only from the
  layers below it; and the read-level search's shortcuts that left out the
  check of which locus a read comes from are test fixtures now
  (`TestEvidence`), so the code a run uses always checks it.
- Code: what a sample shows for an assay (target size, other product, seen in
  too few reads, not checkable from reads, ...) is decided in
  `pcr.AssayVerdict`, tested apart from the HTML report, which only draws it.
- Code: assemblies are read without building lines (`util.FastaScanner`, which
  the trimmed-end check already used), also to load contig descriptions and
  lengths, which read a contig written on one line as one string; the ID and
  description of a FASTA header are read by one rule everywhere.
- Code: the columns of the BLAST TSV are one list (`pcr.BlastField`) for the
  `-outfmt` given to blastn, the header and the parser, which read them by
  position; blastn is run from one command line (`BlastTsv.blastn`) for
  contigs and reads; and the files named after a report (its HTML page, its
  FASTQ diagnostics, its gel image) take their name from one rule
  (`ConsolidatedReport.sibling`).
- Code: a report row says what its call was made on (`ReportRow.Source`: the
  assembly, reads with their support, or extended reads with their seeds) as
  data, and the HTML report shows the rows the run made instead of reading
  `report.tsv` back and parsing that from the Contig Description text. The
  row's fields are grouped (where the amplicon lies, how each primer binds,
  the probe, the source).
- Code: one reader of a BLAST TSV into a sample (`pcr.BlastHits`) for contigs,
  baited reads and extended reads, which each had their own copy; the FASTA a
  sample is searched in (`Sample.searchedFasta`) and the hits of a set of
  oligos (`Sample.hitsOf`) are each worked out in one place.
- Code: a sample's products are worked out once and reused by the report, the
  FASTQ steps and the diagnostics, instead of four times for a FASTQ sample.
  `Open Previous Run` finds a pre-0.15.1 run's reports by the rule that names
  them (`ConsolidatedReport.isReportFile`); two drawing helpers nothing used
  are gone.
- Code: the output folder's layout is written in two places
  (`PipelineDirectories`, and `SampleFiles` for the files of a sample), which
  the steps that write a file and those that read it both ask; the BLAST
  report reader is given where a sample's report is.
- Code: the command line sets only the options it is given, so its defaults
  are those of `PipelineConfig.builder` and `AmpliconOptions.builder`, which
  sets the amplicon rules by name; its help reads them from the same constants.
- Code: what counts as positive, how the kinds of verdict group and which
  products decide one are on `pcr.AssayVerdict.Kind`, and which FASTQ
  statuses are called or withheld on `FastqDiagnostics.Status`; the HTML report
  counts by these, with no rule of its own. A test keeps the FASTQ diagnostics
  and the report rows agreeing on which assays were called from reads.
- Code: a sample's accepted hits are a value of their own (`model.Hits`); the
  read searches give their reads' hits as one, instead of a stand-in sample
  whose "contigs" were reads, and a sample's name is fixed when it is made.
  The mismatch count is written once (`BlastResult.primerMismatches`,
  `alignedMismatches`), the run's rows are made once (`Products.ofRun`) and
  `ConsolidatedReport` only writes them.
- Code (FASTQ mode): one reader of FASTQ records (`util.FastqRecords`), one
  owner of the baited reads written as FASTA (`BaitedReads`); every external
  program is started through the run's monitor, so tests stand in for a
  failing one (the read extension's out-of-memory note is tested).
- Code: a primer's role is passed as `PrimerId.Role`, not a letter; the DNA
  complement, the "same product" size rule (`ProductSizes`) and the list of
  sequence-file extensions are each written once; unused code is gone
  (`BlastResult` getters and its sample name, two `HtmlReport.render`
  overloads, test-only overloads and constants).

## 0.15.1 (2026-11-14)

### Fixed

- Command line: a run that failed on a fault of the program itself could be
  reported as a wrong command line (exit code 2 and the usage text) instead of
  a failed run (exit code 1 and the stack trace a bug report needs). Only the
  command line is now checked as such.
- FASTQ mode: a file of the read extension that could not be read or written
  stopped the whole run; like a failed program there, it now leaves the
  sample's assays as the read-level search left them, with a `Warning:` line,
  and the run goes on.

- The gel viewer saved a PNG of the gel in the run's output folder each time it
  opened: it could not open a run in a folder it cannot write to (someone
  else's run, with **Open Previous Run** or **Open Report TSV**), and it
  replaced the `--gel` picture of the same name with one drawn from other
  lanes. It now writes only when asked (**Save PNG...**). The name it offers is
  the one `--gel` writes, also for `report(1).tsv` (`synthetic_gel_report(1).png`;
  the viewer offered `synthetic_gel_report_1_.png`).

- Application: **View Gel Image** and **View HTML Report** showed the newest
  report in the output folder, which another run into the same folder (the
  command line, a second window) could have written; they now show the report
  the window's own run wrote. The gel has a lane for every sample of the run,
  with or without a band, as `--gel` draws it; it had lanes for the samples
  with a band only.
- Application: `log.txt` was written when the window closed, for the last run
  only, so an earlier run of the same window into another folder got none. What
  the window showed of a run is now added to `log.txt` at the end of that run,
  also when it fails or is stopped.
- Closing the application during a run: the run was left to the Java runtime to
  end, possibly in the middle of writing a report, and its worker threads could
  keep the program running after the window had gone. The window now stops the
  run and its programs and waits for it (20 seconds at most) before closing.
  It no longer writes a `QAlog.txt` of its own for a stopped run: the folder
  keeps the log of the last run that finished there.
- `QAlog.txt` names the report the run wrote (`Consolidated report:`) and the
  samples it analysed (`Sample analysed:`), and its input list holds the files
  of those samples instead of everything in the input folder when the log was
  written. **Open Previous Run** reads them back, so it shows that run's
  report, without lanes for files added to the input folder later. Logs
  written before 0.15.1 are read as before.

- An assembly named `<name>_R1.fasta`, `<name>_R2.fasta` or `<name>_assembly.fasta`,
  or with an upper-case extension (`ISO2.FASTA`), was searched but reported
  with no product, whatever it held: its BLAST results were filed under a name
  worked out from the file name with other rules than the sample's, and then
  left out as the results of no sample, with a warning on the terminal only.
  The results are now filed and read under the sample's own name. An assembly
  keeps a `_R1`/`_R2` ending in its sample name (`strain_R1`), as it already
  kept `_1`/`_2`: read pairs are FASTQ files only. Found on renamed copies of
  the five-isolate example genomes, three of which lost all their products
  (also with 0.15.0).
- The input check leaves out, with a warning, files that share a sample name
  without being a read pair (`x.fasta` and `x.fastq`, which were searched as
  one sample, or `x.fasta` and `x.fna`), and sample names that differ only in
  case (`iso2.fasta`, `ISO2.fna`), whose results would share a folder on
  Windows and macOS.

- FASTQ input: a sample whose Tadpole ran out of its share of memory, when
  several samples ran at once, stopped the run with `Tadpole failed with exit
  code 1` instead of being run again alone. Tadpole's k-mer table is sized
  from its heap, and when it is full BBTools stops the program itself, after
  printing `This program ran out of memory.`, without the Java runtime running
  out of memory; only the runtime's exit code 3 was recognised. That line is
  now recognised too: such a sample is set aside and run again alone, and a
  sample that runs out alone gets the `ran out of memory` message and its
  advice. Found on 4 Globodera read sets sharing 48 GB (12 GB each): one
  stopped with 5.8 of its 12 GB in use.

- An assay missing its forward or its reverse primer was left out of every
  report without a word, so it looked negative in every sample. The run now
  stops when it reads the primer file and names the missing primers (`Every
  assay needs a forward (-F) and a reverse (-R) primer, but toxA-R is missing
  ...`). The application checks the same before a run.
- FASTQ mode: the read a call from reads names in the report's Contig column
  (`read_<n>`) could change from one run of the same data to the next, as
  BBDuk and BBMerge wrote the baited reads in a different order each time
  with several threads. They now keep the order they read them in
  (`ordered=t`), so the same data gives the same report.

- A file that could not be read or written at the end of a run (the report
  folder on a full disk, the BLAST reports, the diagnostics, the gel image)
  ended the run with a Java stack trace, as if insilicoPCR itself had failed.
  It is now an `Error:` line naming the file.

- `report.tsv`: a tab or line break in a contig's description (a FASTA header
  holding one) shifted the columns after it; it is written as a space.
- Opening a report TSV in the application: a file saved by a spreadsheet with a
  byte order mark, or without the header line, drew no gel or a wrong one; both
  read now.
- A primer file with an incomplete assay was only rejected after the whole
  input check, which reads every read of every FASTQ file: the primer file is
  now read first.

### Changed

- Code: the window's run task (`PcrPipelineTask`) and the `--gel` image writer
  (`GelImageWriter`) moved out of `pipeline`, so JavaFX is only in `gel` and
  `insilicopcr` (a test keeps it so); methods and overloads nothing used are
  gone. A tool missing from the installation (BLAST+, BBTools, the Java
  runtime) is reported as its message instead of a stack trace.
- Code (FASTQ mode): whether a product called from reads or from extended reads
  is one the sample already shows is decided by one rule
  (`ReadLevelCaller.sameProduct`), where the two callers each had their own;
  the report rows of read-level calls are made next to the read-level caller
  instead of in the diagnostics step, and what a sample already shows is
  worked out once per sample for the read extension, instead of twice.
- The synthetic gel is drawn by one piece of code (`gel.GelScene`) for the
  viewer, the paged viewer of runs of more than 100 samples, the `--gel` image
  and the SVG files, which each drew their own: the paged viewer and the SVG
  files now show bands, the ladder and the gel's texture as the viewer and the
  image do.
- Code: the columns of `report.tsv` are one list, `report.ReportColumn`, from
  which the report's header and rows are written and by whose names it is read
  back; they were written in `ReportRow`, named in `ConsolidatedReport` and read
  in `ReportTsv`, matched by hand.
- Code: the defaults of a run and the checks of its settings are in one place,
  `PipelineConfig.builder`, which the command line and the application both
  start from (the command line kept a copy of every setting, and the
  application its own e-value default).
- The application's dialogs (errors, the exit confirmation) are JavaFX
  dialogs owned by its window, instead of Swing ones.
- The portable releases no longer ship five libraries that nothing used
  (ControlsFX, FormsFX, Ikonli, BootstrapFX, JavaFX FXML): 1.7 MB less.
- FASTQ mode: when the `-t` threads do not divide evenly between the samples
  baited and assembled at once, the ones left over now go to some of those
  samples, one each, instead of sitting idle (`-t 16` with 3 samples: 5 or 6
  threads each, 16 in all, where it was 5 each).
- Code: `InSilicoPcrPipeline.run` now calls every step of a run in order;
  the read-level search and the read extension, which ran inside the report
  step, are steps of their own, and `ReportGenerator` only writes the reports.
  Every step is a class with a static entry point, and three wrapper classes
  are gone. The HTML report and the gel read `report.tsv` with one reader
  (`ReportTsv`) into `ReportRow`s, instead of each with its own.
- Code: the files a run writes and reads back (`report.tsv`, the HTML
  report, the FASTQ diagnostics) are in a `report` package, apart from the
  analysis in `pcr`; finding a sample's products moves from
  `ConsolidatedReport` to `pcr.Products`, which the TSV writer and the
  calls from reads both use. `Sample`'s leftover accessors that handed out
  its own collections (`getFiles`, `getBlastResults`, `getContigDict`) and
  its duplicate methods are gone.
- Code: the primers of a run travel as one `PrimerPanel` (sequences, assays,
  which have a probe, declared sizes), built once, instead of as a map of
  sequences that each step grouped by assay again, with the declared sizes
  passed beside it. `HtmlReport.write` has one form instead of four.
- Code: the primer naming rule (`<assay>-F/-R/-P`) is read in one place
  (`PrimerId`) instead of seven.
- Code: products pass between the steps of a run as `ReportRow` records,
  turned into report columns only when `report.tsv` is written, instead of as
  lists of strings read back by column number. The HTML report's style and
  script are resource files instead of Java strings, and a sample's file type
  is an enum. `report.tsv` and the HTML report are unchanged.
- Code: packages no longer depend on each other in a cycle. `Sample` and
  `BlastResult` moved to a new `model` package, the gel code to `gel`, and
  `AppPaths` to `pipeline`; the `commandpcr` wrapper is gone (the dispatcher
  runs the pipeline). The version is written once, in `pom.xml`, and read at
  run time (`util.Version`), instead of being copied into `Dispatcher.java`.

## 0.15.0 (2026-11-14)

### Added

- Assembly (FASTA) input: an assay with no product whose primers point off the
  ends of two contigs, or off both ends of one, is now warned about when
  joining them would give a product within `--max-length`. A product is called
  on one contig only, so one that spans a break in the assembly, or the start
  of a circular plasmid or chromosome, used to be a silent negative. The
  warning gives the hits and the smallest product (`Warning:` in the log and in
  `QAlog.txt`), and the HTML report marks the cell `◇ Primers at contig ends`
  and lists them in their own section. On 48 SKESA assemblies of Brucella with
  a 4-assay qPCR panel it fired once.

### Changed

- CI: the GitHub Actions used by the build and release workflows are updated
  to their Node 24 versions (checkout v7, setup-java v6, upload-artifact v7,
  download-artifact v8, codecov v7, action-gh-release v3), before GitHub
  removes Node 20.

### Documentation

- New Limitations page, with a figure: insilicoPCR applies sequence rules only
  (no melting temperature, dimers, hairpins, efficiency or multiplex
  competition), and the cases where the report can say less than a real PCR
  would (a fragmented assembly; in FASTQ mode, a long repeat in the product,
  few reads carrying the primers, too few read pairs to confirm, a product in
  few of the reads), each with what the report says and what to do. The README,
  Amplicon Calling and the FAQ say so too.

## 0.14.0 (2026-11-13)

### Changed

- The application now runs the same pipeline as the command line, instead of a
  copy of it. Results are unchanged (the five-isolate example gives the same
  report from both). What the application does differently:
  - FASTQ samples are baited, merged and assembled several at a time, as on the
    command line, instead of one after the other;
  - the primer BLAST database (`primer_tmp.fasta*`) stays in the output folder,
    as on the command line, instead of a `.tmp` folder deleted after the run.
- FASTQ mode: the samples baited, merged and assembled at the same time now
  share the threads and the memory. Each BBTools process used to get all of
  `-t` and half of the heap, so 40 samples on 64 threads started 40 processes
  of 64 threads at once, each allowed half the heap. Now as many samples run
  at once as each can have 4 GB of that half, at most one per thread, and each
  gets its share of the threads and of the half (up to 64 GB). Each step is
  split anew, so BBMerge, on the paired samples only, gets what they leave. A
  single sample still gets everything. The log says how it was split.
- FASTQ mode: a sample whose BBTools program runs out of its share of memory
  is set aside with a warning and run again at the end, alone, with all the
  threads and memory, from the step that ran out. If it runs out alone too,
  the run stops with `<program> ran out of memory on sample <name>` and says
  to raise `-Xmx` (or, at the 64 GB cap, that more does not help), instead of
  `failed with exit code 1`. When the read extension runs out of memory, its
  warning says so. BBTools programs now run with `-XX:+ExitOnOutOfMemoryError`,
  so running out of memory in any of their threads stops them (exit code 3)
  rather than possibly leaving them running on without that thread's work.
- A failed BLAST+ or BBTools command is reported as
  `<program> failed with exit code <n>: <command line>`, from the command line
  and from the application alike.

### Tests

- Whole runs of the five-isolate example (assemblies, reads, read extension,
  faulty input files, a cancelled run) are now part of the unit tests, with the
  BLAST+ and BBTools of the checkout.
- The synthetic gel's layout, colours, text, paging and SVG/PDF/PNG writing
  are now plain classes with unit tests, and the three gel pictures (the
  viewer, the paged viewer and the `--gel` image) no longer keep copies of the
  same code. Every picture is byte-for-byte what it was.
- Line coverage went from 48% to 70% (72% where a display lets the gel be
  drawn), and the build fails if it drops below the floors set in `pom.xml`.

## 0.13.0 (2026-11-13)

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
- Input check: every input file is checked before the run, and a sample with
  a faulty file is left out with a warning while the others are run. Until
  now an empty file was skipped without a word (the sample was simply absent
  from the report), a pair with one empty file was run as single-end reads,
  and a damaged file stopped the whole run inside BBDuk.
  - Faults found in FASTQ files: empty (also an empty gzip stream, what a
    library without reads looks like); not gzip, or a gzip stream damaged or
    cut short; a file that ends inside a read; a read that is not four lines
    of the expected kind (no `@` header, no `+` line, a quality line of
    another length, a character that is not a base or a quality score, a blank
    line between reads); FASTA under a `.fastq` name; and two files of a pair
    with different numbers of reads. Each is reported with the file, the read
    and the line.
  - Faults found in assemblies: empty, header lines only, not starting with
    `>`, and a gzip stream cut short.
  - Reported as `Warning:` lines (terminal, or the output area of the
    application), in `QAlog.txt` (`Input not analysed`, with one
    `Sample not analysed: <name>` line per sample), and at the top of the HTML
    report (a section **Input not analysed** and a summary tile). Such a
    sample is in no table of the report and has no lane in the synthetic gel:
    nothing was run on it, so it is neither positive nor negative. The exit
    code stays 0.
  - Every read is read, which costs one more pass over each FASTQ file, about
    as long as decompressing it (files are checked in parallel).
    `--input-check quick` reads the first read of each file only. The
    application always checks every read, and no longer refuses to start
    because one file of the input folder is faulty.
- `docs/examples/five-isolates`: the small synthetic data set pictured on the
  Reports page (five genomes, two assays with declared sizes) and its report.
- CI runs each portable ZIP it builds, on Linux and on Windows, on that
  example (`scripts/ci/smoke-test.sh`): two genomes gzipped, the report
  compared with the expected one, and a folder with faulty files checked to
  give its warnings and the result of its one good sample. It also runs FASTQ mode on simulated reads of
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

- FASTQ mode, degenerate probes: a product called from reads (or from extended
  reads) is reported on one representative read, and when two variants of a
  degenerate probe both hit that read within `--probe-mismatches`, the variant
  shown was arbitrary: `ProbeMismatches` could read `1` although another
  variant matched exactly, and the choice could change from run to run. The
  representative is now the row with the fewest primer mismatches, then the
  fewest probe mismatches, so the best-matching variant is shown. Which
  products are reported does not change, only the `Probe`, `ProbeLocation`,
  `ProbeSize` and `ProbeMismatches` of such a row, and with them the read it is
  shown on. Between variants of a degenerate primer that bind a read alike, the
  one shown is now always the same.
- A sequence file, not gzipped, with a byte that is not UTF-8 in a header (an
  accented letter written in Latin-1, say): when the byte was near the start of
  the file the sample was skipped without a word, and when it was further on
  the run stopped with `Could not parse contigs` (`Input length = 1`). Such a
  byte is now read as a replacement character, as it already was in a gzipped
  file, and the sample is analysed.
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
