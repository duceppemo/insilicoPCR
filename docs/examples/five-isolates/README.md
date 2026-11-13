# Five-isolate example

A small synthetic data set: five made-up genomes of 4 to 11 kb and two assays that both declare their product size. It runs in about a second, and it is the run pictured on the wiki [Reports](https://github.com/duceppemo/insilicoPCR/wiki/Reports) page.

| File | What it is |
|---|---|
| [`genomes/`](genomes) | `isolate_01.fasta` to `isolate_05.fasta`, one contig each. The sequences are random; the primer sites were written into them |
| [`primers.fasta`](primers.fasta) | `toxA` (standard PCR, 312 bp) and `stxQ` (qPCR with a probe, 118 bp) |
| [`report.tsv`](report.tsv) | Consolidated report of the run below, with insilicoPCR 0.12.1 and default settings |
| [`reads/`](reads) | Simulated paired reads of `isolate_01` (2 x 150 bases, about 20 times coverage, no errors), for a FASTQ-mode run that takes a few seconds |
| [`make-reads.py`](make-reads.py) | The script that wrote them; it writes the same files again on every run |

What the samples show:

| Sample | `stxQ` | `toxA` |
|---|---|---|
| `isolate_01`, `isolate_02` | 118 bp, target | 312 bp, target |
| `isolate_03` | no product | 312 bp, target |
| `isolate_04` | 118 bp, target | 583 bp, an unexpected size |
| `isolate_05` | no product | no product |

## Run it

From an extracted portable release (Linux shown; on Windows use `runtime\windows\jdk\bin\java.exe`):

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar \
  -i docs/examples/five-isolates/genomes \
  -p docs/examples/five-isolates/primers.fasta \
  -o five-isolates-results
```

`five-isolates-results/consolidated_report/report.tsv` is then the same as [`report.tsv`](report.tsv) here.

## FASTQ mode

```bash
./runtime/linux/jdk/bin/java -jar insilicoPCR.jar \
  -i docs/examples/five-isolates/reads \
  -p docs/examples/five-isolates/primers.fasta \
  -o five-isolates-reads-results
```

The reads are baited, merged and assembled, and the report has the same two products as `isolate_01` above: `stxQ` at 118 bp and `toxA` at 312 bp. `Contig` and `GenomeLocation` then refer to the contigs of the mini-assembly, not to the genome.

## Uses

- `scripts/ci/smoke-test.sh` runs every portable ZIP built by CI on these files, on Linux and on Windows: the genomes, compared with [`report.tsv`](report.tsv), and the reads, checked for the two products.
- The picture `docs/assets/report/html-report.png` is the top of `report.html` from this run (a browser window 1000 px wide at twice the pixel density, cut above **Run details**).
