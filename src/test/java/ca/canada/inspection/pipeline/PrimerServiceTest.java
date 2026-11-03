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
    void invalidCharactersAreRejected() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACGU")));
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACG T")));
    }

    private static Map<String, String> map(String id, String seq) {
        Map<String, String> primers = new LinkedHashMap<>();
        primers.put(id, seq);
        return primers;
    }
}
