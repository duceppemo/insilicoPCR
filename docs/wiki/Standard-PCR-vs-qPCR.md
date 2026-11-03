# Standard PCR vs qPCR

insilicoPCR supports two reporting modes: standard PCR mode and qPCR/probe mode.

## Standard PCR mode

Use standard PCR mode when assays contain only forward and reverse primers.

Required primer IDs:

```text
assayName-F
assayName-R
```

A positive consolidated report row is generated when the forward and reverse primers produce a valid amplicon.

## qPCR/probe mode

Use qPCR mode when assays contain forward primer, reverse primer, and probe entries.

Required primer IDs:

```text
assayName-F
assayName-R
assayName-P
```

A positive consolidated report row is generated only when the forward primer, reverse primer, and probe are detected in the expected amplicon: the probe match must lie entirely between the two primers.

Because hydrolysis probes do not extend, the 3'-end mismatch rules that apply to primers are not applied to probes — a probe hit only needs to fit the total mismatch budget (see [Amplicon Calling](Amplicon-Calling)).

## Important limitation

Do not mix standard PCR assays and qPCR/probe assays in the same primer FASTA file.

If any primer ID contains `-P`, the consolidated report is generated in qPCR mode. In qPCR mode, assays without a probe are not reported as positive because the report expects probe support.

## Recommended practice

Use separate primer FASTA files:

```text
standard-pcr-assays.fasta
qpcr-probe-assays.fasta
```

Run them as separate analyses and compare the resulting reports afterward if needed.
