# Benchmark

The benchmark runs insilicoPCR on samples whose products are known, and scores what it reports. It answers two questions a unit test does not: **which products does the program miss, and what does it say when it does**, and **what does a change to a rule or to the search move**. Its results for a version are kept in the repository as a baseline, so a change shows as a difference of cases.

It drives the program as a user does: a command line in a process of its own, judged by the report files. It uses none of the program's classes but the Tm calculation (`thermo.Thal`, Primer3's, checked against `ntthal` by its own tests). The same cases therefore run against this build, a release unpacked in a folder, and a version whose inside is written anew.

The code is in `src/test/java/ca/canada/inspection/benchmark`; the baselines in `benchmark/baselines`.

## Where the answers come from

**Synthetic cases.** The benchmark writes the samples itself, from fixed seeds: random sequences, assays designed on them (primers of 20 to 24 bases melting at 60 to 66 °C, or "hot" ones of up to 28 bases at up to 72 °C), and each assay's product written into a sample, as it is or changed. The answer is known because the benchmark put it there.

When a primer site is changed (substitutions, an insertion, a deletion), whether the product is expected is decided by the acceptance rule as [Amplicon Calling](Amplicon-Calling) states it, applied to the planted bases alone (`Rule`): the oligo's Tm on the site must reach 55 °C (60 less the margin of 5), and a primer's last 5 bases must pair. That is worked out without any search, so the case measures the search: is a site the rule accepts found, and one it rejects left out?

**The Tm of a site depends on how many template bases are taken under the oligo.** The bases beside a site stack on the duplex's ends, and with one base more or less the thermodynamic alignment of a site with an indel can change: on one site with a 1-base deletion, the same primer melts at 54.1 °C, and at 51.2 °C with one template base more. The rule is therefore applied to every window from the oligo's footprint to the footprint with the 4 bases before it and the 2 after it. A site counts as accepted only when every window is 0.5 °C or more above the minimum, as rejected only when every window is as far below. Any other site is **borderline**: it is run and listed, and not scored, because it passes under one convention and fails under another without either being wrong.

