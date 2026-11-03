package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimerServiceTest {

    @Test
    void nonDegeneratePrimerIsKeptAsIs() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "ACGTACGT"));
        assertEquals(Map.of("assay-F", "ACGTACGT"), expanded);
    }

    @Test
    void degeneratePrimerExpandsToAllCombinations() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "ARYT"));
        assertEquals(4, expanded.size()); // R -> A/G, Y -> T/C
        assertTrue(expanded.containsKey("assay-F_0"));
        assertTrue(expanded.values().containsAll(java.util.List.of("AATT", "AACT", "AGTT", "AGCT")),
                "unexpected expansions: " + expanded.values());
    }

    @Test
    void lowercaseSequencesAreNormalized() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "acgt"));
        assertEquals("ACGT", expanded.get("assay-F"));
    }

    @Test
    void nonConformingPrimerIdsAreRejectedAtLoadTime() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("16S_27F", "ACGT")));   // no dash
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-fwd", "ACGT"))); // bad type char
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("-F", "ACGT")));        // empty assay name
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-", "ACGT")));    // dash at end
    }

    @Test
    void invalidCharactersAreRejected() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACGU")));
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACG T")));
    }

    private static Map<String, String> map(String id, String seq) {
        Map<String, String> primers = new LinkedHashMap<>();
        primers.put(id, seq);
        return primers;
    }
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void loadAndPrepareReadsMultiLineFastaExpandsAndWritesDatabase() throws java.io.IOException {
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("primers.fasta"),
                ">assay-F\nACGT\nacgt\n\n>assay-R\nACRT\n");
        java.nio.file.Path input = java.nio.file.Files.writeString(dir.resolve("in.fasta"), ">c\nACGT\n");
        java.nio.file.Path out = dir.resolve("out");
        var config = new PipelineConfig(input, out, primerFile, 1, 1e5,
                ca.canada.inspection.pcr.AmpliconOptions.withMismatches(0), false);
        var directories = PipelineDirectories.create(out);

        Map<String, String> primers = new PrimerService().loadAndPrepare(config, directories);

        assertEquals("ACGTACGT", primers.get("assay-F")); // lines joined, uppercased
        assertEquals("ACAT", primers.get("assay-R_0"));   // R expanded to A/G
        assertEquals("ACGT", primers.get("assay-R_1"));
        String db = java.nio.file.Files.readString(directories.primerDatabase());
        assertTrue(db.contains(">assay-R_0"));
    }

    @Test
    void everyIupacCodeExpandsToItsBases() {
        Map<String, char[]> expected = Map.ofEntries(
                Map.entry("R", "AG".toCharArray()), Map.entry("Y", "TC".toCharArray()),
                Map.entry("S", "GC".toCharArray()), Map.entry("W", "AT".toCharArray()),
                Map.entry("K", "TG".toCharArray()), Map.entry("M", "AC".toCharArray()),
                Map.entry("B", "TGC".toCharArray()), Map.entry("D", "ATG".toCharArray()),
                Map.entry("H", "ATC".toCharArray()), Map.entry("V", "ACG".toCharArray()),
                Map.entry("N", "ATCG".toCharArray()));
        for (var entry : expected.entrySet()) {
            Map<String, String> expanded = PrimerService.expand(map("x-F", "A" + entry.getKey()));
            assertEquals(entry.getValue().length, expanded.size(), "code " + entry.getKey());
        }
        // Two Ns expand to 16 combinations.
        assertEquals(16, PrimerService.expand(map("x-F", "NN")).size());
    }

    @Test
    void blankSequencesAreRejected() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("x-F", "   ")));
    }

}
