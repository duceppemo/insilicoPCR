package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BLAST ({@code blastn-short}, +1/-3) trims a terminal mismatch together with up to
 * three matching bases beyond it. These hits reproduce what BLAST reports for a
 * 15-mer probe with one mismatch near either end, and check that the trimmed bases
 * are compared against the template and counted.
 */
class ProbeEndsTest {

    private static final String PROBE = "CCGGTTCGCTCAATG";            // 15-mer, ReidRos-P like
    private static final String FLANK_L = "ATATATATATATATATATAT";     // 20 bases
    private static final String FLANK_R = "GAGAGAGAGAGAGAGAGAGA";     // 20 bases
    private static final int PROBE_START = FLANK_L.length() + 1;      // query position of probe base 1
    private static final Map<String, String> PRIMERS = Map.of("assay-P", PROBE, "assay-F", PROBE);
    private static final AmpliconOptions PROBE_BUDGET_0 = new AmpliconOptions(0, 0, 5, 0, 5, 0, 5000);
    private static final AmpliconOptions PROBE_BUDGET_1 = new AmpliconOptions(0, 1, 5, 0, 5, 0, 5000);

    /** Probe with the base at 1-based position {@code pos} changed. */
    private static String mutate(String seq, int pos) {
        char[] bases = seq.toCharArray();
        bases[pos - 1] = bases[pos - 1] == 'A' ? 'C' : 'A';
        return new String(bases);
    }

    private static String revcomp(String seq) {
        StringBuilder out = new StringBuilder(seq.length());
        for (int i = seq.length() - 1; i >= 0; i--) {
            out.append(switch (seq.charAt(i)) {
                case 'A' -> 'T';
                case 'C' -> 'G';
                case 'G' -> 'C';
                case 'T' -> 'A';
                default -> 'N';
            });
        }
        return out.toString();
    }

    /**
     * The BLAST TSV line for a plus-strand probe whose alignment covers probe
     * positions [sstart, send] only, on a template where the probe sits at PROBE_START.
     */
    private static String plusLine(String template, int sstart, int send) {
        int qstart = PROBE_START + sstart - 1;
        int qend = PROBE_START + send - 1;
        String qseq = template.substring(qstart - 1, qend);
        String sseq = PROBE.substring(sstart - 1, send);
        return line(qstart, qend, qseq, sstart, send, sseq);
    }

    /**
     * The BLAST TSV line for a probe on the template's minus strand (sstart > send),
     * covering probe positions [send, sstart]; the template carries revcomp(probe)
     * at PROBE_START.
     */
    private static String minusLine(String template, int sstart, int send) {
        // Probe position p sits at query position PROBE_START + (15 - p).
        int qstart = PROBE_START + PROBE.length() - sstart;
        int qend = PROBE_START + PROBE.length() - send;
        String qseq = template.substring(qstart - 1, qend);
        String sseq = revcomp(PROBE.substring(send - 1, sstart));
        return line(qstart, qend, qseq, sstart, send, sseq);
    }

    private static String line(int qstart, int qend, String qseq, int sstart, int send, String sseq) {
        int mismatches = 0;
        for (int i = 0; i < qseq.length(); i++) {
            mismatches += qseq.charAt(i) == sseq.charAt(i) ? 0 : 1;
        }
        return String.join("\t", "contig1", "assay-P", String.valueOf(qseq.length() - mismatches),
                String.valueOf(mismatches), "0", "0.01", "24.3", String.valueOf(PROBE.length()),
                String.valueOf(qseq.length()), String.valueOf(qstart), String.valueOf(qend), qseq,
                String.valueOf(sstart), String.valueOf(send), sseq);
    }

