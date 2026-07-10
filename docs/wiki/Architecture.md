# Architecture

insilicoPCR uses a shared pipeline behind both the graphical interface and command-line interface.

## Entry points

| Area | Entry point |
|---|---|
| Dispatcher | `ca.canada.inspection.dispatchpcr.Dispatcher` |
| GUI | `ca.canada.inspection.insilicopcr.MainRun` |
| CLI | `ca.canada.inspection.commandpcr.CommandMain` |
| Pipeline | `ca.canada.inspection.pipeline.InSilicoPcrPipeline` |

The dispatcher starts the GUI when no command-line arguments are provided. When CLI arguments are present, it runs the command-line pipeline.

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
