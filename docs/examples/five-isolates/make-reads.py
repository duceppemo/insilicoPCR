#!/usr/bin/env python3
"""Simulated paired reads for the five-isolate example.

Writes reads/isolate_01_R1.fastq.gz and reads/isolate_01_R2.fastq.gz next to this
script: 2 x 150 bases from fragments of 350 bases placed at random along
genomes/isolate_01.fasta, about 20 times coverage, without sequencing errors. The
random numbers are seeded and the gzip header carries no time, so the files come out
the same on every run. Python 3 standard library only.
"""
import gzip
import random
from pathlib import Path

HERE = Path(__file__).resolve().parent
READ, FRAGMENT, COVERAGE = 150, 350, 20
COMPLEMENT = str.maketrans("ACGTacgt", "TGCAtgca")


def reverse_complement(sequence):
    return sequence.translate(COMPLEMENT)[::-1]


def main():
    lines = (HERE / "genomes" / "isolate_01.fasta").read_text().splitlines()
    genome = "".join(line.strip() for line in lines if not line.startswith(">")).upper()
    pairs = len(genome) * COVERAGE // (2 * READ)
    rng = random.Random(1)
    quality = "I" * READ
    forward, reverse = [], []
    for number in range(1, pairs + 1):
        start = rng.randrange(0, len(genome) - FRAGMENT + 1)
        fragment = genome[start:start + FRAGMENT]
        if rng.random() < 0.5:
            fragment = reverse_complement(fragment)
        name = f"@isolate_01.{number}"
        forward.append(f"{name}/1\n{fragment[:READ]}\n+\n{quality}\n")
        reverse.append(f"{name}/2\n{reverse_complement(fragment[-READ:])}\n+\n{quality}\n")
    out = HERE / "reads"
    out.mkdir(exist_ok=True)
    for mate, records in (("R1", forward), ("R2", reverse)):
        with open(out / f"isolate_01_{mate}.fastq.gz", "wb") as raw:
            with gzip.GzipFile(fileobj=raw, mode="wb", mtime=0, filename="") as zipped:
                zipped.write("".join(records).encode("ascii"))
    print(f"{pairs} read pairs of {READ} bases from {len(genome)} bases")


if __name__ == "__main__":
    main()
