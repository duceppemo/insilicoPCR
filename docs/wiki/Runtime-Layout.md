# Runtime Layout

insilicoPCR uses a deterministic portable runtime layout.

```text
runtime/
├── common/
│   └── bbmap/
├── linux/
│   └── blast/
│       └── bin/
└── windows/
    └── blast/
        └── bin/
```

The bundled JDK is not committed. Release packaging copies `JAVA_HOME` into the staged portable release as:

```text
runtime/linux/jdk/
runtime/windows/jdk/
```

## Why BBMap and BLAST+ are staged

BBMap and BLAST+ are application runtime assets.

Keeping known-good runtime assets in the repository makes release packaging reproducible and avoids relying on external mirrors during CI.

## Validation

Run:

```bash
scripts/dev/verify-runtime-layout.sh
```

The release scripts fail if required runtime assets are missing.
