#!/usr/bin/env bash
# Writes src/test/resources/thermo/ntthal.tsv: Primer3's ntthal results that ThalTest checks the Java port
# (ca.canada.inspection.thermo.Thal) against. Needs Primer3 2.6.1's ntthal on PATH, e.g.:
#   conda create -p /tmp/p3 -c conda-forge -c bioconda primer3=2.6.1 && PATH=/tmp/p3/bin:$PATH
#
# Each case runs twice: as is, for dS, dH, dG and t (printed with %g, 6 significant digits) and the structure
# drawing; and with -r, for the Tm alone (printed with %f, 6 decimals). The cases: each primer of the Brucella
# and Globodera panels on its perfect complement, on sites with 1 to 3 mismatches (5' end, middle, 3' end),
# with 3 or 8 5' bases missing (a contig end), with a 1-base deletion or insertion (a bulge), inside flanking
# sequence; primer dimers between panel primers and with itself; each primer's hairpin; END1 and END2 runs;
# and a few odd inputs. All under 4 conditions: ntthal's defaults, a qPCR mix, 60 °C, and loops of 10 at most.
# thal_main.c reads its options by prefix (-d after -dv, -t, -n, -r, -a, -i by 2 characters, -maxloop by 8):
# the options used here are all read as intended.
set -euo pipefail
out=${1:-src/test/resources/thermo/ntthal.tsv}
command -v ntthal > /dev/null || { echo "ntthal not on PATH" >&2; exit 1; }
python3 -I - "$out" <<'EOF'
import subprocess, sys

out = sys.argv[1]
primers = """
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
""".split()
conditions = ["", "-mv 50 -dv 3 -n 0.8 -d 250", "-t 60", "-maxloop 10"]
SUB = {"A": "C", "C": "A", "G": "T", "T": "G"}
COMP = {"A": "T", "C": "G", "G": "C", "T": "A", "N": "N"}
LEFT, RIGHT = "GATTACAGTCCA", "TGGACTGTAATC"


def rc(s):
    return "".join(COMP[c] for c in reversed(s))


def mutate(s, positions):
    s = list(s)
    for p in positions:
        s[p] = SUB[s[p]]
    return "".join(s)


def site_cases(p):
    n, mid = len(p), len(p) // 2
    sites = {
        "perfect": p,
        "mm5": mutate(p, [0]),
        "mmMid": mutate(p, [mid]),
        "mm3": mutate(p, [n - 1]),
        "mm3second": mutate(p, [n - 2]),
        "mm2": mutate(p, [0, mid]),
        "mm3x": mutate(p, [0, mid, n - 1]),
        "trunc3": p[3:],
        "trunc8": p[8:],
        "deletion": p[:mid] + p[mid + 1:],
        "insertion": p[:mid] + "A" + p[mid:],
        "flanked": LEFT + p + RIGHT,
        "flankedMm": LEFT + mutate(p, [1, mid + 1]) + RIGHT,
    }
    return [(name, p, rc(site)) for name, site in sites.items()]


cases = []  # (kind, s1, s2, mode)
for k, p in enumerate(primers):
    for name, s1, s2 in site_cases(p):
        cases.append((name, s1, s2, "ANY"))
    for name in ("perfect", "mm3", "trunc3"):
        s1, s2 = next((a, b) for n, a, b in site_cases(p) if n == name)
        cases.append((name, s1, s2, "END1"))
        cases.append((name, s1, s2, "END2"))
    other = primers[(k + 1) % len(primers)]
    cases.append(("dimer", p, other, "ANY"))
    cases.append(("dimer", p, other, "END1"))
    cases.append(("dimer", p, other, "END2"))
    cases.append(("selfDimer", p, p, "ANY"))
    cases.append(("hairpin", p, "", "HAIRPIN"))
