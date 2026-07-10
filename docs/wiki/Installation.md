# Installation

insilicoPCR is distributed as portable ZIP archives for Linux and Windows.

The portable releases are the recommended installation method for end users because they include the application and its required runtime tools.

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
- OpenJDK 26 runtime
- JavaFX 26.0.1 runtime
- BBMap
- NCBI BLAST+
- platform launcher script

No separate Java, JavaFX, BBMap, or BLAST+ installation is required.
