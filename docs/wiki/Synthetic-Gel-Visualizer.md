# Synthetic Gel Visualizer

The synthetic gel visualizer displays consolidated PCR results as gel-like bands.

It is intended as a quick visual review tool for expected amplicon size patterns across samples and targets.

## What it uses

The visualizer reads consolidated report data produced by an insilicoPCR run.

It uses information such as:

- sample name
- target or assay name
- amplicon size
- report rows from the consolidated report

## When to use it

Use the synthetic gel visualizer to:

- inspect whether expected amplicon sizes are present
- compare band patterns across samples
- quickly spot unexpected amplicon sizes
- produce a gel-style image for review or discussion

## Opening the gel viewer

After a GUI run finishes, use the gel viewer button in the application.

The application can also open previous run folders or consolidated report TSV files when available.

## Interpretation

Each displayed band represents a predicted PCR amplicon from the consolidated report.

The visualizer is not a replacement for reviewing the detailed reports. It is a visual summary of the report data.

Always confirm important results in the detailed or consolidated report tables.

## Standard PCR vs qPCR mode

The gel visualizer depends on the consolidated report.

Because the consolidated report behaves differently in standard PCR and qPCR mode, the same limitation applies:

- standard PCR assays should be run together
- qPCR/probe assays should be run together
- do not mix standard PCR assays and qPCR/probe assays in one primer file

If a qPCR/probe run is used, a target is reported only when the forward primer, reverse primer, and probe are detected in the expected amplicon.

## Limitations

The synthetic gel is a visualization of predicted results. It does not simulate every physical property of a real electrophoresis gel.

It should be used for review and communication, not as the only source of interpretation.

## Recommended workflow

1. Run insilicoPCR.
2. Review the console/log output for errors.
3. Review detailed and consolidated reports.
4. Open the synthetic gel visualizer.
5. Use the gel view to compare amplicon patterns across samples.
6. Export or capture the gel view if needed for documentation.
