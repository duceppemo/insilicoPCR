#!/usr/bin/env bash
# Writes src/test/resources/thermo/oligotm.tsv: Primer3's oligotm results that OligoTmTest checks the Java
# port against. Needs Primer3 2.6.1's oligotm on PATH, e.g.:
#   conda create -p /tmp/p3 -c conda-forge -c bioconda primer3=2.6.1 && PATH=/tmp/p3/bin:$PATH
# The oligotm program reads -dm, -df and -fo as -d (it compares 2 characters), so DMSO and formamide are not
# in the fixture.
set -euo pipefail
out=${1:-src/test/resources/thermo/oligotm.tsv}
seqs=(
  # Brucella qPCR panel (Hoover 2026) and Globodera species panel primers and probes
  CGCGCATGATACCACTTCT AGGTGCGGTCGATAAACTTCTGGAAC GTGTTCGCGCATGTTGAAG CATACAACCGCCATGGGT
  ACAGACGGTCGGCCATTCCA CTCTCGCGCCGTATCAG ATGGTCGGCAACGAGTG CTGCCTGTTTGGCCTGATAGT GCGGCGAAGGAACAAGT
  CCATAAAGCGCAAAGATCACAA CACCGTAGAGAATGCCCACTGCAT AACCCTGTCGTCATGAAGTG
  AAGGAGTTGTGGTCCAGACG ACAACAGCAATCGTCGAG ACATGAGTGTTGGGGTGTAAC ACGGACACATGCCCGCTA
  AGCACCTAAACTTAAAACATAATGAAAATG AGCGCAGACATGCCGCAA AGCGCAGATATGCCGCG AGGCATTGCTTGAGCGAACGGA
  AGTCGGCTTACAACACAT CCAGCAGATTCTTTTTTACATCA CCGCTATGTTTGGGC CGCAGACATGCCGCAA
  CGCAGATATGCTAACATGGAGTGTAG CGCTCGTCGGCCTCCTCCTC CGTCTTTTTTACCATATTTCCAGAA GAAGGCAATCTGTGTTCGGG
  GGAAGTAAAAGTCGTAACAAGG GTGTCTCTAATTTGCCATT GTTCGATTAGTCTTTCGCCCCT TCAAATTTGTCGTTGGGATGGAAGG
  TCGTCCACTTCGGCAAAATCA TCTGTGCGTCGTTGAGC TGACGAGGAACAGTACAAAG TTAAGCATATCATTTAGCGGAGGAA
  TTTGTGCGATTTTGCCTAT TTTTTTGGGCATCCTGAGGTTTAT
  # self-complementary (symmetry correction), N, very short, long, and illegal characters
  GAATTC GCGCATGCGC ACGTACGTACGT AAAAATTTTT ACGTNACGTACGT NNNNNNNNNN GC A
  ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT ACGU ACGTRACGT
)
conds=(
  ""
  "-mv 50 -dv 3 -n 0.8 -d 250"
  "-mv 100 -dv 0 -n 0 -d 500"
  "-dv 1 -n 2"
  "-sc 0"
  "-sc 0 -tp 0"
  "-tp 0"
  "-sc 2"
  "-sc 2 -dv 0"
  "-sc 2 -mv 0 -dv 3"
  "-sc 2 -mv 10 -dv 6 -n 0.2"
)
{
  printf 'sequence\targs\ttm\n'
  for c in "${conds[@]}"; do
    for s in "${seqs[@]}"; do
      # shellcheck disable=SC2086
      tm=$(oligotm $c "$s" 2>/dev/null) || tm=ERROR
      printf '%s\t%s\t%s\n' "$s" "$c" "$tm"
    done
  done
} > "$out"
echo "wrote $(($(wc -l < "$out") - 1)) rows to $out"
