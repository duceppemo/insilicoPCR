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

If the launcher says `Error: could not find java.dll` or `Could not find Java SE Runtime Environment`, the folder it was extracted to has a letter outside the computer's code page in its path (a Chinese, Greek or Cyrillic letter on a computer set up for English or French, say): the Java launcher cannot find itself there. Extract it to a folder whose path has none, such as `C:\insilicoPCR`. Accented letters such as `é` are fine.

## Paths on Windows

- **Letters outside the code page.** Java receives command-line arguments in the computer's code page: a path with a letter outside it (`数据`, `Δ` on a computer set up for English or French) reaches it as `?`, and the run stops saying so. The external programs (BLAST+, BBTools) are given paths the same way, from the application too: the output folder's and the input files' (not the primer file's, which insilicoPCR reads itself). Rename such folders, or turn on **Use Unicode UTF-8 for worldwide language support** (Settings, Time & language, Language & region, Administrative language settings, Change system locale). Accented letters (`Données`, `Résultats`) work.
- **Long paths.** BLAST+ cannot open a path of 260 characters or more, and Windows starts no program in such a folder, whatever the long-path setting. A run whose files would pass that length is refused at its start, naming the longest path; choose an output folder nearer the top of the drive. The output folder is checked before the input files are read (since 0.15.3), each sample's files once the input check has named the samples.
- **Sample names Windows cannot give a folder.** A sample's results go to a folder named after it, and Windows cannot make one whose name ends with a space or a dot (`Sample 1 _R1.fastq.gz` gives the sample `Sample 1 `) or is a device name (`con.fasta`, `nul.fa`, `com1.fastq.gz`). Since 0.15.3 such a sample is left out with a warning (`the sample name ends with a space`) and the others are run; before, the whole run stopped with `Trailing char < >`. Rename the file.
- **FASTQ input from an installation whose path has an accent and a `#` or `%`.** BBTools finds its resources from its own path, and cannot read one that has both (`D:\Données\C# tools`): the run stops naming the folder. Move insilicoPCR to a folder whose path has neither; assemblies run from anywhere.
- **A trailing backslash before a quote.** In `-i "C:\my data\" -p primers.fasta`, the backslash keeps the quote, and `-i` gets the rest of the line. Leave out the last backslash: `-i "C:\my data"`.
- **Files open in another program.** A sample's BLAST hits (`detailed_report/<sample>/<sample>.tsv`) or the primer database of the last run, open in Excel or held by an antivirus scan, cannot be replaced by the next run into the same folder, which stops naming the file: close it, or give the run another output folder. The consolidated reports are never replaced (each run writes `report(n).tsv` with the next number), so one open in Excel does not stop a run.

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

BBMerge, used in FASTQ mode for paired reads, also needs `runtime/common/bbmap/networks/bbmerge.bbnet.gz`. In a source checkout, `REQUIRE_BINARIES=true scripts/dev/verify-runtime-layout.sh`, run from the repository root, lists anything missing.

## FASTQ run fails with `truncated or corrupt input`

The full message may also say `There appear to be different numbers of reads in the paired input files`, even though the files are intact. This happens with releases before 0.9.0 on multi-member (concatenated) `.fastq.gz` files, which is the default output of Illumina bcl-convert and DRAGEN. The bundled BBTools 39.94 stopped reading at a gzip member boundary whenever BBDuk ran on more than one thread.

Upgrade to 0.9.0 or newer, which bundles BBTools 40.04. The files are then read completely, with no repacking needed.

## FASTQ run aborts with `OutOfMemoryError`

Releases before 0.9.0 ran the second baiting pass with the `-m` mismatch budget as BBDuk's `hdist`, and on large samples with `-m 2` or more its k-mer table outgrew any heap. Since 0.9.0 that pass runs with `hdist=0`. Upgrade.

## FASTQ run fails with `ran out of memory on sample`

