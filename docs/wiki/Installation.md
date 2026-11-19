# Installation

insilicoPCR is distributed as portable ZIP archives for Linux and Windows.

The portable releases are the recommended installation method for end users because they include the application and its required runtime tools.

## System requirements

| | Linux | Windows |
|---|---|---|
| System | 64-bit x86 (x86-64), glibc 2.28 or later (BLAST+ needs it): Rocky Linux/RHEL 8 and later, Debian 10 and later, Ubuntu 20.04 and later | 64-bit Windows 10 or 11 (x64) |
| Window | GTK 3 (installed with any desktop); the command line needs no display | |
| Disk | about 270 MB to download, 470 MB extracted, and room for the results (FASTQ mode keeps its baited reads, a few percent of the input) | about 250 MB to download, a similar size extracted |

macOS and ARM processors are not supported: the bundled BLAST+ and Java are built for x86-64.

Memory: FASTA input needs little. FASTQ input runs the BBTools programs in three quarters of the memory the computer has free, shared by the samples processed at once; a sample that runs out of its share is run again alone with all of it. See [Threads and memory in FASTQ mode](Command-line-Interface#threads-and-memory-in-fastq-mode).

## Download

Download the latest release from:

https://github.com/duceppemo/insilicoPCR/releases

Choose the archive for your operating system:

| Operating system | Archive |
|---|---|
| Linux | `insilicoPCR-<version>-linux-x64.zip` |
| Windows | `insilicoPCR-<version>-windows-x64.zip` |

## Extract

Extract the ZIP archive to a writable folder.

Do not run the program directly from inside the compressed ZIP archive.

Keep the extracted folder intact. The launcher expects the bundled runtime files to stay beside the application.

## Linux

Open a terminal in the extracted folder and run:

```bash
chmod +x run-insilicoPCR.sh
./run-insilicoPCR.sh
```

Starting from the terminal is recommended because startup messages and errors are displayed directly in the console.

## Windows

Open the extracted folder and double-click:

```text
run-insilicoPCR.bat
```

You can also run it from PowerShell:

```powershell
.\run-insilicoPCR.bat
```

## Included software

Each portable release includes:

- insilicoPCR
- OpenJDK 25 (LTS) runtime
- JavaFX 26.0.1 runtime
- BBTools/BBMap 40.04 (BBDuk, BBMerge and Tadpole, used in FASTQ mode)
- NCBI BLAST+ 2.17.0 (Linux) or 2.15.0 (Windows)
- platform launcher script

No separate Java, JavaFX, BBMap, or BLAST+ installation is required. Their versions are in `VERSIONS.txt` (Linux ZIP, since 0.15.3), and each run writes them in `QAlog.txt` and in the HTML report's run details (`Tools:`). Their licenses are in `THIRD-PARTY-NOTICES.md` and `third_party/`.

## Launcher options

The launcher passes its arguments to insilicoPCR: without any, it opens the window; with them, it runs on the command line.

```bash
./run-insilicoPCR.sh --version
./run-insilicoPCR.sh --help
./run-insilicoPCR.sh -i assemblies/ -p primers.fasta -o results/
```

See [Command-line Interface](Command-line-Interface) for every option.

## Without the bundled programs (package managers, containers)

Since 1.0.1, each release also has `insilicoPCR-<version>-linux-x64-no-runtime.zip` (about 9 MB): insilicoPCR and its libraries, without Java, BLAST+ and BBTools. The Bioconda package is made from it. It is for an environment that provides those programs already, such as a conda environment or a container:

| Program | Version | Found as |
|---|---|---|
| Java | 25 or later | `JAVA_HOME`, or `java` on `PATH` |
| NCBI BLAST+ | 2.17.0 tested | `blastn` and `makeblastdb` on `PATH` |
| BBTools | 40.04 tested | `bbduk.sh` on `PATH`; its classes in `bbtools.jar` or `current/` beside it, links followed |

```bash
conda create -n insilicopcr -c conda-forge -c bioconda 'openjdk=25' 'blast=2.17.0' 'bbmap=40.04'
conda activate insilicopcr
unzip insilicoPCR-<version>-linux-x64-no-runtime.zip
insilicoPCR-<version>-linux-x64-no-runtime/run-insilicoPCR.sh --version
```

insilicoPCR takes the programs on `PATH` only when there is no `runtime` folder beside it: a portable ZIP always runs its own. Each run writes the programs it used, with their versions, in `QAlog.txt` (`BLAST Location`, `BBTools Location`, `Tools:`). Results with the versions above are the same as with the portable ZIP; other versions of BLAST+ or BBTools may give other results. The JavaFX libraries in this ZIP are those for Linux x64.
