# insilicoPCR Architecture

## Overview

insilicoPCR is composed of four independent layers:

```
GUI / CLI
      │
      ▼
 PCR Pipeline
      │
      ▼
External Tools (BLAST / BBMap)
      │
      ▼
Report Generation
```

Both the graphical interface and the command-line interface call exactly the same pipeline. This ensures that both interfaces produce identical results and that future improvements only need to be implemented once.

---

## Project Layout

```
ca.canada.inspection

├── app
│   Application startup and platform detection
│
├── commandpcr
│   Command-line interface
│
├── ui
│   JavaFX graphical interface
│
├── pipeline
│   PCR workflow implementation
│
├── parser
│   FASTA/FASTQ/BLAST/Primer parsers
│
├── model
│   Data models
│
├── util
│   General utility classes
```

---

## Runtime Resources

The application automatically discovers:

* bundled Java runtime
* JavaFX SDK
* BLAST+
* BBMap

No absolute filesystem paths are used.

---

## Processing Pipeline

1. Load primer definitions
2. Load input assemblies or reads
3. Assemble reads (optional)
4. Run BLAST
5. Parse BLAST results
6. Determine valid amplicons
7. Generate reports
8. Render gel image (GUI)

---

## Design Principles

* Platform independent
* No hardcoded paths
* Shared code between GUI and CLI
* Immutable data objects where possible
* Small, focused classes
* Separation between parsing, analysis and presentation
