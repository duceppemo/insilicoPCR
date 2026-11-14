package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimerPanelTest {

    private static final PrimerPanel PANEL = PrimerPanel.of(Map.of(
            "qpcr-F", "ACGT", "qpcr-R_0", "ACGA", "qpcr-R_1", "ACGG", "qpcr-P", "ACGT", "lone-F", "ACGT"));

    @Test
    void theAssaysAreReadOnlyAllTheWayDown() {
        // Every step shares them: none may change what the others see.
        assertThrows(UnsupportedOperationException.class, () -> PANEL.assays().get("qpcr").forward().add("x-F"));
        assertThrows(UnsupportedOperationException.class, () -> PANEL.assays().remove("qpcr"));
        assertEquals(List.of("qpcr-R_0", "qpcr-R_1"), PANEL.assays().get("qpcr").reverse().stream().sorted().toList());
    }

    @Test
    void onlyAssaysWithBothPrimersCanGiveAProduct() {
        assertEquals(List.of("lone", "qpcr"), List.copyOf(PANEL.assays().keySet()));
        assertEquals(List.of("qpcr"), List.copyOf(PANEL.completeAssays().keySet()));
        assertEquals(java.util.Set.of("qpcr"), PANEL.assaysWithProbe());
        assertTrue(PANEL.anyProbe());
        assertEquals(4, PANEL.length("qpcr-F"));
        assertEquals(0, PANEL.length("absent-F"));
    }
}
