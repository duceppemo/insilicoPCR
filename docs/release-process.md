# Release process

The release pipeline is deterministic and does not download BBMap or BLAST during CI.

## Source-controlled runtime assets

The following runtime tools are committed as application assets:

```text
runtime/common/bbmap
runtime/linux/blast
runtime/windows/blast
```

The following runtime assets are generated or copied during packaging and must not be committed:

```text
runtime/linux/jdk
runtime/windows/jdk
runtime/**/javafx-sdk*
build/
target/
release/
out/
```

## CI flow

1. Check out the repository with Git LFS enabled.
2. Install JDK 26 with `actions/setup-java`.
3. Validate that bundled BBMap and BLAST are present with `scripts/dev/verify-runtime-layout.sh`.
4. Build with Maven.
5. Copy the current CI JDK into the staged portable runtime.
6. Create platform ZIP and SHA256 files.
7. Upload artifacts, and publish them only for `v*` tags.

## Local runtime validation

Run `scripts/dev/verify-runtime-layout.sh`.

## Runtime validation

The packaging scripts deliberately fail when BBMap, BLAST, the built JAR, Maven runtime libraries, or the packaging JDK are missing. A release ZIP should never be created with missing bioinformatics tools.
