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
2. Select the input file or directory (**Select ▾** → **File…** or **Folder…**).
3. Select the primer FASTA file.
4. Select the output directory.
5. Choose the number of threads, mismatches, and BLAST e-value if needed; **Amplicon settings…** and **FASTQ settings…** hold the rest.
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

Start with `consolidated_report/report.html` in the output folder (in the GUI: **View HTML Report**). Its
grid has one row per sample and one column per assay, and shows which samples are positive, with the product
size in each cell. Then review, as needed:

- `consolidated_report/report.tsv`: the same results as a table, one row per amplicon
- the synthetic gel view
- detailed per-sample report files (the raw BLAST hits)
- console or GUI log output, and `QAlog.txt`

A product of the wrong size is still reported: check `AmpliconSize` before calling a test positive, or
[declare the expected size](Primer-FASTA-Format#declaring-product-sizes-optional) in the primer file and let
the reports label it. See [Reports](Reports).

For primer naming requirements, see [Primer FASTA Format](Primer-FASTA-Format).
