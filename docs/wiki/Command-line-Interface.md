# Command-line Interface

insilicoPCR can run without the JavaFX graphical interface. This is useful for automation, batch analysis, remote Linux machines, HPC environments, and workflow managers such as Nextflow or Snakemake.

Use the bundled Java runtime from the portable release so the command does not depend on a system Java installation.

## Show help

From inside the extracted Linux portable release folder:

```bash
cd insilicoPCR-linux-x64
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

Expected help output:

```text
usage: java -jar insilicoPCR.jar -i <input> -o <output> -p <primers> [-t
            n] [-m n] [-e value]
 -e,--evalue <value>   blastn e-value; default: 1e5
 -h,--help             Print help and usage
 -i,--input <path>     Input file/directory containing .fasta/.fastq
                       sequence(s)
 -m,--mismatches <n>   Allowed primer mismatches; default: 0
 -o,--output <dir>     Directory for output files
 -p,--primers <file>   Primer FASTA file
 -t,--threads <n>      Number of worker threads; default: available
                       processors
 -v,--version          Print version
```

## Required options

| Option | Meaning |
|---|---|
| `-i`, `--input` | Input FASTA/FASTQ file or directory |
| `-o`, `--output` | Output directory |
| `-p`, `--primers` | Primer FASTA file |

## Optional options

| Option | Meaning |
|---|---|
| `-t`, `--threads` | Number of worker threads; default is available processors |
| `-m`, `--mismatches` | Allowed primer mismatches; default is `0` |
| `-e`, `--evalue` | BLASTN e-value; default is `1e5` |
| `-v`, `--version` | Print version |
| `-h`, `--help` | Print help |

## Example: assembly folder

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies/ \
  -p primers.fasta \
  -o results/
```

## Example: read folder

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i reads/ \
  -p primers.fasta \
  -o results/ \
  -t 8 \
  -m 1
```

## Example: one input file

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i sample.fasta \
  -p primers.fasta \
  -o sample-results/
```

## Notes

- The input path can be a single sequence file or a directory.
- The primer FASTA must follow the required `-F`, `-R`, and optional `-P` naming convention.
- Standard PCR and qPCR/probe assays should be run separately.
- The CLI uses the same pipeline as the graphical application.
