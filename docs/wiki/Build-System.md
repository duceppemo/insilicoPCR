# Build System

insilicoPCR uses Maven with the Maven wrapper.

## Requirements

- Java 26
- Maven wrapper included in the repository

## Build

```bash
./mvnw clean package
```

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
