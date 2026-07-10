# First Analysis

This page walks through a minimal first run.

## Prepare inputs

You need:

1. An input FASTA or FASTQ file, or a directory containing FASTA/FASTQ files.
2. A primer FASTA file using the required `-F`, `-R`, and optional `-P` suffixes.
3. An empty or writable output directory.

## Example primer file

Standard PCR example:

```fasta
>targetA-F
ATGCGTACGTTAGCTAGCTA
>targetA-R
TCGATCGATACGCGTACGTA
```

## GUI workflow

1. Start the graphical application.
2. Select the input file or directory.
3. Select the output directory.
4. Select the primer FASTA file.
5. Choose the number of threads, mismatches, and BLAST e-value if needed.
6. Click **Run**.
7. Review logs and reports when the run finishes.
8. Open the synthetic gel visualizer if desired.

## CLI workflow

Linux portable release example:

```bash
./runtime/linux/jdk/bin/java \
  -jar insilicoPCR.jar \
  -i assemblies_or_reads/ \
  -p primers.fasta \
  -o results/ \
  -t 8 \
  -m 1
```

## After the run

Review:

- console or GUI log output
- detailed per-sample report files
- consolidated report
- Excel report, if generated
- synthetic gel view

For primer naming requirements, see [Primer FASTA Format](Primer-FASTA-Format).
