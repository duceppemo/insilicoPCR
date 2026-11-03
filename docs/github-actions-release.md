# GitHub Actions CI/CD for portable releases

> Parts of this page previously described an older flow where CI downloaded BBMap/BLAST at build time. That is no longer the case: the runtime tools are committed under `runtime/` (via Git LFS) and CI only validates their presence. See [release-process.md](release-process.md) and the Wiki [Release Pipeline](https://github.com/duceppemo/insilicoPCR/wiki/Release-Pipeline) page.

The workflow is defined in `.github/workflows/ci-portable-release.yml` and runs on:

- pushes to `master` and pull requests: runs the unit tests with coverage (uploaded to Codecov), builds the Linux and Windows portable ZIPs, and stores them as workflow artifacts
- manual `workflow_dispatch` with a custom version string
- version tags matching `v*`: additionally publishes a GitHub Release with the ZIPs and `.sha256` checksum files (archived on Zenodo with a DOI)

## Creating a release

```bash
git tag v0.7.0
git push origin v0.7.0
```

The workflow publishes:

```text
insilicoPCR-0.7.0-linux-x64.zip
insilicoPCR-0.7.0-linux-x64.sha256
insilicoPCR-0.7.0-windows-x64.zip
insilicoPCR-0.7.0-windows-x64.sha256
```

Only portable ZIPs are published — no `.exe`, `.msi`, `.dmg`, `.deb`, or `.rpm` installers.
