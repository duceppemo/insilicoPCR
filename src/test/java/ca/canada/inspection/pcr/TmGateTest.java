package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.thermo.Thal;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmGateTest {

    /** Not its own reverse complement, so that a strand mixed up shows. */
    private static final String PRIMER = "GAAGTCGGACTACCTGGTGA";
    private static final String FLANK = "TTGCAACGTAGCATCGATGG";
    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of("g-F", PRIMER, "g-P", PRIMER));
    private static final AmpliconOptions STRICT = AmpliconOptions.withMismatches(0);

    // outfmt: qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq
    private static String line(String sseqid, int mismatch, int qstart, int qend, String qseq, int sstart, int send,
                               String sseq) {
        return String.join("\t", "contig1", sseqid, String.valueOf(qseq.length() - mismatch),
                String.valueOf(mismatch), "0", "1e-5", "40.1", "20", String.valueOf(qseq.length()),
                String.valueOf(qstart), String.valueOf(qend), qseq, String.valueOf(sstart), String.valueOf(send), sseq);
    }

    private static Optional<BlastResult> parse(String blastLine, String contig, AmpliconOptions options) {
        return BlastHitParser.parse(blastLine, PRIMERS, options, hit -> new TrimmedEnds.Template(contig));
    }

    private static AmpliconOptions gated(double minimumTm) {
        return STRICT.withTmGate(new TmGate(minimumTm, 0, 50, 3, 0.8, 250));
    }

    private static double tm(String blastLine, String contig) {
        BlastResult hit = parse(blastLine, contig, gated(0)).orElseThrow();
        return TmGate.at(60).siteTm(hit, new TrimmedEnds.Template(contig));
    }

    @Test
    void theOligoAnnealsToTheOtherStrandOfItsSite() {
        String reverse = Dna.reverseComplement(PRIMER);
        String plusContig = FLANK + PRIMER + FLANK;
        BlastResult plus = parse(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), plusContig, STRICT).orElseThrow();
        assertEquals(reverse, TmGate.strandUnder(plus, new TrimmedEnds.Template(plusContig)));

        String minusContig = FLANK + reverse + FLANK;
        BlastResult minus = parse(line("g-F", 0, 21, 40, reverse, 20, 1, reverse), minusContig, STRICT).orElseThrow();
        assertEquals(reverse, TmGate.strandUnder(minus, new TrimmedEnds.Template(minusContig)));
    }

    @Test
    void aPerfectSiteHasThePrimersTmOnItsComplement() {
        String contig = FLANK + PRIMER + FLANK;
        double expected = Thal.thal(PRIMER, Dna.reverseComplement(PRIMER),
                new Thal.Args(Thal.Type.ANY, Thal.MAX_LOOP, 50, 3, 0.8, 250, Thal.TEMP_KELVIN)).tm();
        assertEquals(expected, tm(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), contig), 1e-9);
        assertTrue(expected > 55 && expected < 70, String.valueOf(expected));
    }

    @Test
    void theTmDecidesInsteadOfTheMismatchCount() {
        // Two central mismatches: rejected at -m 0, accepted by a Tm gate the site reaches, rejected by one it does not.
        String site = PRIMER.substring(0, 8) + "TC" + PRIMER.substring(10);
        String contig = FLANK + site + FLANK;
        String blastLine = line("g-F", 2, 21, 40, site, 1, 20, PRIMER);
        assertTrue(parse(blastLine, contig, STRICT).isEmpty());
        double siteTm = tm(blastLine, contig);
        double perfect = tm(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), FLANK + PRIMER + FLANK);
        assertTrue(siteTm < perfect - 5, siteTm + " vs " + perfect);
        assertTrue(parse(blastLine, contig, gated(siteTm - 0.5)).isPresent());
        assertTrue(parse(blastLine, contig, gated(siteTm + 0.5)).isEmpty());
    }

    @Test
    void basesPastTheContigEndLowerTheTmInsteadOfCountingAsFree() {
        // The primer's first 8 bases hang off the contig start: --5p-truncation 8 accepts the 12 left at -m 0.
        String contig = PRIMER.substring(8) + FLANK + FLANK;
        String blastLine = line("g-F", 0, 1, 12, PRIMER.substring(8), 9, 20, PRIMER.substring(8));
        AmpliconOptions truncation8 = AmpliconOptions.builder().fivePrimeTruncation(8).build();
        assertTrue(parse(blastLine, contig, truncation8).isPresent());
        double cut = tm(blastLine, contig);
        double whole = tm(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), FLANK + PRIMER + FLANK);
        assertTrue(cut < whole - 15, cut + " vs " + whole);
        assertTrue(parse(blastLine, contig, truncation8.withTmGate(new TmGate(cut + 1, 0, 50, 3, 0.8, 250)))
                .isEmpty());
    }

    @Test
    void aPrimerMustStillPassThe3PrimeRule() {
        // The last base mismatched, BLAST stops one short: the duplex is stable, the 3' end does not pair.
        String site = PRIMER.substring(0, 19) + "T";
        String contig = FLANK + site + FLANK;
        String primerLine = line("g-F", 0, 21, 39, PRIMER.substring(0, 19), 1, 19, PRIMER.substring(0, 19));
        double siteTm = TmGate.at(60).siteTm(
                parse(primerLine, contig, AmpliconOptions.builder().threePrimePenalty(9).tmGate(TmGate.at(0)).build())
                        .orElseThrow(),
                new TrimmedEnds.Template(contig));
        assertTrue(siteTm > 40, String.valueOf(siteTm));
        assertTrue(parse(primerLine, contig, gated(siteTm - 5)).isEmpty(), "3' penalty 3 > 0");
        // A probe does not extend: its Tm alone decides.
        String probeLine = primerLine.replace("g-F", "g-P");
        assertTrue(parse(probeLine, contig, gated(siteTm - 5)).isPresent());
    }

    @Test
    void aSiteWithUnpairedFivePrimeBasesIsJudgedByItsTmLikeAnyOther() {
        // The first 5 bases mismatched, as the ITS5 primer's on G. pallida: BLAST aligns the other 15. No count
        // decides: the site's Tm does, for a primer and a probe alike.
        StringBuilder tail = new StringBuilder();
        for (char base : PRIMER.substring(0, 5).toCharArray()) {
            tail.append("ACGT".charAt(("CATG".indexOf(base))));
        }
        String contig = FLANK + tail + PRIMER.substring(5) + FLANK;
        String primerLine = line("g-F", 0, 26, 40, PRIMER.substring(5), 6, 20, PRIMER.substring(5));
        double site = tm(primerLine, contig);
        double perfect = tm(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), FLANK + PRIMER + FLANK);
        assertTrue(site < perfect - 3, site + " vs " + perfect);
        assertTrue(parse(primerLine, contig, gated(site - 0.1)).isPresent());
        assertTrue(parse(primerLine, contig, gated(site + 0.1)).isEmpty());
        assertTrue(parse(primerLine.replace("g-F", "g-P"), contig, gated(site - 0.1)).isPresent());
        assertTrue(parse(primerLine.replace("g-F", "g-P"), contig, gated(site + 0.1)).isEmpty());
    }

    @Test
    void theReadKeepsEachAcceptedSitesTmForTheReport(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        String contig = FLANK + PRIMER + FLANK;
        String blastLine = line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER);
        java.nio.file.Path searched = java.nio.file.Files.writeString(dir.resolve("c.fasta"), ">contig1\n" + contig + "\n");
        ca.canada.inspection.model.Hits into = new ca.canada.inspection.model.Hits();
        BlastHits.read(new java.io.BufferedReader(new java.io.StringReader(blastLine + "\n")), java.util.List.of(searched),
                into, PRIMERS, gated(40), java.util.function.UnaryOperator.identity(), ignored -> { });
        BlastResult hit = into.byOligo().get("g-F").getFirst();
        assertEquals(tm(blastLine, contig), into.siteTm(hit), 1e-9);
        assertEquals(into.siteTm(hit), into.filtered(kept -> true).siteTm(hit), 1e-9);
        assertTrue(Double.isNaN(new ca.canada.inspection.model.Hits().siteTm(hit)));
    }

    @Test
    void theLogListsEachOligosOwnTmAndThoseThatCannotReachTheMinimum() {
        PrimerPanel panel = PrimerPanel.of(new java.util.LinkedHashMap<>(Map.of("g-F", PRIMER)));
        double perfect = TmGate.at(60).perfectTm(PRIMER);
        java.util.List<String> ok = new TmGate(perfect + 4, 5, 50, 3, 0.8, 250).oligoLines(panel);
        assertEquals(String.format(java.util.Locale.ROOT, "  g-F %.1f °C", perfect), ok.get(1));
        java.util.List<String> low = new TmGate(perfect + 6, 5, 50, 3, 0.8, 250).oligoLines(panel);
        assertTrue(low.get(1).endsWith(": below the minimum, no site can reach it"), low.get(1));
    }

    @Test
    void theReadKeepsEachOligosBestRejectedSite(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // Two sites, one central mismatch and three: both under a gate above the better one; the better is kept.
        String one = PRIMER.substring(0, 9) + "A" + PRIMER.substring(10);
        String three = PRIMER.substring(0, 6) + "AAA" + PRIMER.substring(9);
        String fasta = ">c1\n" + FLANK + one + FLANK + "\n>c3\n" + FLANK + three + FLANK + "\n";
        String table = line("g-F", 3, 21, 40, three, 1, 20, PRIMER).replaceFirst("^contig1", "c3") + "\n"
                + line("g-F", 1, 21, 40, one, 1, 20, PRIMER).replaceFirst("^contig1", "c1") + "\n";
        java.nio.file.Path searched = java.nio.file.Files.writeString(dir.resolve("c.fasta"), fasta);
        ca.canada.inspection.model.Hits into = new ca.canada.inspection.model.Hits();
        BlastHits.read(new java.io.BufferedReader(new java.io.StringReader(table)), java.util.List.of(searched),
                into, PRIMERS, gated(80), java.util.function.UnaryOperator.identity(), ignored -> { });
        assertTrue(into.byOligo().isEmpty());
        ca.canada.inspection.model.NearMiss best = into.nearMisses().get("g-F");
        assertEquals("c1", best.hit().queryID());
        assertTrue(best.tm() < 80 && !best.failsThreePrimeRule(), String.valueOf(best.tm()));
    }

    @Test
    void withoutTheGateTheCountsDecideAsBefore() {
        assertFalse(STRICT.tmGated());
        assertTrue(STRICT.withTmGate(TmGate.at(60)).tmGated());
        assertEquals(STRICT, STRICT.withTmGate(TmGate.at(60)).withTmGate(null));
    }

    @Test
    void settingsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new TmGate(Double.NaN, 5, 50, 3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, -1, 50, 3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, 5, 50, -3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, 5, 50, 3, 0.8, 0));
        assertEquals(55.0, TmGate.DEFAULT.minimumTm());
        assertEquals(TmGate.at(60), TmGate.DEFAULT);
        assertEquals("site Tm at least 55 °C (60 - 5); 50 mM Na+, 3 mM Mg2+, 0.8 mM dNTP, 250 nM oligo",
                TmGate.at(60).describe());
    }

    @Test
    void theParallelReadKeepsTheHitsAndTheirOrder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // More hits than BlastHits holds back at once, sites of 0 to 3 mismatches: the gate keeps some, rejects others.
        AmpliconOptions options = gated(50);
        StringBuilder fasta = new StringBuilder();
        StringBuilder table = new StringBuilder();
        java.util.List<String> expected = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        for (int n = 0; n < 20_000; n++) {
            char[] site = PRIMER.toCharArray();
            int changes = random.nextInt(4);
            for (int k = 0; k < changes; k++) {
                int at = 2 + random.nextInt(13);
                site[at] = site[at] == 'A' ? 'C' : 'A';
            }
            String siteText = new String(site);
            int mismatches = 0;
            for (int k = 0; k < 20; k++) {
                mismatches += site[k] == PRIMER.charAt(k) ? 0 : 1;
            }
            String id = "contig_" + n;
            fasta.append('>').append(id).append('\n').append(FLANK).append(siteText).append(FLANK).append('\n');
            String hit = line("g-F", mismatches, 21, 40, siteText, 1, 20, PRIMER).replaceFirst("^contig1", id);
            table.append(hit).append('\n');
            String contig = FLANK + siteText + FLANK;
            BlastHitParser.parse(hit, PRIMERS, options, h -> new TrimmedEnds.Template(contig))
                    .ifPresent(accepted -> expected.add(accepted.queryID()));
        }
        java.nio.file.Path searched = java.nio.file.Files.writeString(dir.resolve("contigs.fasta"), fasta);
        ca.canada.inspection.model.Hits into = new ca.canada.inspection.model.Hits();
        java.util.List<String> acceptedLines = new java.util.ArrayList<>();
        BlastHits.read(new java.io.BufferedReader(new java.io.StringReader(table.toString())), java.util.List.of(searched),
                into, PRIMERS, options, java.util.function.UnaryOperator.identity(), acceptedLines::add);
        java.util.List<String> read = into.byOligo().getOrDefault("g-F", java.util.List.of()).stream()
                .map(BlastResult::queryID).toList();
        assertTrue(expected.size() > 1000 && expected.size() < 19_000, expected.size() + " accepted");
        assertEquals(expected, read);
        assertEquals(expected, acceptedLines.stream().map(l -> l.substring(0, l.indexOf('\t'))).toList());
    }
}