The message is `Tadpole ran out of memory on sample <name> with -Xmx<n>g` (or `BBDuk`, `BBMerge`). The sample had all the memory insilicoPCR gives a BBTools program: `--memory` (`--bbtools-memory` before 1.0) when given, otherwise three quarters of the memory it leaves free on the computer, at least half of its own heap; up to 64 GB. (Samples that run out of only their share, when several run at once, are run again alone automatically; see [Threads and memory in FASTQ mode](Command-line-Interface#threads-and-memory-in-fastq-mode).)

- Below 64 GB (`all it had`): give the BBTools programs more with `--memory <GB>` (in the application, **FASTQ settings…**), as much as the computer can spare, or run on a computer with more memory.
- At 64 GB (`the most insilicoPCR gives a BBTools program`): more memory does not help. The sample holds too many reads that match the primers; check the primers, and a large `--tm-margin` or low `--anneal-temp`, which accept short partial matches.
- If the program's output above the message says `unable to create native thread`, it is the computer's limit on threads, not memory: use fewer threads with `-t` (in the application, **Threads**).

When the read extension runs out of memory, the run goes on: the sample's assays stay as the read-level search left them, and a `Warning:` line in the log, and in `QAlog.txt`, says `read extension could not be run for sample <name> (Tadpole ran out of memory ...)`.

Releases before 0.14.0 reported this as `Tadpole failed with exit code 1`, with `java.lang.OutOfMemoryError` in the program's output above it. 0.14.0 and 0.15.0 still did when Tadpole stopped because its k-mer table was full (its output says `This program ran out of memory.`): the run stopped there, instead of running the sample again alone.

## An assay is negative in FASTQ mode but positive on an assembly

A FASTQ-mode negative is weaker evidence than an assembly-mode one: the mini-assembly can miss the target region or break inside the amplicon. Since 0.10.0 such assays are, where the reads support them, called from the reads themselves (`FASTQ read-level call` in `report.tsv`), and since 0.13.0 products longer than a read are looked for by extending the reads (`FASTQ read-extension call`). Every missed assay whose oligos are in the reads is listed in `consolidated_report/report_fastq_diagnostics.tsv`, with the reason. See [FASTQ mode and false negatives](Reports#fastq-mode-and-false-negatives).

These cases remain negative by design (see also the figure on [Limitations](Limitations)):

- **The amplicon is longer than a read or merged pair** (about 580 bp for 2 x 300 bp reads) **and the reads could not be joined.** No read can hold such a product, so the reads carrying its primers are extended through the reads baited in rounds; see [Read extension](Reports#read-extension-products-longer-than-a-read). When too few reads carry the primers, the extension stops before joining them and the diagnostics say `NOT_CLOSED_BY_EXTENSION`, with the number of reads extended. Run the assay on an assembly of the reads.
- **Extended reads hold the product, but the read pairs do not confirm it.** The diagnostics say `EXTENSION_NOT_SUPPORTED_BY_PAIRS` (some stretch of the product lies inside no read pair, or the pairs are too few: about 20x and less) or `EXTENSION_THROUGH_UNRESOLVED_REPEAT` (the product passes through a repeat longer than the read pairs, where a real product and a wrong turn of the extension look the same). The product is withheld on purpose; the HTML report marks the sample "not confirmed by read pairs". Confirm on an assembly, of long reads for the repeat case.
- **The extended reads end in a tandem repeat** ((AT)n, (CAG)n, a VNTR) **and loop in it** (`EXTENSION_IN_TANDEM_REPEAT`, since 0.15.3). The extension cannot cross a repeat longer than its k-mers, so a product through it is neither shown nor ruled out: the HTML report marks the sample "not checkable from reads", and `ReadDetail` names the repeat's unit. Confirm on an assembly of long reads, or by PCR.
- **The run used `--read-extension off`.** The diagnostics then say `NOT_IN_READS` with the longest amplicon the reads could have held, and when the primer file declares a size longer than the reads could hold, the HTML report marks the sample "not checkable from reads" instead of negative.
- **`WEAK_READ_SUPPORT`**: complete amplicons are in the reads, but in under `--read-min-fraction` (default 30%) of the reads covering the locus. This is the signature of contamination, and also of a locus of which only some copies match the primers and probe. Read the counts in `ReadDetail`, and lower `--read-min-fraction` if the sample is known to be mixed.

A `--tadpole-k` longer than a sample's reads leaves its mini-assembly empty: Tadpole builds no contig from reads shorter than its k-mers. Since 1.0 the run says so, `Warning: --tadpole-k 200 is longer than the reads of sample <name> (150 bases at most): its mini-assembly is empty, and its products are looked for in the reads only` (before, only blastn's `Query is Empty` showed it). Use a k shorter than the reads; the default is 31.

## FASTQ run seems to hang after BLAST

Releases before 0.11.0 could spend hours in the read-level search after `blastn` had finished, on samples with hundreds of thousands of baited reads (large primer panels, permissive mismatch settings). One CPU stays busy and nothing is logged. The results are correct but late. Upgrade to 0.11.0, where the same step takes seconds.

## An assay is never reported although its primers match

Check the primer FASTA headers. In releases before 0.11.0, any text after the primer ID (`>assay-F forward primer`) became part of the ID, no BLAST hit matched it, and the assay was silently negative in every sample. Since 0.11.0 the ID ends at the first blank and the rest is a description. Before 0.15.3, a probe named for its dye, `stxQ-FAM`, was read as a second forward primer: its assay lost its probe requirement, and a product of `stxQ-FAM` with `stxQ-R` could be reported; such an ID is refused now.

## A 1.x command line fails with `was removed in 2.0`

`-m`/`--mismatches`, `--probe-mismatches` and `--5p-truncation` were removed in 2.0: a primer or probe site is accepted when its Tm on the template reaches `--anneal-temp` (default 60 °C) less `--tm-margin` (default 5 °C), whatever its mismatches. A command line that gives one stops with exit code `2`:

```text
Error: -m was removed in 2.0: a primer or probe site is accepted when its Tm reaches --anneal-temp (default 60 °C) less --tm-margin (default 5 °C), whatever its mismatches. Leave -m out; lower --anneal-temp or raise --tm-margin to accept weaker sites
```

Leave the option out. To accept weaker sites, as a larger `-m` did, lower `--anneal-temp` or raise `--tm-margin`. See [Amplicon Calling](Amplicon-Calling).

## An assay gives no product, or fewer than with 1.x

Since 2.0 a site is judged by its Tm, the same rule for every assay. An assay can then give no product in silico: an oligo too short or too A/T-rich for the run's annealing temperature, or an assay published for other conditions. `consolidated_report/report_no_product.tsv` (and **Why no product** in the HTML report) gives, for each sample, the oligo without an accepted site, its best site and its Tm, and the rule it fails; see [Assays without a product](Reports#assays-without-a-product). The run's log and `QAlog.txt` list each oligo's Tm on a perfect match, and flag one below the minimum: that oligo is unlikely to anneal anywhere, and its assay to give a product in any sample.

If the assay is run at another temperature, give it: `--anneal-temp 55`, or `ta=55` on its primer header when the other assays of the file are not ([Primer FASTA Format](Primer-FASTA-Format#annealing-temperature-optional)). To accept weaker sites, raise `--tm-margin`. The reaction (`--na`, `--mg`, `--dntp`, `--oligo-conc`) changes the Tm too. Why the Tm decides is on [Why Tm](Why-Tm).

## A product has an unexpected size

That is a result, not an error: every product a PCR would amplify is reported. Permissive settings (a large `--tm-margin` or a low `--anneal-temp`) let primers pair at other places in the genome; `ForwardTm` and `ReverseTm` show how well each site melts. See [the FAQ](FAQ#why-is-a-product-of-the-wrong-size-reported).

## Primer file errors

Primer FASTA IDs must end with:

- `-F`
- `-R`
- `-P` for probes

or have one of them before an underscore (`toxA-F_2`).

See [Primer FASTA Format](Primer-FASTA-Format).

Since 0.12.0 the command line reports a mistake in the primer file (an ID without a role suffix, characters that are not bases, a `size=` that cannot be read, two different sizes for one assay; since 0.15.3 also an ID with a character outside ASCII, and `other=` without `size=`; an ID whose role letter is followed by more letters, such as `stxQ-FAM`, `toxA-Rev` or `toxA-F2`, or written in lower case, `toxA-f`: `Primer ID must follow the <assay>-F / <assay>-R / <assay>-P naming convention: stxQ-FAM (the role letter ends the ID, or comes before an underscore: ...)`; an oligo of fewer than 7 bases: `Primer toxA-F is 6 bases long: BLAST finds no site of an oligo under 7 bases...`; a character that is not a base, named: `Primer sequence of toxA-F holds characters that are not bases (*)`; since 1.0 also a header with no ID, and `+` in any assay ID) as one `Error:` line and exits with code 1, before it makes the output folder (since 1.0 for a mistake in a sequence too, which left an empty output folder behind). A `key=value` word in a header that insilicoPCR does not read (`Tm=60`, a mistyped `sise=420`) is not an error: since 1.0 it is named in a `Warning:` line and ignored. Earlier releases printed a Java stack trace for the same mistakes.

Since 0.12.1 a failing external tool (BLAST+, BBTools) is reported the same way: the tool's own message, then `Error:` with the stage that failed and `Cause:` with the full command line and its exit code. Copy the command line to run the tool by hand.

In the application, releases before 0.12.1 did not notice a failed `blastn` search: the run finished and the sample was shown without any product. Since 0.12.1 the run stops with `Run failed` and names the file that could not be searched.

Up to 0.12.1 the application printed a Java stack trace in its output area when a run failed. Later releases print `Run failed` and an `Error:` line there for a failure you can act on (a tool that failed, a file that cannot be read or written), and keep the stack trace for anything else; the terminal the application was started from always gets the stack trace.

## `Malformed input or input contains unmappable characters`

A file or folder name has a letter outside ASCII (an accent) and the system locale is not UTF-8 (`LANG=C`, common in cron jobs, batch schedulers and containers): Java cannot name the file. The Linux launcher `run-insilicoPCR.sh` switches to UTF-8 itself since 0.15.2; when running the jar directly, set the locale first:

```bash
export LC_ALL=C.UTF-8
```

## A program fails and its command is in the error

The error gives the command that failed and the folder it ran in, `(in <folder>)`; some of its file names are relative to that folder. To run it by hand, `cd` there first.

## A sample is missing from the report

Since 0.13.0, look for `Warning:` lines at the start of the run and for the section **Input not analysed** at the top of the HTML report (also in `QAlog.txt`). A sample whose file is empty, damaged, cut short, malformed or not what its extension says is left out, with the file and the fault named, and the other samples are run. See [Input check](Running-Analyses#input-check).

Typical causes: a library that gave no read (an empty `.fastq.gz` of about 20 bytes), a download or copy that was interrupted (`the gzip stream ends early`, or `it ends inside read ...`), and two files of a pair that do not belong together or of which one is incomplete (`different numbers of reads`). Replace the files and run the sample again.

Releases before 0.13.0 did not say anything: an empty file was skipped silently, so the sample was simply absent from the report, and a damaged one stopped the whole run in the first baiting step (`truncated or corrupt input`).

## Run fails with `BLAST query error` on a gzipped assembly

Releases before 0.12.1 accept a gzipped FASTA file (`.fasta.gz`, `.fna.gz` and so on) as input, then hand it to `blastn`, which does not read gzip and stops. In the application the same input ends with `Unable to read sequence file for sample`. Upgrade to 0.12.1, or decompress the assemblies first (`gunzip *.fasta.gz`). Gzipped FASTQ reads were never affected.

## Results differ between 0.11.0 and 0.12.0

The options named here are those of 1.x, removed in 2.0. Releases before 0.12.0 miscounted mismatches near the ends of probes and near the 3' end of primers, because BLAST leaves such a mismatch out of its alignment together with the matching bases beyond it. Since 0.12.0 those bases are compared with the sample sequence. Three kinds of calls can change:

- **A probe mismatch on the first or last two bases** used to go uncounted: the assay was reported with `0` probe mismatches, even at the default `--probe-mismatches 0`. It is now counted, so the assay is negative unless `--probe-mismatches` is 1 or more.
- **A probe mismatch on the third or fourth base from either end** used to remove the assay whatever `--probe-mismatches` allowed. It now counts as one mismatch, and the assay is reported when the budget allows it.
- **Primer mismatches near the 3' end** used to count once per base BLAST trimmed. They now count once each, so runs with `--3p-penalty` above 0, or with `-m 2` or more, can report amplicons that 0.11.0 rejected.

See [Amplicon Calling](Amplicon-Calling) for the rules.

## Missing positive results in mixed primer files

Since version 0.8.0, standard PCR and qPCR/probe assays can be mixed in one primer FASTA file. If an assay that defines a `-P` probe is missing from the report even though its primers hit, the probe did not produce an accepted hit inside the amplicon — that assay is intentionally kept negative. In versions before 0.8.0, any `-P` primer switched the whole report to qPCR mode and silently dropped all probe-less assays; upgrade if you see that behavior.

## CLI help

Use:

```bash
./run-insilicoPCR.sh -h
```

or `./runtime/linux/jdk/bin/java -jar insilicoPCR.jar -h`.

A mistake on the command line prints `Error: <what is wrong>` and `Run insilicoPCR --help for the options.` on standard error, and exits with code 2 (since 1.0; before, the whole help followed on standard output). See [Exit codes](Command-line-Interface#exit-codes-and-stopping-a-run).

## Getting help

Search the [issues](https://github.com/duceppemo/insilicoPCR/issues), and open a new one if yours is not there. Give:

- the version (`run-insilicoPCR.sh --version`, or the first line of `QAlog.txt`) and the operating system;
- the command, or the settings in the GUI (they are in `QAlog.txt`, under `Settings:`, with the tools' versions);
- the messages of the run: the console, or `QAlog.txt`;
- if you can, a small primer file and input that show the problem.

Issues are public: do not attach sequences, sample names or paths you may not share. Leave them out, or rename them first.
