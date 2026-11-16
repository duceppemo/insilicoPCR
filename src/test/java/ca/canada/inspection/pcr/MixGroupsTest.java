package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixGroupsTest {

    @Test
    void assaysOfOneGroupShareAReactionAndOthersRunOnTheirOwn() {
        MixGroups mix = MixGroups.fromHeaders(Map.of("a-F", "size=100 MIX=panel1", "b-R", "mix=panel1",
                "c-F", "mix=panel2", "d-F", "size=200"));

        assertTrue(mix.shareAReaction("a", "b"));
        assertFalse(mix.shareAReaction("a", "c"), "another reaction");
        assertFalse(mix.shareAReaction("a", "d"), "d is run on its own");
        assertFalse(mix.shareAReaction("a", "a"), "an assay with itself is its own products");
        assertEquals(Map.of("panel1", Set.of("a", "b"), "panel2", Set.of("c")), mix.groups());
        assertSame(MixGroups.NONE, MixGroups.fromHeaders(Map.of("a-F", "size=100")));
        assertEquals(java.util.List.of("Assays sharing a reaction (mix= in the primer file; their primers pair across"
                + " assays):", "  panel1: a, b", "  panel2: c"), mix.qaLogLines());
        assertEquals(java.util.List.of(), MixGroups.NONE.qaLogLines());
    }

    @Test
    void anAssayIsInOneReaction() {
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> MixGroups.fromHeaders(new java.util.TreeMap<>(Map.of("a-F", "mix=one", "a-R", "mix=two"))));
        assertEquals("Assay a declares mix= twice with different values: one on a-F, two on a-R (an assay is in one"
                + " reaction)", twice.getMessage());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> MixGroups.fromHeaders(Map.of("a-F", "mix=")))
                .getMessage().startsWith("Primer a-F: mix= needs the name"));
        // Agreeing on two primers is fine.
        assertTrue(MixGroups.fromHeaders(Map.of("a-F", "mix=one", "a-R", "mix=one", "b-F", "mix=one"))
                .shareAReaction("a", "b"));
    }

    @Test
    void aProductOfTwoAssaysHasOneNameWhicheverStrand() {
        assertEquals("stxQ+toxA", MixGroups.crossAssayName("toxA", "stxQ"));
        assertEquals("stxQ+toxA", MixGroups.crossAssayName("stxQ", "toxA"));
    }

    @Test
    void anAssayNamedAsAProductOfTwoAssaysIsRefused() {
        MixGroups mix = MixGroups.fromHeaders(Map.of("a-F", "mix=one", "b-F", "mix=one"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> mix.checkNames(Set.of("a", "b", "a+b")));
        assertEquals("Assay a+b is named as the products of a and b, which share the reaction one (mix=): rename it",
                error.getMessage());
        mix.checkNames(Set.of("a", "b", "c+d")); // another name with a +: fine
    }

    @Test
    void anAssayOfAReactionWithAPlusInItsIdIsRefused() {
        // a with b+c, and a+b with c, were both reported as a+b+c: two products under one gene.
        MixGroups mix = MixGroups.fromHeaders(Map.of("a-F", "mix=t", "b+c-F", "mix=t", "a+b-F", "mix=t", "c-F", "mix=t"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> mix.checkNames(Set.of("a", "b+c", "a+b", "c")));
        assertEquals("Assay a+b shares the reaction t (mix=) and has '+' in its ID, which joins the names of two assays"
                + " in a product of both: rename it", error.getMessage());
    }
}
