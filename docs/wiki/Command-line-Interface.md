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
            n] [-m n] [-e value] [-l bp]
    --3p-penalty <score>   Maximum weighted 3'-end penalty (mismatch: 3 at
                           terminus, 2 at -1/-2, 1 beyond; +1 if a G/C
                           pair is broken near the terminus; +1 for
                           indels); default: 0
    --3p-window <n>        Bases at the primer 3' end checked with the
                           weighted 3' penalty; default: 5
 -e,--evalue <value>       blastn e-value; default: 1e5
 -h,--help                 Print help and usage
 -i,--input <path>         Input file/directory containing .fasta/.fastq
                           sequence(s)
 -l,--max-length <bp>      Maximum amplicon length in bp, 0 = unlimited;
                           default: 5000
 -m,--mismatches <n>       Allowed mismatches+indels per primer (unaligned
                           3' bases count); default: 0
    --min-length <bp>      Minimum amplicon length in bp; default: 0
 -o,--output <dir>         Directory for output files
 -p,--primers <file>       Primer FASTA file
 -t,--threads <n>          Number of worker threads; default: available
                           processors
 -v,--version              Print version
```

## Required options

| Option | Meaning |
|---|---|
| `-i`, `--input` | Input FASTA/FASTQ file or directory |
| `-o`, `--output` | Output directory |
| `-p`, `--primers` | Primer FASTA file |

## Optional options

| Option | Default | Meaning |
|---|---|---|
| `-t`, `--threads` | available processors | Number of worker threads |
| `-m`, `--mismatches` | `0` | Allowed mismatches + indels per primer; unaligned 3' bases count against the budget |
| `-e`, `--evalue` | `1e5` | BLASTN e-value |
| `-l`, `--max-length` | `5000` | Maximum amplicon length in bp; `0` disables the limit |
| `--min-length` | `0` | Minimum amplicon length in bp |
| `--3p-window` | `5` | Number of bases at the primer 3' end checked with the weighted penalty |
| `--3p-penalty` | `0` | Maximum tolerated weighted 3'-end penalty |
| `-v`, `--version` | | Print version |
| `-h`, `--help` | | Print help |

See [Amplicon Calling](Amplicon-Calling) for exactly how the mismatch budget, the 3'-end penalty, and the size limits decide which amplicons are reported.

## Example: assembly folder

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies/ \
  -p primers.fasta \
  -o results/
```

## Example: read folder, one tolerated internal mismatch

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i reads/ \
  -p primers.fasta \
  -o results/ \
  -t 8 \
  -m 1
```

## Example: inclusivity screen that tolerates 3'-end wobble

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies/ \
  -p primers.fasta \
  -o results/ \
  -m 2 \
  --3p-penalty 3
```

## Example: long-range PCR (no product size cap)

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies/ \
  -p primers.fasta \
  -o results/ \
  -l 0
```

## Notes

- The input path can be a single sequence file or a directory.
- The primer FASTA must follow the required `-F`, `-R`, and optional `-P` naming convention.
- Standard PCR and qPCR/probe assays should be run separately.
- The CLI uses the same analysis engine as the graphical application, so results are identical.
