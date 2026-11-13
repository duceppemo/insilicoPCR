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

From a local checkout of the main repository, run:

```bash
scripts/docs/publish-wiki.sh
```

The script clones the Wiki repository into `/tmp/insilicoPCR.wiki` (or reuses the clone already there), copies `docs/wiki/` over it, and commits and pushes when something changed. Set `WIKI_WORKDIR` to use another folder and `WIKI_REMOTE` to push to another repository.

The same steps by hand:

```bash
git clone https://github.com/duceppemo/insilicoPCR.wiki.git /tmp/insilicoPCR.wiki
rsync -av --delete --exclude='.git/' docs/wiki/ /tmp/insilicoPCR.wiki/
cd /tmp/insilicoPCR.wiki
git add .
git commit -m "Update Wiki documentation"
git push
```

Keep `--exclude='.git/'`: without it, `--delete` removes the clone's `.git` folder.

## Why keep a mirror in the main repository?

The mirror gives the documentation:

- normal code-review history
- a backup copy in the main repository
- easier synchronization with code changes
- a simple way to republish the Wiki after edits

The GitHub Wiki tab should remain the user-facing manual.
