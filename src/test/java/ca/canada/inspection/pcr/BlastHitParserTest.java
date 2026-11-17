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
    private static final AmpliconOptions STRICT = AmpliconOptions.withMismatches(0);
    private static final AmpliconOptions RELAXED = new AmpliconOptions(2, 2, 5, 0, 2, 0, 5000);

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
        // Primer bases 2 to 12 aligned on the last 11 bases of the contig, base 1 (within --5p-truncation) left out
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

    @Test
    void threePrimeTruncatedPrimerIsRejectedEvenWithMismatchBudget() {
        // Alignment covers primer 1..18: BLAST trimmed 2 bases at the 3' end.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-F", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, RELAXED).isEmpty());
    }

    @Test
    void fivePrimeTruncationWithinAllowanceIsAccepted() {
        // Alignment covers primer 3..20: 2 bases missing at the harmless 5' end.
        String aligned = PRIMER_20.substring(2);
        String blastLine = line("assay-F", 0, 0, 20, 18, 103, 120, aligned, 3, 20, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isPresent());
    }

    @Test
    void anUnalignedThreePrimeBaseCountsAsAMismatchAgainstTheBudget() {
        // Aligned over 1..19, the 3' base left unaligned and no template to compare it with. Its 3' penalty
        // (3) is allowed here: only -m decides, the base counting as a mismatch.
        String aligned = PRIMER_20.substring(0, 19);
        String blastLine = line("assay-F", 0, 0, 20, 19, 101, 119, aligned, 1, 19, aligned);

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, new AmpliconOptions(0, 0, 5, 3, 5, 0, 5000)).isEmpty());
        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, new AmpliconOptions(1, 1, 5, 3, 5, 0, 5000));
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().primerMismatches());
    }

    @Test
    void fivePrimeTruncationIsAllowedUpToTheLimitAndNoFurther() {
        // --5p-truncation 5 (the default): 5 bases left unaligned at the 5' end pass, 6 do not.
        String five = PRIMER_20.substring(5);
        String six = PRIMER_20.substring(6);
        AmpliconOptions defaults = AmpliconOptions.withMismatches(0);

        assertTrue(BlastHitParser.parse(line("assay-F", 0, 0, 20, 15, 106, 120, five, 6, 20, five), PRIMERS, defaults)
                .isPresent());
        assertTrue(BlastHitParser.parse(line("assay-F", 0, 0, 20, 14, 107, 120, six, 7, 20, six), PRIMERS, defaults)
                .isEmpty());
    }

    @Test
    void aMismatchWithinTheFivePrimeAllowanceIsAnUnpairedFivePrimeBase() {
        // One mismatch at base 5, which BLAST keeps aligned: it was counted against -m 0, while two there
        // (bases 1 and 5), which BLAST trims off, were not. Within --5p-truncation 5 it is a 5' base that does
        // not pair, and the report says so.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(4, 'T' == qseq.charAt(4) ? 'A' : 'T');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, STRICT);
        assertTrue(hit.isPresent());
        assertEquals(0, hit.get().primerMismatches());
        assertEquals(1, hit.get().fivePrimeUnpairedBases());
        // With --5p-truncation 4, base 5 is past the allowance: a mismatch again.
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, new AmpliconOptions(0, 0, 5, 0, 4, 0, 5000)).isEmpty());
    }

    @Test
    void trimmedFivePrimeBasesPastTheAllowanceAreComparedNotRejected() {
        // Mismatches at bases 4 and 7: BLAST trims the first 7 bases. They were rejected, 7 being over
        // --5p-truncation 5, while a site with base 7's mismatch alone passed -m 1. Base 4 is within the
        // allowance; base 7, compared with the template, is one mismatch.
        StringBuilder site = new StringBuilder(PRIMER_20);
        for (int index : new int[]{3, 6}) {
            site.setCharAt(index, site.charAt(index) == 'A' ? 'C' : 'A');
        }
        String flank = "GGGGGGGGGGGGGGGGGGGG";
        TrimmedEnds.Template template = TrimmedEnds.Template.whole(flank + site + flank);
        String aligned = PRIMER_20.substring(7);
        String blastLine = line("assay-F", 0, 0, 20, 13, 28, 40, aligned, 8, 20, aligned);

        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, PRIMERS, new AmpliconOptions(1, 1, 5, 0, 5, 0, 5000),
                any -> template);
        assertTrue(hit.isPresent());
        assertEquals(1, hit.get().primerMismatches());
        assertEquals(1, hit.get().fivePrimeUnpairedBases(), "base 4");
        assertEquals(21, hit.get().fivePrimeTerminus(), "5 bases left unaligned before 26");
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT, any -> template).isEmpty(), "-m 0");
        // Without the template the bases cannot be compared: 7 unaligned, over the allowance.
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, new AmpliconOptions(1, 1, 5, 0, 5, 0, 5000)).isEmpty());
    }

    @Test
    void internalMismatchOutsideWindowUsesTotalBudget() {
        // Mismatch at primer position 6, past --5p-truncation 5: fine with -m 1, rejected with -m 0.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(5, 'T' == qseq.charAt(5) ? 'A' : 'T');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isEmpty());
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS,
                new AmpliconOptions(1, 1, 5, 0, 2, 0, 5000)).isPresent());
    }

    @Test
    void mismatchInsideThreePrimeWindowIsRejectedByDefaultEvenWithBudget() {
        // Mismatch at primer position 18 (distance 2): total budget would allow it,
        // but the default 3' penalty of 0 rejects it.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(17, 'A');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, RELAXED).isEmpty());
        // Raising the 3' penalty allowance rescues it (G/C mismatch at distance 2 scores 2+1).
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS,
                new AmpliconOptions(1, 1, 5, 3, 2, 0, 5000)).isPresent());
    }

    @Test
    void probesAreExemptFromThreePrimeRules() {
        // Probe truncated by 2 bases at its 3' side: fine, probes do not extend.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-P", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isPresent());
    }

    @Test
    void probeCoverageIsIndependentOfPrimerFivePrimeAllowance() {
        // 3 unaligned probe bases: rejected even though --5p-truncation (5) would
        // allow it for a primer. Probes must cover nearly their whole length.
        String aligned = PRIMER_20.substring(0, 17);
        String blastLine = line("assay-P", 0, 0, 20, 17, 101, 117, aligned, 1, 17, aligned);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isEmpty());
    }

    @Test
    void probesUseTheirOwnMismatchBudget() {
        // One internal mismatch in the probe, probe position 10: no 3' rules apply.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(9, qseq.charAt(9) == 'A' ? 'G' : 'A');
        String blastLine = line("assay-P", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        // Rejected when the probe budget is 0, accepted with --probe-mismatches 1
        // even though the primer budget (-m) stays 0.
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, STRICT).isEmpty());
        AmpliconOptions probeTolerant = new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000);
        assertTrue(BlastHitParser.parse(blastLine, PRIMERS, probeTolerant).isPresent());
    }

    @Test
    void malformedLinesAreIgnored() {
        assertTrue(BlastHitParser.parse("too\tfew\tfields", PRIMERS, STRICT).isEmpty());
        assertTrue(BlastHitParser.parse(
                line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20).replace("101", "abc"),
                PRIMERS, STRICT).isEmpty());
    }
}
