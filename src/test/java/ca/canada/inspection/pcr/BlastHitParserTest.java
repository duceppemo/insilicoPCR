package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastHitParserTest {

    private static final Map<String, String> PRIMERS = Map.of(
            "assay-F", PRIMER_20,
            "assay-P", PRIMER_20
    );
    private static final AmpliconOptions STRICT = AmpliconOptions.withMismatches(0);
    private static final AmpliconOptions RELAXED = new AmpliconOptions(2, 5, 0, 2, 0, 5000);

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
        Optional<BlastResult> hit = BlastHitParser.parse(blastLine, "sample1", PRIMERS, STRICT);
        assertTrue(hit.isPresent());
        assertEquals("sample1", hit.get().name());
        assertEquals("assay-F", hit.get().subjectID());
        assertTrue(hit.get().isPlusStrand());
    }

    @Test
    void unknownPrimerIsIgnored() {
        String blastLine = line("other-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20);
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, STRICT).isEmpty());
    }

    @Test
    void threePrimeTruncatedPrimerIsRejectedEvenWithMismatchBudget() {
        // Alignment covers primer 1..18: BLAST trimmed 2 bases at the 3' end.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-F", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, RELAXED).isEmpty());
    }

    @Test
    void fivePrimeTruncationWithinAllowanceIsAccepted() {
        // Alignment covers primer 3..20: 2 bases missing at the harmless 5' end.
        String aligned = PRIMER_20.substring(2);
        String blastLine = line("assay-F", 0, 0, 20, 18, 103, 120, aligned, 3, 20, aligned);
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, STRICT).isPresent());
    }

    @Test
    void internalMismatchOutsideWindowUsesTotalBudget() {
        // Mismatch at primer position 5: fine with -m 1, rejected with -m 0.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(4, 'T' == qseq.charAt(4) ? 'A' : 'T');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, STRICT).isEmpty());
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS,
                new AmpliconOptions(1, 5, 0, 2, 0, 5000)).isPresent());
    }

    @Test
    void mismatchInsideThreePrimeWindowIsRejectedByDefaultEvenWithBudget() {
        // Mismatch at primer position 18 (distance 2): total budget would allow it,
        // but the default 3' penalty of 0 rejects it.
        StringBuilder qseq = new StringBuilder(PRIMER_20);
        qseq.setCharAt(17, 'A');
        String blastLine = line("assay-F", 1, 0, 20, 20, 101, 120, qseq.toString(), 1, 20, PRIMER_20);

        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, RELAXED).isEmpty());
        // Raising the 3' penalty allowance rescues it (G/C mismatch at distance 2 scores 2+1).
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS,
                new AmpliconOptions(1, 5, 3, 2, 0, 5000)).isPresent());
    }

    @Test
    void probesAreExemptFromThreePrimeRules() {
        // Probe truncated by 2 bases at its 3' side: fine, probes do not extend.
        String aligned = PRIMER_20.substring(0, 18);
        String blastLine = line("assay-P", 0, 0, 20, 18, 101, 118, aligned, 1, 18, aligned);
        assertTrue(BlastHitParser.parse(blastLine, "sample1", PRIMERS, STRICT).isPresent());
    }

    @Test
    void probeIdDetectionHandlesDegenerateSuffixes() {
        assertTrue(BlastHitParser.isProbe("assay-P"));
        assertTrue(BlastHitParser.isProbe("assay-P_3"));
        assertFalse(BlastHitParser.isProbe("assay-F"));
        assertFalse(BlastHitParser.isProbe("assay-R_1"));
        assertFalse(BlastHitParser.isProbe("plainname"));
    }

    @Test
    void malformedLinesAreIgnored() {
        assertTrue(BlastHitParser.parse("too\tfew\tfields", "sample1", PRIMERS, STRICT).isEmpty());
        assertTrue(BlastHitParser.parse(
                line("assay-F", 0, 0, 20, 20, 101, 120, PRIMER_20, 1, 20, PRIMER_20).replace("101", "abc"),
                "sample1", PRIMERS, STRICT).isEmpty());
    }
}