# odd inputs: self-complementary, N, lower case, very short, digits, a long template, both too long
odd = [
    ("palindrome", "GAATTCGAATTC", "GAATTCGAATTC", "ANY"),
    ("palindromeShort", "GCGC", "GCGC", "ANY"),
    ("withN", "ACGGACACANGCCCGCTA", rc("ACGGACACATGCCCGCTA"), "ANY"),
    ("lowerCase", "acggacacatgcccgcta", rc("ACGGACACATGCCCGCTA").lower(), "ANY"),
    ("twoBases", "GC", "GC", "ANY"),
    ("digits", "0123", "0123", "ANY"),
    ("noPair", "AAAAAAAAAA", "AAAAAAAAAA", "ANY"),
    ("longTemplate", "ACGGACACATGCCCGCTA", rc("T" * 40 + "ACGGACACATGCCCGCTA" + "G" * 40), "ANY"),
    ("bothTooLong", "A" * 61, "T" * 61, "ANY"),
    ("hairpinLoop3", "GGGGAAACCCC", "", "HAIRPIN"),
    ("hairpinLoop4", "GGGGAAAACCCC", "", "HAIRPIN"),
    ("hairpinLong", "GCGCATGCGCAAAAAAGCGCATGCGC", "", "HAIRPIN"),
    ("hairpinNone", "AAAAAAAAAAAA", "", "HAIRPIN"),
]
# hairpins whose stems hold a bulge, an internal loop or a mismatch; dimers with longer bulges and loops
X, Y = "GCATCGC", "GCGTCAG"
for name, left, right in [("hairpinBulge1", X + "A" + Y, rc(Y) + rc(X)),
                          ("hairpinBulge3", X + "AAT" + Y, rc(Y) + rc(X)),
                          ("hairpinInternal2x2", X + "AA" + Y, rc(Y) + "CC" + rc(X)),
                          ("hairpinInternal1x3", X + "A" + Y, rc(Y) + "CTC" + rc(X)),
                          ("hairpinMismatch1x1", X + "A" + Y, rc(Y) + "A" + rc(X))]:
    for loop in ("TTTT", "GAAA", "TTTTTTTT"):
        cases.append((name + loop, left + loop + right, "", "HAIRPIN"))
for p in primers[:12]:
    mid = len(p) // 2
    cases.append(("bulge3", p, rc(p[:mid] + "TTG" + p[mid:]), "ANY"))
    cases.append(("internal2x2", p, rc(p[:mid] + mutate(p[mid:mid + 2], [0, 1]) + p[mid + 2:]), "ANY"))
    cases.append(("internal2x4", p, rc(p[:mid] + "TATA" + p[mid + 2:]), "ANY"))
    cases.append(("bulge3", p, rc(p[:mid] + "TTG" + p[mid:]), "END1"))
cases.extend(odd)


def ntthal(args):
    run = subprocess.run(["ntthal"] + args, capture_output=True, text=True)
    return run.returncode, run.stdout


rows = []
for cond in conditions:
    copt = cond.split() if cond else []
    for kind, s1, s2, mode in cases:
        seqs = ["-s1", s1] + (["-s2", s2] if mode != "HAIRPIN" else [])
        args = copt + ["-a", mode] + seqs
        code, general = ntthal(args)
        code_r, fast = ntthal(["-r"] + args)
        if code != 0 or code_r != 0:
            rows.append([kind, mode, cond, s1, s2, "ERROR", "", "", "", "", ""])
            continue
        tm = fast.strip()
        values = {"dS": "NA", "dH": "NA", "dG": "NA", "t": "NA"}
        lines = []
        for line in general.split("\n"):
            if line.startswith("Calculated thermodynamical parameters"):
                for field in line.split("\t"):
                    if " = " in field:
                        name, value = field.split(" = ")
                        values[name] = value
            elif line.startswith("SEQ\t") or line.startswith("STR\t"):
                lines.append(line[4:])
        rows.append([kind, mode, cond, s1, s2, tm, values["dS"], values["dH"], values["dG"], values["t"],
                     "|".join(lines)])

with open(out, "w") as f:
    f.write("kind\tmode\targs\ts1\ts2\ttm\tdS\tdH\tdG\tt\tstructure\n")
    for r in rows:
        f.write("\t".join(r) + "\n")
print(f"wrote {len(rows)} rows to {out}")
EOF
