package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmpliconOptionsTest {

    @Test
    void withMismatchesAppliesDocumentedDefaults() {
        AmpliconOptions options = AmpliconOptions.withMismatches(2);
        assertEquals(2, options.maxTotalMismatches());
        assertEquals(2, options.maxProbeMismatches());
        assertEquals(5, options.threePrimeWindow());
        assertEquals(0, options.maxThreePrimePenalty());
        assertEquals(5, options.maxFivePrimeTruncation());
        assertEquals(0, options.minAmpliconLength());
        assertEquals(5000, options.maxAmpliconLength());
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(-1, 0, 5, 0, 5, 0, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, -1, 5, 0, 5, 0, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, -1, 0, 5, 0, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, 5, -1, 5, 0, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, 5, 0, -1, 0, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, 5, 0, 5, -1, 5000));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, 5, 0, 5, 0, -1));
    }

    @Test
    void minAboveMaxIsRejectedUnlessMaxIsUnlimited() {
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(0, 0, 5, 0, 5, 600, 500));
        // max = 0 means unlimited, so any min is allowed.
        assertEquals(600, new AmpliconOptions(0, 0, 5, 0, 5, 600, 0).minAmpliconLength());
    }
}
