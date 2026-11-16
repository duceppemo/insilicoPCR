package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.PrimerId.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimerIdTest {

    @Test
    void theAssayIsEverythingBeforeTheLastDashAndTheRoleTheLetterAfterIt() {
        assertEquals(new PrimerId("toxA", Role.FORWARD), PrimerId.parse("toxA-F"));
        assertEquals(new PrimerId("16S-v4", Role.REVERSE), PrimerId.parse("16S-v4-R"));
        assertEquals(new PrimerId("assay", Role.PROBE), PrimerId.parse("assay-P_3")); // an expanded probe
    }

    @Test
    void anIdOutsideTheConventionIsNotRead() {
        for (String id : List.of("plainname", "-F", "assay-", "assay-X", "assay-fwd", "16S_27F")) {
            assertNull(PrimerId.parse(id), id);
        }
    }

    @Test
    void theRoleIsReadWithoutTheAssay() {
        assertEquals(Role.REVERSE, PrimerId.roleOf("toxA-R_2"));
        assertEquals(Role.PROBE, PrimerId.roleOf("assay-P_3"));
        assertNull(PrimerId.roleOf("plain"));
        assertNull(PrimerId.roleOf("assay-"));
    }

    @Test
    void theRoleLetterEndsTheIdOrComesBeforeAnUnderscore() {
        // stxQ-FAM, a probe named for its dye, was a second forward primer: the assay lost its probe, and a
        // product of stxQ-FAM with stxQ-R was reported.
        for (String id : List.of("stxQ-FAM", "toxA-Rev", "toxA-R2", "toxA-R.1", "toxA-forward")) {
            assertEquals(null, PrimerId.parse(id), id);
        }
        assertEquals(PrimerId.Role.REVERSE, PrimerId.roleOf("toxA-R_x"));
        assertEquals(PrimerId.Role.FORWARD, PrimerId.roleOf("toxB-F_2"));
        assertEquals(PrimerId.Role.PROBE, PrimerId.roleOf("stxQ-P"));
    }

    @Test
    void probeIdDetectionHandlesDegenerateSuffixes() {
        assertTrue(PrimerId.isProbe("assay-P"));
        assertTrue(PrimerId.isProbe("assay-P_3"));
        assertFalse(PrimerId.isProbe("assay-F"));
        assertFalse(PrimerId.isProbe("assay-R_1"));
        assertFalse(PrimerId.isProbe("plainname"));
    }

    @Test
    void primersAreGroupedByAssayInNameOrder() {
        Map<String, PrimerId.Assay> assays = PrimerId.byAssay(
                List.of("b-F_0", "b-F_1", "b-R", "a-F", "a-P", "noconvention", "c-P"));

        assertEquals(List.of("a", "b", "c"), List.copyOf(assays.keySet()));
        assertEquals(List.of("b-F_0", "b-F_1"), assays.get("b").forward());
        assertFalse(assays.get("a").isComplete());
        assertTrue(assays.get("a").hasProbe());
        assertTrue(assays.get("b").isComplete());
        assertFalse(assays.get("b").hasProbe());
        assertFalse(assays.get("c").isComplete());
    }
}
