package ca.canada.inspection.insilicopcr.gel;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelScaleTest {

    @Test
    void positionIsZeroAtTheBottomAndOneAtTheTopOfTheScale() {
        assertEquals(0.0, GelScale.normalizedPosition(0), 1e-12);
        assertEquals(1.0, GelScale.normalizedPosition(25_000), 1e-12);
    }

    @Test
    void positionIsClampedOutsideTheScale() {
        assertEquals(0.0, GelScale.normalizedPosition(-50), 1e-12);
        assertEquals(1.0, GelScale.normalizedPosition(30_000), 1e-12);
        assertEquals(1.0, GelScale.normalizedPosition(Integer.MAX_VALUE), 1e-12);
    }

    @Test
    void positionFollowsTheLogOfTheSizePlusTheOffset() {
        // (log10(150) - log10(50)) / (log10(25050) - log10(50)) = log10(3) / log10(501)
        assertEquals(Math.log10(3) / Math.log10(501), GelScale.normalizedPosition(100), 1e-12);
        assertEquals(Math.log10(21) / Math.log10(501), GelScale.normalizedPosition(1_000), 1e-12);
    }

    @Test
    void largerAmpliconsTravelLess() {
        double previous = -1;
        for (int size = 0; size <= 25_000; size += 250) {
            double position = GelScale.normalizedPosition(size);
            assertTrue(position > previous, "position must grow with size at " + size);
            previous = position;
        }
    }

    @Test
    void ladderYRunsFromTheBottomOfTheGelUpToItsTop() {
        assertEquals(578.0, GelScale.ladderY(18, 560, 0), 1e-9);
        assertEquals(18.0, GelScale.ladderY(18, 560, 25_000), 1e-9);
        assertEquals(18 + 560 - 560 * GelScale.normalizedPosition(500), GelScale.ladderY(18, 560, 500), 1e-12);
        assertTrue(GelScale.ladderY(18, 560, 100) > GelScale.ladderY(18, 560, 1_000));
    }

    @Test
    void sizeIntensityIsStrongerForSmallAndCoMigratingBands() {
        assertEquals(0.65, GelScale.sizeIntensity(0, 1), 1e-12);
        assertEquals(0.35, GelScale.sizeIntensity(25_000, 1), 1e-12);
        assertEquals(0.47, GelScale.sizeIntensity(25_000, 2), 1e-12);
        assertEquals(0.35 + 0.35, GelScale.sizeIntensity(25_000, 10), 1e-12);
        assertEquals(1.0, GelScale.sizeIntensity(0, 5), 1e-12);
        assertEquals(0.35, GelScale.sizeIntensity(25_000, 0), 1e-12);
        assertTrue(GelScale.sizeIntensity(100, 1) > GelScale.sizeIntensity(5_000, 1));
    }

    @Test
    void deterministicRangeDependsOnlyOnTheKey() {
        double first = GelScale.deterministicRange("sampleA:300", -0.45, 0.45);
        assertEquals(first, GelScale.deterministicRange("sampleA:300", -0.45, 0.45));
        assertEquals(0.90, GelScale.deterministicRange(null, 0.90, 1.10), 1e-12);
        assertEquals(0.90, GelScale.deterministicRange("", 0.90, 1.10), 1e-12);
    }

    @Test
    void deterministicRangeStaysWithinBoundsAndSpreadsKeys() {
        Set<Double> values = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            double value = GelScale.deterministicRange("sample_" + i, -0.45, 0.45);
            assertTrue(value >= -0.45 && value <= 0.45, "out of range: " + value);
            values.add(value);
        }
        assertTrue(values.size() > 400, "keys should spread over the range");
        // A negative hash code is folded to a positive value.
        assertTrue("polygenelubricants".hashCode() < 0);
        double negativeHash = GelScale.deterministicRange("polygenelubricants", 0, 1);
        assertTrue(negativeHash >= 0 && negativeHash <= 1);
    }

    @Test
    void deterministicRangeReachesTheMaximum() {
        // hash % 10000 == 9999 gives the maximum.
        String key = findKeyWithRemainder(9_999);
        assertEquals(1.10, GelScale.deterministicRange(key, 0.90, 1.10), 1e-12);
    }

    private static String findKeyWithRemainder(int remainder) {
        for (int i = 0; ; i++) {
            String key = "k" + i;
            if ((key.hashCode() & 0x7fffffff) % 10_000 == remainder) {
                return key;
            }
        }
    }

    @Test
    void textWidthsAreEstimatedFromTheNumberOfCharacters() {
        assertEquals(30.5, GelScale.approximateTextWidth("1.5kb"), 1e-12);
        assertEquals(0.0, GelScale.approximateTextWidth(""), 1e-12);
        assertEquals(6 * 6.2, GelScale.laneLabelWidth(List.of()), 1e-12);
        assertEquals(6 * 6.2, GelScale.laneLabelWidth(List.of("abc")), 1e-12);
        assertEquals(10 * 6.2, GelScale.laneLabelWidth(List.of("abc", "abcdefghij", "abcd")), 1e-12);
    }

    @Test
    void ladderRunsFromTwentyKilobasesDownToOneHundredBasePairs() {
        List<GelScale.LadderBand> ladder = GelScale.LADDER;
        assertEquals(15, ladder.size());
        assertEquals(new GelScale.LadderBand(20_000, "20kb"), ladder.getFirst());
        assertEquals(new GelScale.LadderBand(1_500, "1.5kb"), ladder.get(7));
        assertEquals(new GelScale.LadderBand(100, "100"), ladder.getLast());
        for (int i = 1; i < ladder.size(); i++) {
            assertTrue(ladder.get(i).size() < ladder.get(i - 1).size());
        }
    }
}
