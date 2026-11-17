# Primer FASTA Format

insilicoPCR primer definitions must be provided as a FASTA file.

Each FASTA record ID must end with a role suffix.

| Suffix | Meaning |
|---|---|
| `-F` | Forward primer |
| `-R` | Reverse primer |
| `-P` | Probe |

The text before the final suffix is treated as the assay, gene, or target name. The role letter ends the ID, or comes before an underscore (`toxB-F_2`, a second forward primer): an ID such as `stxQ-FAM`, a probe named for its dye, `toxA-Rev`, `toxA-F2` or a lower-case role letter, `toxA-f`, is refused with a message, since 0.15.3 (before, the letter after the last `-` was read, so `stxQ-FAM` was a second forward primer, and its assay lost its probe). An oligo must have at least 7 bases, BLAST's shortest word: a shorter one never has a hit, and is refused.

For example:

```text
targetA-F
targetA-R
targetA-P
```

all belong to the same target, `targetA`.

Every assay needs a forward (`-F`) and a reverse (`-R`) primer; the probe is optional. An assay missing either could never give a product, so the run stops at once and names what is missing, for example `Every assay needs a forward (-F) and a reverse (-R) primer, but toxA-R is missing from the primer file primers.fasta`. (Before 0.15.1, such an assay was left out of the reports without a word, and looked negative in every sample.)

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

The consolidated report has the probe columns whatever the primer file holds (since 1.0; before, only when at least one assay defined a probe); they are left empty on rows of probe-less assays.

## Degenerate bases

Primer sequences may contain standard IUPAC degenerate bases.

Supported bases include:

| Code | Bases |
|---|---|
| `R` | A or G |
| `Y` | T or C |
| `S` | G or C |
| `W` | A or T |
| `K` | T or G |
| `M` | A or C |
| `B` | T, G, or C |
| `D` | A, T, or G |
| `H` | A, T, or C |
| `V` | A, C, or G |
| `N` | A, T, C, or G |

Sequences should not include spaces, fluorophore names, quencher names, or non-IUPAC sequence characters. If a probe has labels such as FAM or BHQ1, remove those labels and keep only the nucleotide sequence.

Each degenerate oligo is searched as every sequence it stands for, up to 65,536 (eight `N`). A more degenerate one stops the run with `Primer <ID> is too degenerate`; an inosine written as `N` counts as four bases.

The run also stops on a record without a sequence (`Primer toxA-P has no sequence in the primer file ...`) and on an ID used twice (`Primer ID toxB-F appears twice ...`; give each its own ID, such as `toxB-F_1`, `toxB-F_2`). Before 0.15.2 both were silent: a probe without a sequence turned its qPCR assay into a PCR assay reported on its primers alone, and of two records with one ID only the last was searched. A header with no ID, a `>` with nothing after it, stops the run with `The primer file ... has a header with no ID` since 1.0 (before, with a message about the naming convention and a blank where the ID goes).

The sequences of a degenerate primer are named by its ID followed by `_0`, `_1`, and so on, and the report names the variants that make a product by these IDs. They are numbered in this order: the degenerate base nearest the 5' end changes slowest and the one nearest the 3' end fastest, each code's bases taken in the order of the table above. `toxA-F` = `TTGRCAAY` gives `toxA-F_0` `TTGACAAT`, `toxA-F_1` `TTGACAAC`, `toxA-F_2` `TTGGCAAT` and `toxA-F_3` `TTGGCAAC`. A primer without a degenerate base keeps its ID. No other primer of the file may have one of those IDs (`X-F_1` beside a degenerate `X-F`): the run stops with `Primer X-F is degenerate: its sequences are named X-F_0 to ...`. It also stops on a file with no primer, or with a sequence before its first `>` line. Before 0.15.2 the first replaced a sequence of the degenerate primer, and the others left the run with no primer to search, without a word.

The primer file may have any name, and may be gzipped (`primers.fasta.gz`), in the application as on the command line. Before 0.15.2 the application refused a primer file not named like a FASTA file (`primers.txt`), and accepted a gzipped one that the run then could not read.

## Recommended naming

Use simple names that make the assay clear:

```fasta
>geneName-F
ACTG...
>geneName-R
CAGT...
```

The ID ends at the first blank (space or tab); anything after it on the header line is a description. Use underscores or hyphens inside the assay name, and keep the final role suffix as the last part of the ID. An ID holds ASCII letters, digits and punctuation only: BLAST writes any other character (`é`, `Δ`) as `#` in its database, and a primer so renamed found nothing, without a word (before 0.15.3). Such an ID is refused, naming the character; the description after the ID can hold any.

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
known to give. The report's `SizeCheck` column then labels each of its rows. **Nothing is filtered or
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

- Put the words on the header of any one primer of the assay (`-F`, `-R` or `-P`), after the ID. The keys
  `size`, `other` and `mix` are read in any letter case (`Size=434` is `size=434`). Other words on the header
  are free text and are ignored; since 1.0, a word of the form `key=value` with another key (`Tm=60`, a mistyped
  `sise=420`) is named in a warning (`Warning: the header of primer ... holds Tm=60, which insilicoPCR does not
  read ...`) and ignored: a later release might read it, and a mistyped key was ignored without a word. Write
  such a note otherwise (`Tm 60`).
- `size=` and `other=` can sit on different primers of the same assay. Giving the same word twice for one
  assay is accepted only when the values agree.
- A size that matches both `size=` and `other=` is labelled `target`.
- An assay that declares `other=` must declare `size=` too: without a target, none of its products could ever be positive. Such a primer file is refused (since 0.15.3).
- A value that cannot be read (`size=434bp`, `size=450-420`) stops the run with a message naming the primer.
- The column is in every report since 1.0, empty for the assays that declare nothing (before, it appeared
  only when at least one assay declared a size). The ranges used are recorded in `QAlog.txt`.
- The declaration does not change `--min-length`/`--max-length`: a product outside those limits is still not
  reported, declared or not.


## Assays in one reaction (multiplex)

A primer file can hold assays run on their own and assays run together in one tube. To say that assays share a reaction, give them the same `mix=<name>` after a primer's ID, on any one of each assay's primers:

```text
>toxA-F mix=panel1
TTTCCTCATGCAATTCAAAACC
>toxA-R
ATGTCCGTAATGTAGGCGAAA
>stxQ-F mix=panel1 size=118
...
>its-F size=434
...
```

The primers of the assays of one group then also pair with each other, any role with any role, as they would in the tube: such a product is reported under the two assays' names joined by `+` (`stxQ+toxA`), tagged **two assays** in the HTML report (see [Amplicon Calling](Amplicon-Calling#stage-2-amplicon-calling)). `its`, without `mix=`, is run on its own, as every assay of a file without `mix=` is. An assay is in one reaction: two different `mix=` names for one assay are refused, and so is an assay named as a product of two assays of one reaction (an assay `a+b` beside `a` and `b` sharing `mix=`). A primer two assays of a reaction share can be written under each one's ID: the product it makes is reported once (see [Amplicon Calling](Amplicon-Calling#stage-2-amplicon-calling)). `QAlog.txt` lists the groups. Since 0.15.3. No assay ID may hold `+`, since 1.0, as the report joins two assays' names with it (`Assay ... has '+' in its ID ...`): before, only an assay that shared a reaction was refused (`a` with `b+c`, and `a+b` with `c`, would both be reported as `a+b+c`), and an assay `a+b` run on its own still read as a product of two assays.
