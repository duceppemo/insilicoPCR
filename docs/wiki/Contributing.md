# Contributing

Contributions should keep the project portable, reproducible, and maintainable.

## Before changing code

- Build the project with `./mvnw clean package`.
- Understand whether the change affects GUI, CLI, pipeline, reports, runtime layout, or packaging.
- Keep GUI and CLI behavior consistent where possible.

## Documentation expectations

When adding or changing features, update the relevant documentation:

- README only for project overview or quick-start changes
- Wiki pages for detailed user/developer documentation
- SVG diagrams if workflow, architecture, or release behavior changes
- screenshots when UI behavior changes

## Runtime assets

Do not commit generated release output, Maven output, IDE folders, JavaFX SDK folders, or JDK/JRE folders.

Curated BBMap and BLAST+ runtime assets are intentionally part of the repository layout.

## Recommended checks

```bash
./mvnw clean package
scripts/dev/verify-runtime-layout.sh
```

## Pull request focus

Prefer focused changes that are easy to review.

For large refactors, explain:

- why the change is needed
- what behavior should remain unchanged
- what files or packages were moved
- what testing was performed
