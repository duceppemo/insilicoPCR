# Contributing to insilicoPCR

Thanks for considering a contribution! Bug reports, primer-handling edge cases, documentation fixes, and code are all welcome.

## Quick start for developers

```bash
git clone https://github.com/duceppemo/insilicoPCR.git
cd insilicoPCR
./mvnw test           # unit tests (JUnit 5) + JaCoCo coverage
./mvnw clean package  # builds target/insilicoPCR.jar
java -jar target/insilicoPCR.jar -h
```

Development requires **Java 26** (the portable releases bundle their own runtime).

The tests include whole runs of the five-isolate example (`EndToEndTest`), which use the BLAST+ and BBTools under `runtime/` (Git LFS) and are skipped without them. `./mvnw test` fails when line coverage drops below the floors set in `pom.xml` (the whole project, and the `pcr`, `util` and `pipeline` packages); without the tools, run `./mvnw test -Djacoco.haltOnFailure=false`. For a quicker build, `-Dsurefire.excludedGroups=end-to-end` leaves out the whole runs.

## Ground rules

- Keep the project **portable and reproducible**: no new runtime downloads at build time, no committed build output. The curated BBMap and BLAST+ assets under `runtime/` are intentionally part of the repository (Git LFS).
- Keep **GUI and CLI behavior consistent** — both run the same pipeline (`InSilicoPcrPipeline`), and the analysis logic lives in the shared `ca.canada.inspection.pcr` package; scientific decisions belong there, not in UI code.
- **Add or update unit tests** for behavior changes, especially in the amplicon-calling core (`src/test/java`).
- Update documentation with your change: the README only for overview/quick-start changes, the [`docs/wiki/`](docs/wiki/) mirror for everything else (published to the GitHub Wiki with `scripts/docs/publish-wiki.sh`), and the SVG diagrams under `docs/assets/` if the workflow or architecture changes.
- Record user-visible changes in [`CHANGELOG.md`](CHANGELOG.md).

## Recommended checks before opening a PR

```bash
./mvnw clean package
scripts/dev/verify-runtime-layout.sh
```

## Pull requests

Prefer focused changes that are easy to review. For larger refactors, explain why the change is needed, what behavior must remain unchanged, and what testing was performed. CI builds both portable ZIPs and runs the test suite with coverage on every PR.

More detail lives in the Wiki: [Developer Guide](https://github.com/duceppemo/insilicoPCR/wiki/Developer-Guide), [Architecture](https://github.com/duceppemo/insilicoPCR/wiki/Architecture), [Contributing](https://github.com/duceppemo/insilicoPCR/wiki/Contributing).
