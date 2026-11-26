package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AmpliconOptionsTest {

    @Test
    void theDefaultsAreTheDocumentedOnes() {
        AmpliconOptions options = AmpliconOptions.defaults();
        assertEquals(5, options.threePrimeWindow());
        assertEquals(0, options.maxThreePrimePenalty());
        assertEquals(0, options.minAmpliconLength());
        assertEquals(5000, options.maxAmpliconLength());
        assertSame(TmGate.DEFAULT, options.tmGate());
        assertEquals(5, AmpliconOptions.FIVE_PRIME_FREE);
    }

    @Test
    void negativeValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(-1, 0, 0, 5000, TmGate.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(5, -1, 0, 5000, TmGate.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(5, 0, -1, 5000, TmGate.DEFAULT));
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(5, 0, 0, -1, TmGate.DEFAULT));
    }

    @Test
    void theTmRuleIsAlwaysOn() {
        assertThrows(IllegalArgumentException.class, () -> new AmpliconOptions(5, 0, 0, 5000, null));
        TmGate at55 = TmGate.at(55);
        assertSame(at55, AmpliconOptions.defaults().withTmGate(at55).tmGate());
    }

    @Test
    void minAboveMaxIsRejectedUnlessMaxIsUnlimited() {
        assertThrows(IllegalArgumentException.class, () -> AmpliconOptions.builder().minLength(600).maxLength(500).build());
        // max = 0 means unlimited, so any min is allowed.
        assertEquals(600, AmpliconOptions.builder().minLength(600).maxLength(0).build().minAmpliconLength());
    }
}
