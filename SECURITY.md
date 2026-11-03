# Security Policy

## Supported versions

Only the latest release of insilicoPCR receives fixes. The portable ZIPs bundle their own Java, JavaFX, BBMap, and NCBI BLAST+ runtimes, so security updates to those components are delivered through new insilicoPCR releases.

| Version | Supported |
|---|---|
| Latest release | ✅ |
| Older releases | ❌ |

## Reporting a vulnerability

Please **do not open a public issue for security problems**. Instead, use GitHub's private vulnerability reporting: go to the repository's **Security** tab → **Report a vulnerability**. You should receive a response within a week.

When reporting, please include:

- the insilicoPCR version (`-v`) and operating system,
- a description of the issue and its impact,
- steps or files needed to reproduce it (malicious FASTA/FASTQ/primer files are in scope — the application parses user-supplied files and runs bundled external tools on them).

## Scope notes

- insilicoPCR is a desktop/CLI analysis tool; it makes no network connections at runtime.
- Issues in the bundled third-party tools (BBMap, BLAST+, the JDK) should be reported upstream, but please also report them here so the bundled versions can be updated.