**Real samples** have no written answer. They are given both ways, as an assembly and as the reads it was made from, and the assembly is the reference ([Concordance](#real-samples-concordance)).

## The synthetic suites

| Suite | What it measures | Cases |
|---|---|---|
| `sites` | One chromosome as an assembly; for each assay, a product with one or both primer sites changed. | Perfect sites; 1 to 5 substitutions outside the 3' window; 2 to 4 within the first six 5' bases; a mismatch on the 3' base, and within the 3' window; a 1-base deletion or insertion; hot primers with 2 to 5 substitutions or an indel; both sites changed. |
| `geometry` | Perfect sites, arranged every way the documentation describes. | Product sizes up to `--max-length` and beyond; the minus strand; two copies; a primer without a site; a product across two contigs; a product across the origin of a circle, marked and unmarked; a probe with and without its site; a product of one primer alone; a product of two assays of a reaction (`mix=`); a degenerate base; an assay at its own `ta=`; a gzipped and a lower-case FASTA; in the larger tiers, a product 1.2 Mb along a sequence and one across its millionth base. |
| `reads` | One chromosome and its assays, looked for in simulated read pairs and, as a control, in the chromosome itself as an assembly. | Products of 100 bp to 4.5 kb; sites with 2 to 4 substitutions the rule accepts; both sites changed so that no 17 bases in a row of a primer are within one difference of its site (the bait gap of [Limitations](Limitations#known-gaps)); a repeat of 150 to 600 bp inside a 1.5-kb product, with another copy 3% different elsewhere; a tandem repeat inside a product; assays the sample does not hold. |

The read pairs are simulated plainly (fragments drawn evenly, from either strand, substitution errors at 0.2%): 2 x 150 bp on 350-bp fragments at 40x and at 15x, and 2 x 100 bp on 165-bp fragments at 60x, whose reads overlap.

A sample and assay that no case names must give no product: a product there is scored `UNEXPECTED`.

### Tiers

| Tier | Size | Time (16 threads) | Run by |
|---|---|---|---|
| `ci` | 10 assays for each kind of site; 2 for each kind of product in reads, one sequencing run | about a minute and a half | the tests (`BenchmarkTest`), on every build |
| `standard` | the same sites; 3 for each kind in reads, three sequencing runs, products to 4.5 kb | about 6 minutes | by hand, before a change to the calling is merged |
| `full` | 60 and 12, in a chromosome of 3 Mb | long | by hand, for rates |

Every tier makes its own cases: a baseline compares only with a run of the same tier. Changing a seed, a count, or the order of the random draws in `Suites` changes every case of the tier, and its baseline must be written again.

## Results

Each case ends as one of:

| Result | Meaning |
|---|---|
| `FOUND` | The expected products, and no other |
| `ABSENT` | No product, where none is expected |
| `MISSED` | No product, where one is expected |
| `WRONG` | Products, but not the expected ones: another size, one too many, one too few. On an assembly every copy counts; on reads, copies of one size are one call |
| `UNEXPECTED` | A product where none is expected |
| `FAILED` | The run did not finish |

For a case that is not right, the scorecard also holds what the run **said** of that sample and assay: the status of `report_fastq_diagnostics.tsv`, `PRIMERS_AT_CONTIG_ENDS` from `QAlog.txt`, or the reason of `report_no_product.tsv` in a few words. A missed product the run names as "could not tell" is not the same failure as one it reports as a plain negative, and the summary counts them apart.

A run writes, into its `--out` folder (`target/benchmark` by default):

- `scorecard.tsv`: one line for each case (expected, observed, result, whether it is scored, what the run said, how the case was made);
- `summary.txt`: the scored cases counted by suite and category;
- `timings.tsv`: the seconds of each run of the program;
- `against-baseline.txt`, with `--baseline`;
- each run's log; with `--keep`, the input it wrote and the program's output folder too.

## Running it

```bash
# The quick tier against this build, compared with its baseline
scripts/benchmark/run.sh --baseline benchmark/baselines/ci.tsv

# The standard tier
scripts/benchmark/run.sh --tier standard --baseline benchmark/baselines/standard.tsv

# One suite
scripts/benchmark/run.sh --tier standard --suite sites

# A release, unpacked in a folder (its launcher is run), or a jar
scripts/benchmark/run.sh --tier standard --app ~/insilicoPCR-2.1.1/insilicoPCR-linux-x64
```

The script compiles the tests and starts `benchmark.Benchmark`; it needs a JDK of the project's Java version and the tools of `runtime/` (Git LFS). `MAVEN_OFFLINE=1` builds without the network. Exit code 0: done, and no case worse than the baseline when one is given; 1: a case is worse, or a run failed; 2: wrong command line.

## The baseline

`benchmark/baselines/<tier>.tsv` holds, for every case, its result and what the run said. Compared with it, a run's cases are:

- **worse**: right in the baseline and not now, or wrong in another way;
- **better**: not right in the baseline, right now;
- **changed**: as before, but the run says something else of it; or a case that is new, gone, or no longer scored.

`BenchmarkTest` fails on a case that is worse, and also on one that is better until the baseline says so: the baseline is then always what the program does, and its history in Git is the history of the calls.

After a change that is meant to move cases:

```bash
scripts/benchmark/run.sh --baseline benchmark/baselines/ci.tsv --write-baseline benchmark/baselines/ci.tsv
scripts/benchmark/run.sh --tier standard --baseline benchmark/baselines/standard.tsv --write-baseline benchmark/baselines/standard.tsv
git diff benchmark/baselines
```

Read the difference before committing it: it is the change's effect, case by case.

## Real samples: concordance

```bash
scripts/benchmark/run.sh --concordance samples.tsv --primers panel.fasta --design 2 --out /scratch/concordance
```

`samples.tsv` is tab-separated, with a header line; paths are taken from the file's own folder:

```text
sample	assembly	reads1	reads2
SRR642185	assemblies/SRR642185.fasta	reads/SRR642185_1.fastq.gz	reads/SRR642185_2.fastq.gz
```

The assemblies are run, then the reads, with the same assays. For each sample and assay, what the assembly shows is what the reads are expected to show; the cases are scored as above, with the sizes compared once each.

The assays are those of `--primers`, those designed with `--design n`, or both. A designed assay is a primer pair cut from a random place of a sample's own contigs: `n` assays in each sample for each product size of 300, 1,000, 2,000, 3,000 and 4,500 bp, with primers as the synthetic ones. It is scored in its own sample only when the assembly gives its one product, at the size it was designed for: a place that is not unique in the assembly is left out. Every assay is also run on the other samples, where the assembly decides as well.

An assembly is not the truth: a product its reads show and it lacks counts against the reads here (`UNEXPECTED`). Read such a case before believing either side.

`--baseline` and `--write-baseline` work as for the synthetic cases, with a file of your own: the samples are not in the repository.

## When the program changes

`Observed` is the one class that knows the program's output: it reads the products from `report.tsv`, and what the run said from `report_no_product.tsv`, `report_fastq_diagnostics.tsv` and `QAlog.txt`. A version that writes its results otherwise changes that class. `App` knows how the program is started (`-i`, `-p`, `-o`, `-t`). The cases, the rule and the scoring do not change with the program: that is what makes two versions comparable.
