# Primer FASTA Format

insilicoPCR primer definitions must be provided as a FASTA file.

Each FASTA record ID must end with a role suffix.

| Suffix | Meaning |
|---|---|
| `-F` | Forward primer |
| `-R` | Reverse primer |
| `-P` | Probe |

The text before the final suffix is treated as the assay, gene, or target name.

For example:

```text
targetA-F
targetA-R
targetA-P
```

all belong to the same target, `targetA`.

## Standard PCR mode

Use standard PCR mode when every assay has only a forward and reverse primer.

```fasta
>toxA-F
ATGCGTACGTTAGCTAGCTA
>toxA-R
TCGATCGATACGCGTACGTA

>toxB-F
GCTAGCTAGGATCGATCGAA
>toxB-R
TTCGATCGATCCTAGCTAGC
```

In standard PCR mode, a target is reported when the forward and reverse primers produce a valid amplicon.

## qPCR / probe mode

Use qPCR mode when every assay includes a probe.

```fasta
>toxA-F
ATGCGTACGTTAGCTAGCTA
>toxA-R
TCGATCGATACGCGTACGTA
>toxA-P
ACGTTAGCTAGCTACGTA

>toxB-F
GCTAGCTAGGATCGATCGAA
>toxB-R
TTCGATCGATCCTAGCTAGC
>toxB-P
CGATCGATCCTAGCTAGC
```

In qPCR mode, a target is reported as positive only when the forward primer, reverse primer, and probe are detected in the expected amplicon.

## Do not mix PCR and qPCR assays

Do not mix standard PCR assays and qPCR/probe assays in one primer FASTA file.

If any primer ID includes a `-P` probe, the consolidated report is generated in qPCR mode. In qPCR mode, assays without probes are not reported as positive in the consolidated report because the program expects probe support.

Use separate primer files:

```text
standard-pcr-primers.fasta
qpcr-probe-primers.fasta
```

Run them as separate analyses.

## Degenerate bases

Primer sequences may contain standard IUPAC degenerate bases.

Supported bases include:

| Code | Bases |
|---|---|
| `R` | A or G |
| `Y` | C or T |
| `S` | C or G |
| `W` | A or T |
| `K` | G or T |
| `M` | A or C |
| `B` | C, G, or T |
| `D` | A, G, or T |
| `H` | A, C, or T |
| `V` | A, C, or G |
| `N` | A, C, G, or T |

Sequences should not include spaces, fluorophore names, quencher names, or non-IUPAC sequence characters. If a probe has labels such as FAM or BHQ1, remove those labels and keep only the nucleotide sequence.

## Recommended naming

Use simple names that make the assay clear:

```fasta
>geneName-F
ACTG...
>geneName-R
CAGT...
```

Avoid spaces in FASTA IDs. If needed, use underscores or hyphens inside the assay name, but keep the final role suffix as the last part of the ID.

Good:

```text
>speciesA_toxA-F
>speciesA_toxA-R
```

Avoid:

```text
>speciesA toxA forward
>speciesA-toxA-forward
```
