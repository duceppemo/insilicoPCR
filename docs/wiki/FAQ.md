# FAQ

## Do I need to install Java?

No. Portable releases include the Java runtime.

## Do I need to install BBMap or BLAST+?

No. Portable releases include BBMap and NCBI BLAST+.

## Can I run insilicoPCR from the terminal?

Yes. Use the bundled Java runtime:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```

## Can I use FASTQ reads?

Yes. insilicoPCR supports FASTA assemblies and FASTQ reads.

## Can I use a folder as input?

Yes. The input can be a single sequence file or a directory containing sequence files.

## What primer format is required?

The primer file must be FASTA. Primer IDs must end with `-F`, `-R`, or `-P`.

## Can I mix PCR assays with qPCR/probe assays?

No. Run standard PCR assays and qPCR/probe assays separately.

## Why is Linux launched from a terminal?

Launching from a terminal makes startup messages and errors visible, which helps troubleshooting.

## What is the synthetic gel visualizer?

It is a visual summary of consolidated report results, displayed as gel-like bands. It is useful for reviewing amplicon size patterns across samples.
