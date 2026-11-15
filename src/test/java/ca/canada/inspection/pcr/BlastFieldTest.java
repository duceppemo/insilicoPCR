package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** blastn is asked for these columns, the header names them and the parser reads them: one list. */
class BlastFieldTest {

    @Test
    void theColumnsAreTheOnesBlastnIsAskedFor() {
        assertEquals("6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
                BlastField.OUTFMT);
        assertEquals(BlastField.OUTFMT.substring(2).replace(' ', '\t'), BlastField.HEADER);
    }

    @Test
    void theParserReadsEachColumnWhereTheListPutsIt() {
        String[] fields = new String[BlastField.values().length];
        fields[BlastField.QSEQID.ordinal()] = "contig_9";
        fields[BlastField.SSEQID.ordinal()] = "a-F";
        fields[BlastField.POSITIVE.ordinal()] = "20";
        fields[BlastField.MISMATCH.ordinal()] = "0";
        fields[BlastField.GAPS.ordinal()] = "0";
        fields[BlastField.EVALUE.ordinal()] = "1e-5";
        fields[BlastField.BITSCORE.ordinal()] = "40.1";
        fields[BlastField.SLEN.ordinal()] = "20";
        fields[BlastField.LENGTH.ordinal()] = "20";
        fields[BlastField.QSTART.ordinal()] = "101";
        fields[BlastField.QEND.ordinal()] = "120";
        fields[BlastField.QSEQ.ordinal()] = TestHits.PRIMER_20;
        fields[BlastField.SSTART.ordinal()] = "1";
        fields[BlastField.SEND.ordinal()] = "20";
        fields[BlastField.SSEQ.ordinal()] = TestHits.PRIMER_20;

        var hit = BlastHitParser.parse(String.join("\t", fields), "S", PrimerPanel.of(Map.of("a-F", TestHits.PRIMER_20)),
                AmpliconOptions.withMismatches(0)).orElseThrow();

        assertEquals("contig_9", hit.queryID());
        assertEquals("a-F", hit.subjectID());
        assertEquals(0, hit.mismatch());
        assertEquals(101, hit.qstart());
        assertEquals(120, hit.qend());
        assertEquals(TestHits.PRIMER_20, hit.sseq());
    }
}
