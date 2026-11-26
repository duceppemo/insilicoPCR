package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NumbersTest {

    @Test
    void numbersAreWrittenWithoutTrailingZerosOrAnExponent() {
        assertEquals("60", Numbers.plain(60));
        assertEquals("58.5", Numbers.plain(58.5));
        assertEquals("0.8", Numbers.plain(0.8));
        assertEquals("100", Numbers.plain(1e2));
        assertEquals("0", Numbers.plain(0));
    }
}
