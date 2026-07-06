# GitHub Actions CI/CD for portable releases

This repository is configured to build and publish portable ZIP releases only:

- `insilicoPCR-<version>-linux-x64.zip`
- `insilicoPCR-<version>-windows-x64.zip`
- matching `.sha256` checksum files

It intentionally does **not** publish `.exe`, `.msi`, `.dmg`, `.deb`, or `.rpm` installers.

## Workflows

The workflow is defined in:

```text
.github/workflows/ci-portable-release.yml
```

It runs on:

- pushes to `main` or `master`
- pull requests
- manual `workflow_dispatch`
- version tags matching `v*`

For normal pushes and pull requests, it builds Linux and Windows ZIPs and stores them as workflow artifacts.
For tags, it also creates or updates a GitHub Release and uploads only the ZIP/checksum artifacts.

## Runtime dependencies

The workflow downloads runtime dependencies during CI, so large third-party binaries are not committed to Git:

```text
runtime/common/bbmap/
runtime/linux/blast/
runtime/windows/blast/
```

Default URLs are defined in:

```text
scripts/ci/fetch-runtime-deps-linux.sh
scripts/ci/fetch-runtime-deps-windows.ps1
```

You can override them with repository variables or workflow environment variables:

```text
BBMAP_URL
BLAST_LINUX_URL
BLAST_WINDOWS_URL
BLAST_BASE_URL
```

Use explicit `BLAST_LINUX_URL` and `BLAST_WINDOWS_URL` if you want fully pinned, reproducible dependency versions.

## Creating a release

Push a version tag:

```bash
git tag v0.6.0
git push origin v0.6.0
```

The workflow publishes:

```text
insilicoPCR-0.6.0-linux-x64.zip
insilicoPCR-0.6.0-linux-x64.sha256
insilicoPCR-0.6.0-windows-x64.zip
insilicoPCR-0.6.0-windows-x64.sha256
```

## Manual test build

From GitHub, open:

```text
Actions → CI and portable releases → Run workflow
```

This creates workflow artifacts without requiring a tag.
