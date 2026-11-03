# Troubleshooting

## The application does not start on Linux

Run the launcher from a terminal:

```bash
cd insilicoPCR-linux-x64
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

Check the terminal output for missing files or permission errors.

## Permission denied on Linux

Make the launcher executable:

```bash
chmod +x run-insilicoPCR.sh
```

If needed, also ensure bundled tool scripts are executable.

## The application does not start on Windows

Make sure the ZIP was fully extracted before running the launcher.

Run:

```text
run-insilicoPCR.bat
```

Do not run the application directly from inside the ZIP archive.

## Windows SmartScreen warning

Windows may warn about unsigned software.

Only continue if the release was downloaded from the official repository.

## Missing Java

Portable releases include a bundled Java runtime. You should not need system Java.

If Java is reported missing, the release folder may be incomplete or files may have been moved after extraction.

## Missing BBMap or BLAST+

Portable releases include BBMap and BLAST+.

If a tool is reported missing:

- confirm the ZIP was fully extracted
- keep the `runtime/` folder beside the launcher
- do not move files out of the release folder

## Primer file errors

Primer FASTA IDs must end with:

- `-F`
- `-R`
- `-P` for probes

See [Primer FASTA Format](Primer-FASTA-Format.md).

## Missing positive results in mixed primer files

Since version 0.7.0, standard PCR and qPCR/probe assays can be mixed in one primer FASTA file. If an assay that defines a `-P` probe is missing from the report even though its primers hit, the probe did not produce an accepted hit inside the amplicon — that assay is intentionally kept negative. In versions before 0.7.0, any `-P` primer switched the whole report to qPCR mode and silently dropped all probe-less assays; upgrade if you see that behavior.

## CLI help

Use:

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h
```
