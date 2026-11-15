package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What a sample shows for an assay, decided apart from how the HTML report draws it. */
class AssayVerdictTest {

    private static final ExpectedSizes SIZES = ExpectedSizes.fromHeaders(Map.of("its-F", "size=420-450 other=575"));

    @Test
    void productsAreJudgedByTheirSizes() {
        assertEquals(AssayVerdict.Kind.TARGET, AssayVerdict.of("its", 2,
                Set.of(ExpectedSizes.Label.TARGET, ExpectedSizes.Label.OTHER), SIZES, null, null).kind());
        assertEquals(AssayVerdict.Kind.OTHER, AssayVerdict.of("its", 1,
                Set.of(ExpectedSizes.Label.OTHER), SIZES, null, null).kind());
        assertEquals(AssayVerdict.Kind.UNEXPECTED, AssayVerdict.of("its", 1,
                Set.of(ExpectedSizes.Label.UNEXPECTED), SIZES, null, null).kind());
        // An assay that declares no size: amplified, the size left to the reader.
        assertEquals(AssayVerdict.Kind.AMPLIFIED, AssayVerdict.of("plain", 1, Set.of(), SIZES, null, null).kind());
    }

    @Test
    void withoutAProductTheReadsDecide() {
        assertEquals(new AssayVerdict(AssayVerdict.Kind.NONE, ""), AssayVerdict.of("its", 0, Set.of(), SIZES, null, null));
        FastqDiagnostics.Finding notInReads = new FastqDiagnostics.Finding("S", "its", FastqDiagnostics.Status.NOT_IN_READS,
                FastqDiagnostics.Assembly.NOT_ASSEMBLED, new FastqDiagnostics.ReadsWithOligo(5, 5, 0), 300, "", "");
        AssayVerdict unchecked = AssayVerdict.of("its", 0, Set.of(), SIZES, notInReads, null);
        assertEquals(AssayVerdict.Kind.UNCHECKED, unchecked.kind());
        assertEquals("Both primers are in the reads, but the mini-assembly did not join them, and a product of the"
                + " declared size (" + SIZES.describe("its") + ") is longer than any read could hold (300 bp)."
                + " Confirm on an assembly of the reads.", unchecked.note());
    }

    @Test
    void theKindsSayWhatCountsAsPositiveAndWhichSizesDecide() {
        // The HTML report counts and lists the kinds by these, not by a list of its own.
        assertEquals(java.util.List.of(AssayVerdict.Kind.TARGET, AssayVerdict.Kind.AMPLIFIED),
                java.util.Arrays.stream(AssayVerdict.Kind.values()).filter(AssayVerdict.Kind::isPositive).toList());
        assertEquals(AssayVerdict.Kind.Group.WITHHELD, AssayVerdict.Kind.UNCONFIRMED.group());
        assertEquals(AssayVerdict.Kind.Group.NEGATIVE, AssayVerdict.Kind.ACROSS_ENDS.group());
        for (ExpectedSizes.Label label : ExpectedSizes.Label.values()) {
            AssayVerdict.Kind kind = AssayVerdict.Kind.of(label);
            org.junit.jupiter.api.Assertions.assertTrue(kind.decidedBy(label));
            org.junit.jupiter.api.Assertions.assertTrue(AssayVerdict.Kind.AMPLIFIED.decidedBy(label));
            org.junit.jupiter.api.Assertions.assertFalse(AssayVerdict.Kind.NONE.decidedBy(label));
        }
        org.junit.jupiter.api.Assertions.assertTrue(AssayVerdict.Kind.AMPLIFIED.decidedBy(null));
        org.junit.jupiter.api.Assertions.assertFalse(AssayVerdict.Kind.TARGET.decidedBy(null));
    }

    @Test
    void everyReadStatusIsCalledWithheldOrNeither() {
        for (FastqDiagnostics.Status status : FastqDiagnostics.Status.values()) {
            org.junit.jupiter.api.Assertions.assertFalse(status.isCalled() && status.isWithheld(), status.name());
        }
        org.junit.jupiter.api.Assertions.assertTrue(FastqDiagnostics.Status.CALLED_BY_EXTENSION.isCalled());
        org.junit.jupiter.api.Assertions.assertTrue(FastqDiagnostics.Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT.isWithheld());
    }
}
