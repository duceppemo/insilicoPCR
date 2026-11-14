# Architecture

insilicoPCR uses a shared pipeline behind both the graphical interface and command-line interface.

![insilicoPCR architecture](https://raw.githubusercontent.com/duceppemo/insilicoPCR/master/docs/assets/architecture.svg)

One dispatcher starts the desktop GUI when it is given no arguments and the command line otherwise. Both run the same pipeline stages, which call the bundled external tools (BBTools and BLAST+), pass the BLAST hits to the shared analysis core, and write the reports from the amplicons it calls. Everything runs from the portable runtime shipped in the release ZIP.

## Entry points

| Area | Entry point |
|---|---|
| Dispatcher | `ca.canada.inspection.dispatchpcr.Dispatcher` |
| GUI | `ca.canada.inspection.insilicopcr.MainRun` |
| CLI | `ca.canada.inspection.commandpcr.CommandMain` |
| Pipeline | `ca.canada.inspection.pipeline.InSilicoPcrPipeline` |
| Analysis core | `ca.canada.inspection.pcr` |

The dispatcher starts the GUI when no command-line arguments are provided. When CLI arguments are present, it runs the command-line pipeline.

The GUI runs the same pipeline: `PcrPipelineTask` runs `InSilicoPcrPipeline` on a background thread and gives it a `RunMonitor` that sends messages, program output and progress to the window, and stops the run when the window closes. The command line gives it `RunMonitor.CONSOLE`. Every external program (BLAST+, BBTools) is started by one class, `pipeline.ExternalProgram`.

## Pipeline stages (`ca.canada.inspection.pipeline`)

`InSilicoPcrPipeline.run` goes through the steps of `RunMonitor.Step` in order, reporting each one to the monitor:

| Step | Classes | What happens |
|---|---|---|
| Dependencies, input check | `RuntimeDependencies`, `SampleRepository` (`util.InputCheck`) | Find the bundled tools; find the samples and check their files |
| Primers | `PrimerService` | Read the primer FASTA, expand degenerate bases, write `primer_tmp.fasta` |
| Reads | `FastqProcessor`, `BbtoolsCommands`, `ChildJavaMemory` | FASTQ samples: two rounds of BBDuk baiting, Tadpole mini-assembly, BBMerge of baited pairs |
| BLAST database, BLAST | `BlastDatabaseBuilder`, `BlastRunner` | `makeblastdb` on the primers; `blastn` of every sample, one thread each, several samples at a time |
| Read search, read extension | `ReadLevelStage`, `ReadExtensionStage` | FASTQ samples: amplicons called on the baited reads; reads extended for products longer than a read |
| Reports | `ReportGenerator` | `report.tsv`, FASTQ diagnostics, HTML report, `QAlog.txt`, `--gel` image |

Samples are processed several at a time by `ParallelStageRunner`, which stops starting new samples once one has failed, or once the run is cancelled. For the BBTools steps, `ChildJavaMemory.share` splits the threads and the memory between the samples running at once: half of insilicoPCR's heap in all, at least 4 GB and at most 64 GB per program. BBTools runs with `-XX:+ExitOnOutOfMemoryError`, so running out of memory is exit code 3 (`ProgramFailedException.exitCode`). `FastqProcessor` sets such a sample aside and, after the others, runs it again alone with everything, from the step that ran out. The user-facing behaviour is on [Threads and memory in FASTQ mode](Command-line-Interface#threads-and-memory-in-fastq-mode).

## Shared analysis core (`ca.canada.inspection.pcr`)

The pipeline delegates the scientific decisions to one JavaFX-free package:

| Class | Responsibility |
|---|---|
| `AmpliconOptions` | Immutable knobs: mismatch budget, 3' window/penalty, 5' truncation allowance, product size limits |
| `BlastHitParser` | Parses BLAST TSV lines and applies the hit acceptance rules (mismatch budget, 3'-end coverage, weighted 3' penalty; probes exempt from 3' rules; trimmed probe ends and primer 3' ends compared with the template by `TrimmedEnds`, the template read alongside the report by `QueryTemplates`) |
| `BlastReports` | Walks per-sample BLAST reports and attaches accepted hits to samples |
| `AmpliconCaller` | Pairs forward/reverse hits: same contig, opposite strands, convergent orientation, size limits |
| `TrimmedEnds` | Compares the oligo bases BLAST trimmed off the ends of an alignment with the template: probe ends and primer 3' ends count only where they do not pair |
| `QueryTemplates` | Reads the contig or read a hit lies on from the FASTA that was searched, one query at a time and at one byte per base (`PackedBases`), for `TrimmedEnds` |
| `ConsolidatedReport` | Writes the consolidated TSV (standard and qPCR modes); also exposes its rows for read-level calling |
| `ReadLevelCaller` | FASTQ mode: calls amplicons on baited reads treated as tiny contigs, one call per product size, with the support rules (≥ 2 fragments, `--read-min-fraction` of the informative ones: reads carrying a primer at the product's locus) |
| `ReadExtensionCaller` | FASTQ mode: for assays in no contig and in no read, picks the primer-carrying reads to extend and finds products on the extended reads (at least 2 reads seeded from each primer), reported when `PairSupport` confirms them |
| `PairSupport` | FASTQ mode: checks a product found on extended reads against the sample's read pairs (every window inside enough fragments; no repeat longer than the window), so that a wrong turn through a repeat is not reported |
| `FastqDiagnostics` | FASTQ mode: explains assays the mini-assembly did not report though their oligos are in the reads |
| `ExpectedSizes` | Product sizes declared in primer FASTA headers (`size=`, `other=`); labels report rows `target` / `other` / `unexpected` and never filters |
| `HtmlReport` | Writes the self-contained HTML report from the consolidated TSV: sample-by-assay grid, per-assay summary, amplicon table |

Three classes outside this package take part in every run as well:

| Class | Responsibility |
|---|---|
| `util.InputCheck` | Finds the samples of the input and checks every file before the run; a sample with a faulty file is left out and reported, the others are run |
| `util.FastqCheck` | Reads a FASTQ file to its end and names the first fault (empty, gzip damaged or cut short, file ending inside a read, malformed read) |
| `pipeline.RunFailure` | Tells a failure the user can act on (reported as `Error:` and `Cause:` lines) from one that points at the program (reported with its stack trace) |

## Synthetic gel (`ca.canada.inspection.insilicopcr.gel`)

The two gel viewers (`InteractiveGelViewer`, `PaginatedGelViewer`) and the `--gel` image (`GelImageWriter`) share plain classes that need no window and have unit tests:

| Class | Responsibility |
|---|---|
| `GelScale` | The gel's log scale (base pairs to position), the ladder, and the small per-band variations |
| `GelLayout` | Geometry of a gel picture: where the gel sits, lane width, picture size, the baseline of the slanted lane labels |
| `LaneBand` | One band of a sample lane: the amplicons it stands for, where it sits, how strong it looks |
| `BandPaint` | Colours of the gel and of the bands |
| `GelText` | Text about the amplicons of a band: tooltips, details, selection and search |
| `GelPages` | Splitting the lanes of a large run into pages of samples |
| `GelSvg` | The viewers' gel as an SVG document |
| `GelImages` | PNG and PDF files of the gel, their default names, snapshots of the viewer |
| `SyntheticGelImage` | The `--gel` PNG, drawn without a window |

`BlastResult` (in `insilicopcr`) carries full alignment context (strand, subject coordinates, aligned sequences) and computes the 3'-end mismatch counts and weighted penalties. The rules themselves are documented on the [Amplicon Calling](Amplicon-Calling) page, and covered by unit tests under `src/test/java`. `EndToEndTest` runs whole runs on the five-isolate example with the BLAST+ and BBTools of the checkout.

## High-level layers

```text
Dispatcher
   ↓
GUI (PcrPipelineTask + window RunMonitor)  /  CLI (RunMonitor.CONSOLE)
   ↓
Shared pipeline (InSilicoPcrPipeline)
   ↓
Input check, primers, FASTQ processing, BLAST database, BLAST, read search and extension
   ↓
External programs: BBTools, BLAST+ (ExternalProgram, ParallelStageRunner)
   ↓
Analysis core (pcr): hit filtering, amplicon calling, read-level calls
   ↓
Reports and synthetic gel (insilicopcr.gel)
```

## Design goals

- Keep GUI and CLI behavior consistent.
- Keep runtime discovery centralized.
- Keep external tool execution isolated from UI code.
- Keep reports and synthetic gel visualization based on structured pipeline output.
- Preserve portable release behavior across Windows and Linux.
