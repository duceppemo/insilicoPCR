# Build System

insilicoPCR uses Maven with the Maven wrapper.

## Requirements

- Java 25 (LTS)
- Maven wrapper included in the repository

## Build

```bash
git clone https://github.com/duceppemo/insilicoPCR.git
cd insilicoPCR
./mvnw test            # unit tests (JUnit 5) with JaCoCo coverage
./mvnw clean package
```

The tests include whole runs of the five-isolate example (`EndToEndTest`), which use the BLAST+ and BBTools under `runtime/` (Git LFS) and are skipped without them. `./mvnw test` fails when line coverage drops below the floors set in `pom.xml` (the whole project, and the `pcr`, `util` and `pipeline` packages); without the tools, run `./mvnw test -Djacoco.haltOnFailure=false`. For a quicker build, `-DexcludedGroups=end-to-end -Djacoco.haltOnFailure=false` leaves out the whole runs (about 40 s instead of 2 minutes; the coverage floors need them).

Development requires Java 25 or later (releases are built and tested with Java 25 LTS). End users should normally use the portable releases, which bundle their own Java.

## Output

The main application JAR is built under:

```text
target/insilicoPCR.jar
```

Runtime dependencies are copied under:

```text
target/lib/
```

## Run built JAR

```bash
java -jar target/insilicoPCR.jar -h
```

## Main class

The Maven configuration points to:

```text
ca.canada.inspection.dispatchpcr.Dispatcher
```

The dispatcher chooses GUI or CLI mode depending on whether command-line arguments are provided.
