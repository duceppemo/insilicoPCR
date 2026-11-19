# Release process

The release pipeline is deterministic and does not download BBMap or BLAST during CI.

## Source-controlled runtime assets

The runtime tools are committed as application assets under `runtime/common/bbmap`, `runtime/linux/blast`, and `runtime/windows/blast`.

Generated release output, Maven output, JavaFX SDK folders, and JDK/JRE folders must not be committed.

## CI flow

1. Check out the repository with Git LFS enabled.
2. Install JDK 25 (LTS) with `actions/setup-java`.
3. Check the runtime folders with `scripts/dev/verify-runtime-layout.sh`; the bundled BBMap and BLAST+ are checked after packaging.
4. Build with Maven.
5. Copy the current CI JDK into the staged portable runtime.
6. Create platform ZIP and SHA256 files.
7. Upload artifacts, and publish them only for `v*` tags.

## Local runtime validation

Run `REQUIRE_BINARIES=true scripts/dev/verify-runtime-layout.sh`, from the repository root (without `REQUIRE_BINARIES=true` it only checks that the `runtime/` folders exist).

## Runtime validation

The packaging scripts deliberately fail when BBMap, BLAST, the built JAR, Maven runtime libraries, or the packaging JDK are missing. A release ZIP should never be created with missing bioinformatics tools.
