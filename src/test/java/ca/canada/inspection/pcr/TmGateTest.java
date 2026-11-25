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
        return STRICT.withTmGate(new TmGate(minimumTm, 0, 0, 50, 3, 0.8, 250));
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
        assertTrue(parse(blastLine, contig, truncation8.withTmGate(new TmGate(cut + 1, 0, 0, 50, 3, 0.8, 250)))
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
    void aPrimerSiteNeedsOnlyToStartTheProductWhileAProbeSiteMustBindInEveryCycle() {
        // The first 5 bases mismatched, as the ITS5 primer's on G. pallida: BLAST aligns the other 15.
        StringBuilder tail = new StringBuilder();
        for (char base : PRIMER.substring(0, 5).toCharArray()) {
            tail.append("ACGT".charAt(("CATG".indexOf(base))));
        }
        String contig = FLANK + tail + PRIMER.substring(5) + FLANK;
        String primerLine = line("g-F", 0, 26, 40, PRIMER.substring(5), 6, 20, PRIMER.substring(5));
        double site = tm(primerLine, contig);
        double perfect = tm(line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER), FLANK + PRIMER + FLANK);
        assertTrue(site < perfect - 3, site + " vs " + perfect);
        // Annealing 7 °C above the site's Tm: within --start-margin 10, not within --tm-margin 5.
        AmpliconOptions options = STRICT.withTmGate(new TmGate(site + 7, 5, 10, 50, 3, 0.8, 250));
        assertTrue(parse(primerLine, contig, options).isPresent());
        assertTrue(parse(primerLine.replace("g-F", "g-P"), contig, options).isEmpty());
        assertTrue(parse(primerLine, contig, STRICT.withTmGate(new TmGate(site + 7, 5, 6, 50, 3, 0.8, 250)))
                .isEmpty());
    }

    @Test
    void aPrimerMustCopyTheProductAtTheAnnealingTemperature() {
        // A perfect site starts the product well within --start-margin, but the primer's own Tm is 3 °C short.
        String contig = FLANK + PRIMER + FLANK;
        String blastLine = line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER);
        double perfect = tm(blastLine, contig);
        assertTrue(parse(blastLine, contig, STRICT.withTmGate(new TmGate(perfect + 3, 0, 20, 50, 3, 0.8, 250)))
                .isEmpty());
        assertTrue(parse(blastLine, contig, STRICT.withTmGate(new TmGate(perfect + 3, 4, 20, 50, 3, 0.8, 250)))
                .isPresent());
    }

    @Test
    void anAssayThatDeclaresItsAnnealingTemperatureIsJudgedAtIt() {
        String contig = FLANK + PRIMER + FLANK;
        String blastLine = line("g-F", 0, 21, 40, PRIMER, 1, 20, PRIMER);
        double perfect = tm(blastLine, contig);
        // --anneal-temp above the site's Tm: rejected; its assay's ta= below it: accepted, the other assays unchanged.
        TmGate gate = new TmGate(perfect + 3, 0, 0, 50, 3, 0.8, 250);
        assertTrue(parse(blastLine, contig, STRICT.withTmGate(gate)).isEmpty());
        TmGate own = gate.withAssayTemps(AnnealTemps.of(Map.of("g", perfect - 1)));
        assertTrue(parse(blastLine, contig, STRICT.withTmGate(own)).isPresent());
        assertEquals(perfect - 1, own.minimumSiteTm("g-F_1"), 1e-9);
        assertEquals(perfect + 3, own.minimumSiteTm("other-F"), 1e-9);
        assertTrue(own.describe().endsWith("; at their own annealing temperature (ta=): g "
                + java.math.BigDecimal.valueOf(perfect - 1).stripTrailingZeros().toPlainString()), own.describe());
    }

    @Test
    void withoutTheGateTheCountsDecideAsBefore() {
        assertFalse(STRICT.tmGated());
        assertTrue(STRICT.withTmGate(TmGate.at(60)).tmGated());
        assertEquals(STRICT, STRICT.withTmGate(TmGate.at(60)).withTmGate(null));
    }

    @Test
    void settingsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new TmGate(Double.NaN, 5, 10, 50, 3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, -1, 10, 50, 3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, 5, -1, 50, 3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, 5, 10, 50, -3, 0.8, 250));
        assertThrows(IllegalArgumentException.class, () -> new TmGate(60, 5, 10, 50, 3, 0.8, 0));
        assertEquals(50.0, TmGate.at(60).minimumSiteTm("a-F"));
        assertEquals(55.0, TmGate.at(60).minimumCopyTm("a-F"));
        assertEquals(55.0, TmGate.at(60).minimumSiteTm("a-P"));
        assertEquals("primer sites Tm at least 50 °C to start a product (60 - 10), primers at least 55 °C on their"
                + " perfect complement to copy it and probe sites at least 55 °C (60 - 5); 50 mM Na+, 3 mM Mg2+,"
                + " 0.8 mM dNTP, 250 nM oligo", TmGate.at(60).describe());
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
