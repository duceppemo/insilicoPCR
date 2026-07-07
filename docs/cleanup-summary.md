# Professional cleanup summary

This cleanup removes the fragile parts of the portable release system and makes the runtime structure deterministic.

## What changed

- GitHub Actions no longer downloads BBMap or BLAST during release builds.
- Runtime bioinformatics tools are treated as curated application assets under `runtime/`.
- Checkout enables Git LFS so large runtime assets can be handled safely when needed.
- Linux and Windows packaging scripts validate required runtime tools before building ZIPs.
- Packaging now fails fast instead of silently omitting BBMap or BLAST.
- Release staging uses `build/` consistently for both Linux and Windows.
- Runtime discovery in `AppPaths` was simplified to the supported deterministic layout.
- Legacy recursive filesystem scanning was removed from runtime discovery.
- Documentation was added for the runtime layout and release process.
- Obsolete CI download scripts now fail with clear messages if invoked accidentally.
- Maven plugin versions were refreshed.
- A shared runtime verification script was added and wired into CI.

## Supported runtime layout

```text
runtime/
  common/
    bbmap/
  linux/
    blast/
      bin/
  windows/
    blast/
      bin/
```

Generated release ZIPs add `runtime/<platform>/jdk`.

The JDK is copied from `JAVA_HOME` at packaging time and is not committed.

## Remaining required action

The repository branch expects the actual BBMap and BLAST files to exist under `runtime/`. If those binaries are not already committed, add them before merging or the validation step will correctly fail.
