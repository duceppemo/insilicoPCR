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
    --3p-penalty <score>         Maximum weighted 3'-end penalty
                                 (mismatch: 3 at terminus, 2 at -1/-2, 1
                                 beyond; +1 if a G/C pair is broken near
                                 the terminus; +1 for indels); default: 0
    --3p-window <n>              Bases at the primer 3' end checked with
                                 the weighted 3' penalty; default: 5
    --5p-truncation <n>          Maximum primer bases allowed to be
                                 unaligned at the harmless 5' end (one
                                 5'-proximal mismatch makes BLAST trim the
                                 whole tail); default: 5
 -e,--evalue <value>             blastn e-value; deliberately permissive,
                                 since primer hits are filtered by the
                                 mismatch and 3'/5' rules instead, and
                                 lowering it (e.g. to 1e-5) drops valid
                                 hits; default: 1e5
 -g,--gel                        Also write the synthetic gel image as PNG
                                 (requires a graphical environment)
 -h,--help                       Print help and usage
 -i,--input <path>               Input file/directory containing
                                 .fasta/.fastq sequence(s)
    --input-check <full|quick>   How the input files are checked before
                                 the run. full: every read of every FASTQ
                                 file is read, and the two files of a pair
                                 must hold the same number of reads.
                                 quick: the first read only, faster on
                                 large files but blind to a file cut
                                 short. A sample with a faulty file is
                                 left out with a warning and the others
                                 are run; default: full
 -l,--max-length <bp>            Maximum amplicon length in bp, 0 =
                                 unlimited; default: 5000
 -m,--mismatches <n>             Allowed mismatches+indels per primer (3'
                                 bases that do not pair with the template
                                 count); default: 0
    --min-length <bp>            Minimum amplicon length in bp; default: 0
 -o,--output <dir>               Directory for output files
 -p,--primers <file>             Primer FASTA file
    --probe-mismatches <n>       Allowed mismatches+indels per hydrolysis
                                 probe; probes bind hotter than primers
                                 and tolerate roughly one more mismatch;
                                 default: same as -m
    --read-extension <mode>      FASTQ mode: which assays the reads
                                 carrying both primers are extended for,
                                 through the baited reads, to find
                                 products longer than a read or merged
                                 pair. One of: unreported (assays found in
                                 no contig and in no read), target (those,
                                 and assays that declare size= and show no
                                 product of that size yet), off (none);
                                 default: unreported
    --read-min-fraction <0-1>    FASTQ mode: share of informative reads
                                 (carrying a primer at the product's locus
                                 and long enough) that must hold a
                                 complete amplicon to call a product the
                                 mini-assembly missed from reads; default:
                                 0.3
 -t,--threads <n>                Number of worker threads; default:
                                 available processors
    --tadpole-k <n>              FASTQ mode: Tadpole k-mer length;
                                 default: 31
    --tadpole-min-contig <bp>    FASTQ mode: minimum contig length kept
                                 (Tadpole mincontig); must not exceed the
                                 amplicons you expect; default: 60
    --tadpole-min-cov <n>        FASTQ mode: minimum average contig
                                 coverage (Tadpole mincov); default: 1
    --tadpole-min-seed <n>       FASTQ mode: minimum k-mer count to seed a
                                 contig (Tadpole mincountseed); default: 2
 -v,--version                    Print version
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
| `-m`, `--mismatches` | `0` | Allowed mismatches + indels per primer; 3' bases that do not pair with the template count against the budget |
| `--probe-mismatches` | same as `-m` | Allowed mismatches + indels per hydrolysis probe (probes bind hotter and tolerate more) |
| `-e`, `--evalue` | `1e5` | BLASTN e-value; deliberately permissive, see [BLAST e-value](Amplicon-Calling#blast-e-value) |
| `-l`, `--max-length` | `5000` | Maximum amplicon length in bp; `0` disables the limit |
| `--min-length` | `0` | Minimum amplicon length in bp |
| `--3p-window` | `5` | Number of bases at the primer 3' end checked with the weighted penalty |
| `--3p-penalty` | `0` | Maximum tolerated weighted 3'-end penalty |
| `--5p-truncation` | `5` | Maximum primer bases allowed unaligned at the 5' end |
| `--input-check` | `full` | How the input files are checked before the run: `full` reads every read of every FASTQ file and compares the read counts of the two files of a pair; `quick` reads the first read only. A sample with a faulty file is left out with a warning. See [Input check](Running-Analyses#input-check) |
| `-g`, `--gel` | off | Also write the synthetic gel image as PNG (needs a graphical environment) |
| `--tadpole-k` | `31` | FASTQ mode: Tadpole k-mer length |
| `--tadpole-min-seed` | `2` | FASTQ mode: minimum k-mer count to seed a contig (Tadpole `mincountseed`) |
| `--tadpole-min-cov` | `1` | FASTQ mode: minimum average contig coverage (Tadpole `mincov`) |
| `--tadpole-min-contig` | `60` | FASTQ mode: minimum contig length kept (Tadpole `mincontig`); keep it below the shortest amplicon you expect |
| `--read-min-fraction` | `0.3` | FASTQ mode: share (0–1) of informative reads that must hold a complete amplicon for a read-level call; see [Read-level calls](Reports#read-level-calls) |
| `--read-extension` | `unreported` | FASTQ mode: which assays the reads carrying both primers are extended for, to find products longer than a read or merged pair. `unreported`: assays found in no contig and in no read. `target`: those, and assays that declare `size=` and show no product of that size yet. `off`: none. See [Read extension](Reports#read-extension-products-longer-than-a-read) |
| `-v`, `--version` | | Print version |
| `-h`, `--help` | | Print help |

The `--read-min-fraction`, `--read-extension` and `--tadpole-*` options only affect FASTQ input, where reads are baited and micro-assembled before BLAST; see [FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives). In the GUI they are under **FASTQ settings…**.

### Threads and memory in FASTQ mode

The BBTools programs that bait and assemble the reads run as separate Java processes. Their heap comes out of half of insilicoPCR's own heap, which the Java `-Xmx` option sets (`java -Xmx96g -jar insilicoPCR.jar ...`); without it, Java takes a quarter of the computer's memory. Samples are processed several at a time, and those running at once share the `-t` threads and that heap. No sample gets less than 4 GB: with less to share, fewer samples run at once. A sample alone gets all the threads and the whole half, up to 64 GB. The log says how it was split, for example `BBTools: 2 samples at a time, each with 4 threads and -Xmx4g`.

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
- Standard PCR and qPCR/probe assays can be mixed in one primer file: assays with a probe require the probe inside the amplicon, probe-less assays are reported on their primer pair alone.
- The CLI uses the same analysis engine as the graphical application, so results are identical.
