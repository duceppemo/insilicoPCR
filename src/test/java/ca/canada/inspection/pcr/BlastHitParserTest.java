package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastHitParserTest {

    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of(
            "assay-F", PRIMER_20,
            "assay-P", PRIMER_20
    ));
    private static final AmpliconOptions STRICT = AmpliconOptions.defaults();

    // outfmt: qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq
    private static String line(String sseqid, int mismatch, int gaps, int slen, int length,
                               int qstart, int qend, String qseq, int sstart, int send, String sseq) {
        return String.join("\t",
                "contig1", sseqid, String.valueOf(length - mismatch), String.valueOf(mismatch),
                String.valueOf(gaps), "1e-5", "40.1", String.valueOf(slen), String.valueOf(length),
                String.valueOf(qstart), String.valueOf(qend), qseq,
                String.valueOf(sstart), String.valueOf(send), sseq);
    }

    @Test
    void perfectHitIsAccepted() {
        String blastLine = line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, STRICT);
        assertTrue(hit.isPresent());
        assertEquals("assay-F", hit.get().subjectID());
        assertTrue(hit.get().isPlusStrand());
    }

    @Test
    void aPrimerCutByTheContigEndNeedsTwelveBasesOrHalfOfItOnTheContig() {
        // A 20-mer whose first n bases are the last n of a contig: its 3' end runs off the end. With 12 on the
        // contig it is a cut site; with 11, a chance match of a few bases (7 to 9 made every warning on 2,022
        // genomes in 0.15.3).
        for (int onContig : new int[]{12, 11}) {
            String contig = "TTTTTTTTTT" + PRIMER_20.substring(0, onContig);
            String aligned = PRIMER_20.substring(0, onContig);
            String blastLine = line("assay-F", 0, 0, 20, onContig, 11, 10 + onContig, aligned, 1, onContig, aligned);
            Optional<BlastResult> cut = BlastHitParser.cutByQueryEnd(blastLine, PRIMERS, STRICT,
                    hit -> new TrimmedEnds.Template(contig));
            assertEquals(onContig >= 12, cut.isPresent(), onContig + " bases on the contig");
        }
        // Primer bases 2 to 12 aligned on the last 11 bases of the contig, base 1 (one of the 5 free 5' bases) left out
        // though on the contig: 11 aligned, not 12 (8 aligned and 5 left out made a warning on 2,022 genomes).
        String contig = "TTTTTTTTTA" + PRIMER_20.substring(1, 12);
        String aligned = PRIMER_20.substring(1, 12);
        String blastLine = line("assay-F", 0, 0, 20, 11, 11, 21, aligned, 2, 12, aligned);
        assertTrue(BlastHitParser.cutByQueryEnd(blastLine, PRIMERS, STRICT, hit -> new TrimmedEnds.Template(contig))
                .isEmpty());
        assertEquals(12, BlastHitParser.minimumOnQuery(20));
        assertEquals(12, BlastHitParser.minimumOnQuery(23));
        assertEquals(13, BlastHitParser.minimumOnQuery(25));
        assertEquals(15, BlastHitParser.minimumOnQuery(30));
    }

    @Test
    void anOligoThePrimerFileDoesNotHoldIsAnError() {
        // Skipped, every hit of a primer whose ID BLAST changed (Caf##-F for Café-F) was lost without a word.
        String blastLine = line("Caf##-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> BlastHitParser.parse(blastLine, PRIMERS, STRICT));
        assertTrue(error.getMessage().contains("Caf##-F"), error.getMessage());
    }

    /** The Tm the site of {@code blastLine} has, on {@code template} ({@code null}: the bases BLAST aligned). */
    private static double tm(String blastLine, TrimmedEnds.Template template) {
        return BlastHitParser.parseSite(blastLine, PRIMERS, LENIENT_THREE_PRIME, any -> template).orElseThrow()
                .tm(TmGate.DEFAULT);
    }

    /** Options whose Tm rule needs {@code minimum} °C, the margin being 5. */
    private static AmpliconOptions needing(double minimum) {
        return AmpliconOptions.builder().tmGate(TmGate.at(minimum + TmGate.DEFAULT_MARGIN)).build();
    }

    private static final AmpliconOptions LENIENT_THREE_PRIME = AmpliconOptions.builder().threePrimePenalty(99).build();

    @Test
    void threePrimeTruncatedPrimerIsRejectedByTheThreePrimeRuleWhateverItsTm() {
        // Alignment covers primer 1..18: BLAST trimmed 2 bases at the 3' end.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-F", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(1)).isEmpty());
        BlastHitParser.Pending site = BlastHitParser.parseSite(blastLine, PRIMERS, STRICT, any -> null).orElseThrow();
        assertTrue(!site.primes() && site.strand() == null, "no Tm is worked out for it");
    }

    @Test
    void aSiteMissingFivePrimeBasesIsJudgedByTheTmOfTheBasesItHas() {
        // No template: only the bases BLAST aligned pair. 2 bases left out at the 5' end melt a little lower than
        // the whole site, 10 much lower; neither is rejected for the count, as --5p-truncation did in 1.x.
        String whole = line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        String two = line("assay-F", 0, 0, 20, 18, 103, 120, PRIMER_20.substring(2), 3, 20, PRIMER_20.substring(2));
        String ten = line("assay-F", 0, 0, 20, 10, 111, 120, PRIMER_20.substring(10), 11, 20, PRIMER_20.substring(10));
        double wholeTm = tm(whole, null);
        double twoTm = tm(two, null);
        double tenTm = tm(ten, null);
        assertTrue(wholeTm > twoTm && twoTm > tenTm, wholeTm + " " + twoTm + " " + tenTm);
        assertTrue(BlastHitParser.parse(two, PRIMERS, needing(twoTm - 0.1)).isPresent());
        assertTrue(BlastHitParser.parse(two, PRIMERS, needing(twoTm + 0.1)).isEmpty());
        assertTrue(BlastHitParser.parse(ten, PRIMERS, needing(tenTm - 0.1)).isPresent(), "not refused by count");
    }

    @Test
    void anUnalignedThreePrimeBaseCountsAsAMismatchAndForTheThreePrimeRule() {
        // Aligned over 1..19, the 3' base left unaligned and no template to compare it with: it counts as a
        // mismatch, and its 3' penalty (3) is over the default 0.
        String aligned = PRIMER_20.substring(0, 19);
        String blastLine = line("assay-F", 0, 0, 20, 19, 101, 119, aligned, 1, 19, aligned);

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isEmpty());
        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS,
                AmpliconOptions.builder().threePrimePenalty(3).tmGate(TmGate.at(10)).build());
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().primerMismatches());
    }

    @Test
    void aMismatchWithinTheFivePrimeFreeBasesIsAnUnpairedFivePrimeBase() {
        // One mismatch at base 5, which BLAST keeps aligned: within the first 5 bases, it is a 5' base that does
        // not pair, and the report says so, as of two there (bases 1 and 5), which BLAST trims off.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(4, 'T' == qseq.charAt(4) ? 'A' : 'T');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, needing(1));
        assertTrue(hit.isPresent());
        assertEquals(0, hit.get().primerMismatches());
        assertEquals(1, hit.get().fivePrimeUnpairedBases());
    }

    @Test
    void trimmedFivePrimeBasesPastTheFreeOnesAreComparedWithTheTemplate() {
        // Mismatches at bases 4 and 7: BLAST trims the first 7 bases. Base 4 is within the first 5, an unpaired
        // base; base 7, compared with the template, is one mismatch. The Tm is the whole site's, both included.
        StringBuilder site = new StringBuilder(PRIMER_20);
        for (int index : new int[]{3, 6}) {
            site.setCharAt(index, site.charAt(index) == 'A' ? 'C' : 'A');
        }
        String flank = "GGGGGGGGGGGGGGGGGGGG";
        TrimmedEnds.Template template = TrimmedEnds.Template.whole(flank + site + flank);
        String aligned = PRIMER_20.substring(7);
        String blastLine = line("assay-F", 0, 0, 20, 13, 28, 40, aligned, 8, 20, aligned);

        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, needing(1), any -> template);
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().primerMismatches());
        assertEquals(1, hit.get().fivePrimeUnpairedBases(), "base 4");
        assertEquals(21, hit.get().fivePrimeTerminus(), "5 bases left unaligned before 26");
        String perfect = line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        assertTrue(tm(blastLine, template) < tm(perfect, null));
    }

    @Test
    void aMismatchOutsideTheThreePrimeWindowLowersTheTm() {
        // A mismatch at primer position 6: the site is accepted when its Tm reaches the minimum, whatever the count.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(5, 'T' == qseq.charAt(5) ? 'A' : 'T');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);
        double siteTm = tm(blastLine, null);
        assertTrue(siteTm < tm(line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20), null));

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(siteTm - 0.1)).isPresent());
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(siteTm + 0.1)).isEmpty());
    }

    @Test
    void mismatchInsideThreePrimeWindowIsRejectedByDefaultWhateverItsTm() {
        // Mismatch at primer position 18 (distance 2): the default 3' penalty of 0 rejects it.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(17, 'A');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(1)).isEmpty());
        // Raising the 3' penalty allowance rescues it (G/C mismatch at distance 2 scores 2+1).
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS,
                AmpliconOptions.builder().threePrimePenalty(3).tmGate(TmGate.at(10)).build()).isPresent());
    }

    @Test
    void probesAreExemptFromThreePrimeRules() {
        // Probe truncated by 2 bases at its 3' side: judged by its Tm alone, probes do not extend.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-P", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(1)).isPresent());
    }

    @Test
    void aProbeMismatchLowersItsTm() {
        // One internal mismatch in the probe, probe position 10: no 3' rules apply, its Tm decides.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(9, qseq.charAt(9) == 'A' ? 'G' : 'A');
        String blastLine = line("assay-P", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);
        double siteTm = tm(blastLine, null);
        assertTrue(siteTm < tm(line("assay-P", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20), null));

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(siteTm - 0.1)).isPresent());
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, needing(siteTm + 0.1)).isEmpty());
    }

    @Test
    void theParallelReadAndTheOneLineParseApplyTheSameTest() {
        // BlastHits reads Tms in parallel and decides with Pending.accepted, as parse does.
        String blastLine = line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        BlastHitParser.Pending site = BlastHitParser.parseSite(blastLine, PRIMERS, STRICT, any -> null).orElseThrow();
        double siteTm = site.tm(TmGate.DEFAULT);
        for (double minimum : new double[]{siteTm - 0.1, siteTm + 0.1}) {
            TmGate gate = TmGate.at(minimum + TmGate.DEFAULT_MARGIN);
            assertEquals(site.accepted(gate, siteTm),
                    BlastHitParser.parse(blastLine, PRIMERS, needing(minimum)).isPresent(), "at " + minimum);
            assertEquals(site.accepted(gate, siteTm), BlastHitParser.accepts(site.hit(), needing(minimum)));
        }
    }

    @Test
    void malformedLinesAreIgnored() {
        assertTrue(BlastHitParser.parse("too\tfew\tfields", PRIMERS, STRICT).isEmpty());
        assertTrue(BlastHitParser.parse(
                line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20).replace("101", "abc"),
                PRIMERS, STRICT).isEmpty());
    }
}
