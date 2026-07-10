# Publishing to the GitHub Wiki

The files in `docs/wiki/` are a source-controlled mirror of the GitHub Wiki.

GitHub Wikis are stored in a separate Git repository named:

```text
insilicoPCR.wiki
```

For this project, the Wiki repository URL is:

```text
https://github.com/duceppemo/insilicoPCR.wiki.git
```

## Initialize the Wiki

If the Wiki repository does not exist yet, open the repository's **Wiki** tab in GitHub and create the first page. This initializes the separate Wiki Git repository.

## Publish the mirrored pages

From a local checkout of the main repository:

```bash
git clone https://github.com/duceppemo/insilicoPCR.wiki.git /tmp/insilicoPCR.wiki
rsync -av --delete docs/wiki/ /tmp/insilicoPCR.wiki/
cd /tmp/insilicoPCR.wiki
git add .
git commit -m "Update Wiki documentation"
git push
```

## Why keep a mirror in the main repository?

The mirror gives the documentation:

- normal code-review history
- a backup copy in the main repository
- easier synchronization with code changes
- a simple way to republish the Wiki after edits

The GitHub Wiki tab should remain the user-facing manual.
