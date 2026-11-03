# Architecture

insilicoPCR uses a shared pipeline behind both the graphical interface and command-line interface.

## Entry points

| Area | Entry point |
|---|---|
| Dispatcher | `ca.canada.inspection.dispatchpcr.Dispatcher` |
| GUI | `ca.canada.inspection.insilicopcr.MainRun` |
| CLI | `ca.canada.inspection.commandpcr.CommandMain` |
| Pipeline | `ca.canada.inspection.pipeline.InSilicoPcrPipeline` |
| Analysis core | `ca.canada.inspection.pcr` |

The dispatcher starts the GUI when no command-line arguments are provided. When CLI arguments are present, it runs the command-line pipeline.

## Shared analysis core (`ca.canada.inspection.pcr`)

Both the GUI pipeline (`PcrPipelineTask`) and the CLI pipeline (`InSilicoPcrPipeline`) delegate the scientific decisions to one shared, JavaFX-free package, so the two interfaces cannot drift apart:

| Class | Responsibility |
|---|---|
| `AmpliconOptions` | Immutable knobs: mismatch budget, 3' window/penalty, 5' truncation allowance, product size limits |
| `BlastHitParser` | Parses BLAST TSV lines and applies the hit acceptance rules (mismatch budget, 3'-end coverage, weighted 3' penalty; probes exempt from 3' rules) |
| `BlastReports` | Walks per-sample BLAST reports and attaches accepted hits to samples |
| `AmpliconCaller` | Pairs forward/reverse hits: same contig, opposite strands, convergent orientation, size limits |
| `ConsolidatedReport` | Writes the consolidated TSV (standard and qPCR modes) |

`BlastResult` (in `insilicopcr`) carries full alignment context (strand, subject coordinates, aligned sequences) and computes the 3'-end mismatch counts and weighted penalties. The rules themselves are documented on the [Amplicon Calling](Amplicon-Calling) page, and covered by unit tests under `src/test/java`.

## High-level layers

```text
GUI / CLI
   ↓
Dispatcher
   ↓
Shared pipeline
   ↓
Input services, primer service, FASTQ processing, BLAST database building
   ↓
BBMap / BLAST+ runners
   ↓
Parsers and domain model
   ↓
Reports and synthetic gel visualization
```

## Design goals

- Keep GUI and CLI behavior consistent.
- Keep runtime discovery centralized.
- Keep external tool execution isolated from UI code.
- Keep reports and synthetic gel visualization based on structured pipeline output.
- Preserve portable release behavior across Windows and Linux.
