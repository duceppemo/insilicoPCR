# Standard PCR vs qPCR

insilicoPCR decides per assay whether to apply standard PCR or qPCR/probe reporting, based on the primers defined in the primer FASTA file. Both kinds of assays can be tested in the same run (since version 0.8.0).

## Standard PCR assays

An assay with only forward and reverse primers:

```text
assayName-F
assayName-R
```

A positive consolidated report row is generated when the forward and reverse primers produce a valid amplicon (see [Amplicon Calling](Amplicon-Calling)).

## qPCR/probe assays

An assay with forward primer, reverse primer, and probe entries:

```text
assayName-F
assayName-R
assayName-P
```

A positive consolidated report row is generated only when the primer pair produces a valid amplicon **and** the probe is detected entirely within that amplicon. If the probe is defined in the primer file but produces no accepted hit inside the amplicon, the assay stays negative — the primer pair alone is not enough.

Because hydrolysis probes do not extend, the 3'-end rule that applies to primers is not applied to probes. A probe site only needs the Tm rule that every oligo passes (its Tm on the template reaches the assay's annealing temperature, `--anneal-temp` or its [`ta=`](Primer-FASTA-Format#annealing-temperature-optional), less `--tm-margin`: 55 °C by default) and to lie inside the product (see [Amplicon Calling](Amplicon-Calling#probes-are-different)). A probe designed hotter than its primers keeps sites with a mismatch or two above that minimum. Before 2.0, it had a mismatch budget of its own (`--probe-mismatches`, removed).

## Mixing both in one run

Mixed primer files are fully supported. The probe requirement follows each assay:

- assays **with** a `-P` entry use qPCR reporting (probe required);
- assays **without** a probe use standard PCR reporting.

The consolidated report has the probe columns (`Probe`, `ProbeLocation`, `ProbeSize`, `ProbeMismatches`, and since 2.0 `ProbeTm`) whatever the primer file holds (since 1.0; before, only when at least one assay in the file defined a probe); those columns are left empty on rows of probe-less assays.

> Versions before 0.8.0 switched the whole report to qPCR mode as soon as any `-P` primer was present, silently dropping all probe-less assays. If you need that behavior for some reason, run the assay types separately.
