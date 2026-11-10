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

Primer files in this convention can also be generated rather than written by hand: [primer-finder](https://github.com/duceppemo/primer-finder) designs selective (q)PCR assays against a group of genomes and writes them as `assays_qpcr.fasta` (the probe assays, with their `-P` entries) and `assays_pcr.fasta` (every assay, primers only), ready to pass to `-p`.

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

## Mixing PCR and qPCR assays

Standard PCR assays and qPCR/probe assays can be mixed in the same primer FASTA file (since version 0.8.0). The probe requirement is decided per assay:

- an assay **with** a `-P` probe is reported only when the probe is also found inside the amplicon;
- an assay **without** a probe is reported on its primer pair alone.

When at least one assay defines a probe, the consolidated report includes the probe columns; they are left empty on rows of probe-less assays.

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

The ID ends at the first blank (space or tab); anything after it on the header line is a description. Use underscores or hyphens inside the assay name, and keep the final role suffix as the last part of the ID.

Good:

```text
>speciesA_toxA-F
>speciesA_toxA-R
```

Also good, with a description:

```text
>speciesA_toxA-F forward primer, Smith 2010
```

Avoid:

```text
>speciesA toxA forward
>speciesA-toxA-forward
```

The first is read as the ID `speciesA`, which has no role suffix; the second ends in `-forward` instead of `-F`.

## Declaring product sizes (optional)

insilicoPCR reports **every** product a primer pair would amplify, whatever its size, as a real PCR does. An
assay that gives a product of the wrong size is still a positive PCR; whether the *test* is positive is for
you to decide from `AmpliconSize`. To make that reading easier, a header can say which sizes the assay is
known to give. The report then gets a `SizeCheck` column that labels each row. **Nothing is filtered or
hidden**: the rows are the same with or without the declaration.

```fasta
>BMros-F size=434
GGAAGTAAAAGTCGTAACAAGG
>BMros-R
AGCGCAGACATGCCGCAA

>NakhlaRosPCR-F size=103 other=453,960-980
TCTGTGCGTCGTTGAGC
>NakhlaRosPCR-R
CGCAGACATGCCGCAA
```

| Header word | Meaning | `SizeCheck` of a matching row |
|---|---|---|
| `size=` | Size of the product the assay is designed to give | `target` |
| `other=` | Sizes of other products the assay is known to give | `other` |
| | A size that matches neither | `unexpected` |
| | The assay declares nothing | *(empty)* |

Values are in bp:

| Form | Matches |
|---|---|
| `size=434` | 424 to 444 bp: the size give or take 10 bp, to take in length variants of one locus |
| `size=420-450` | exactly 420 to 450 bp; use a range when you want another tolerance |
| `size=159,163` | either size (each give or take 10 bp); sizes and ranges can be mixed, without spaces |

Rules:

- Put the words on the header of any one primer of the assay (`-F`, `-R` or `-P`), after the ID. Other words
  on the header are free text and are ignored.
- `size=` and `other=` can sit on different primers of the same assay. Giving the same word twice for one
  assay is accepted only when the values agree.
- A size that matches both `size=` and `other=` is labelled `target`.
- A value that cannot be read (`size=434bp`, `size=450-420`) stops the run with a message naming the primer.
- The column appears only when at least one assay declares a size, so reports of primer files without
  declarations are unchanged. The ranges used are recorded in `QAlog.txt`.
- The declaration does not change `--min-length`/`--max-length`: a product outside those limits is still not
  reported, declared or not.