    private static Optional<BlastResult> parse(String line, String template, AmpliconOptions options) {
        return BlastHitParser.parse(line, "s", PRIMERS, options, id -> "contig1".equals(id) ? template : null);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void mismatchTrimmedFromTheFivePrimeSideIsCounted(int position) {
        String template = FLANK_L + mutate(PROBE, position) + FLANK_R;
        // BLAST keeps probe positions position+1 .. 15.
        String blastLine = plusLine(template, position + 1, PROBE.length());

        BlastResult hit = parse(blastLine, template, PROBE_BUDGET_1).orElseThrow();
        assertEquals(1, hit.mismatch());
        assertEquals(0, hit.fivePrimeUncovered() + hit.threePrimeUncovered());
        assertEquals(PROBE_START, hit.qstart());
        assertEquals(PROBE_START + PROBE.length() - 1, hit.qend());
        assertEquals(template.substring(PROBE_START - 1, PROBE_START - 1 + PROBE.length()), hit.qseq());
        assertEquals(PROBE, hit.sseq());

        assertTrue(parse(blastLine, template, PROBE_BUDGET_0).isEmpty(), "one real mismatch exceeds budget 0");
    }

    @ParameterizedTest
    @ValueSource(ints = {12, 13, 14, 15})
    void mismatchTrimmedFromTheThreePrimeSideIsCounted(int position) {
        String template = FLANK_L + mutate(PROBE, position) + FLANK_R;
        String blastLine = plusLine(template, 1, position - 1);

        BlastResult hit = parse(blastLine, template, PROBE_BUDGET_1).orElseThrow();
        assertEquals(1, hit.mismatch());
        assertEquals(1, hit.sstart());
        assertEquals(PROBE.length(), hit.send());
        assertTrue(parse(blastLine, template, PROBE_BUDGET_0).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3, 13, 15})
    void minusStrandProbeEndsAreComparedOnTheComplement(int position) {
        String template = FLANK_L + revcomp(mutate(PROBE, position)) + FLANK_R;
        String blastLine = position <= 4
                ? minusLine(template, PROBE.length(), position + 1)   // 5' side trimmed
                : minusLine(template, position - 1, 1);              // 3' side trimmed

        BlastResult hit = parse(blastLine, template, PROBE_BUDGET_1).orElseThrow();
        assertFalse(hit.isPlusStrand());
        assertEquals(1, hit.mismatch());
        assertEquals(PROBE.length(), hit.sstart());
        assertEquals(1, hit.send());
        assertEquals(PROBE_START, hit.qstart());
        assertEquals(revcomp(PROBE), hit.sseq());
        assertTrue(parse(blastLine, template, PROBE_BUDGET_0).isEmpty());
    }

    @Test
    void perfectlyMatchingTrimmedBasesAddNoMismatch() {
        // BLAST may stop short for other reasons (e.g. an X-drop); bases that pair are free.
        String template = FLANK_L + PROBE + FLANK_R;
        String blastLine = plusLine(template, 3, 15);
        BlastResult hit = parse(blastLine, template, PROBE_BUDGET_0).orElseThrow();
        assertEquals(0, hit.mismatch());
        assertEquals(1, hit.sstart());
    }

    @Test
    void ambiguousTemplateBasesCountAsMismatches() {
        String template = FLANK_L + "NN" + PROBE.substring(2) + FLANK_R;
        String blastLine = plusLine(template, 3, 15);
        assertEquals(2, parse(blastLine, template, new AmpliconOptions(0, 2, 5, 0, 5, 0, 5000)).orElseThrow().mismatch());
        assertTrue(parse(blastLine, template, PROBE_BUDGET_1).isEmpty());
    }

    @Test
    void basesBeyondTheTemplateEndStayUnaligned() {
        // Probe overhangs the start of a read by 2 bases: nothing to compare them with,
        // so they stay unaligned and the old 2-base coverage allowance applies.
        String template = PROBE.substring(2) + FLANK_R;
        String qseq = PROBE.substring(2);
        String blastLine = String.join("\t", "contig1", "assay-P", "13", "0", "0", "0.01", "24.3",
                "15", "13", "1", "13", qseq, "3", "15", qseq);

        BlastResult hit = parse(blastLine, template, PROBE_BUDGET_0).orElseThrow();
        assertEquals(2, hit.fivePrimeUncovered());
        assertEquals(0, hit.mismatch());
    }

    @Test
    void withoutATemplateTheHitIsUnchanged() {
        String template = FLANK_L + mutate(PROBE, 1) + FLANK_R;
        String blastLine = plusLine(template, 2, 15);
        BlastResult hit = BlastHitParser.parse(blastLine, "s", PRIMERS, PROBE_BUDGET_0).orElseThrow();
        assertEquals(1, hit.fivePrimeUncovered());
        assertEquals(0, hit.mismatch());
    }

    @Test
    void templatesAreRequestedOnlyForTrimmedProbeHits() {
        String template = FLANK_L + PROBE + FLANK_R;
        assertEquals(Optional.of("contig1"), BlastHitParser.templateNeeded(plusLine(template, 3, 15), PRIMERS));
        assertTrue(BlastHitParser.templateNeeded(plusLine(template, 1, 15), PRIMERS).isEmpty());
        String primerLine = plusLine(template, 3, 15).replace("assay-P", "assay-F");
        assertTrue(BlastHitParser.templateNeeded(primerLine, PRIMERS).isEmpty());
    }

    @Test
    void primerHitsAreNeverExtended() {
        String template = FLANK_L + PROBE + FLANK_R;
        BlastResult primer = TestHits.truncatedPlusHit("assay-F", "contig1", PROBE_START + 2, 3, 15, PROBE);
        assertSame(primer, ProbeEnds.extend(primer, template));
    }
}
