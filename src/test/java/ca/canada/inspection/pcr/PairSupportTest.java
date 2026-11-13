package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.PairSupport.Kind;
import ca.canada.inspection.pcr.PairSupport.Placement;
import ca.canada.inspection.pcr.PairSupport.Tally;
import ca.canada.inspection.pcr.PairSupport.Verdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PairSupportTest {

    private static final int READ = 100;
    private static final int FRAGMENT = 300;

    private static String random(Random random, int length) {
        StringBuilder sequence = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sequence.append("ACGT".charAt(random.nextInt(4)));
        }
        return sequence.toString();
    }

    /** Adds the read pairs of fragments of {@link #FRAGMENT} bases starting every {@code step} bases of a genome. */
    private static void sequence(Tally tally, String genome, int step) {
        for (int start = 0; start + FRAGMENT <= genome.length(); start += step) {
            String fragment = genome.substring(start, start + FRAGMENT);
            String forward = fragment.substring(0, READ);
            String reverse = PairSupport.reverseComplement(fragment).substring(0, READ);
            // Half of the fragments are sequenced from the other strand.
            if ((start / step) % 2 == 0) {
                tally.addPair(forward, reverse);
            } else {
                tally.addPair(reverse, forward);
            }
        }
    }

    // --- placing reads ---

    @Test
    void readIsPlacedOnEitherStrandWithItsMismatches() {
        Random random = new Random(1);
        String product = random(random, 400);
        Tally tally = new Tally(List.of(product));

        Placement plus = tally.place(product.substring(50, 150))[0];
        assertEquals(new Placement(0, true, 50, 150, true, false, false), plus);

        Placement minus = tally.place(PairSupport.reverseComplement(product.substring(200, 300)))[0];
        assertEquals(new Placement(0, false, 200, 300, true, false, false), minus);

        // Two mismatches in 100 bases are within the 3% allowed; a read from elsewhere is not placed.
        char[] read = product.substring(50, 150).toCharArray();
        read[30] = read[30] == 'A' ? 'C' : 'A';
        read[60] = read[60] == 'A' ? 'C' : 'A';
        assertTrue(tally.place(new String(read))[0].whole());
        assertNull(tally.place(random(random, 100))[0]);
    }

    @Test
    void readHangingOffTheProductIsPlacedOverThePartOnIt() {
        Random random = new Random(2);
        String genome = random(random, 600);
        Tally tally = new Tally(List.of(genome.substring(100, 500)));

        // Starts 40 bases before the product: 60 bases lie on it, and nothing is clipped.
        assertEquals(new Placement(0, true, 0, 60, true, false, false), tally.place(genome.substring(60, 160))[0]);
        // Only 20 bases on the product: too few to place.
        assertNull(tally.place(genome.substring(20, 120))[0]);
    }

    @Test
    void readOfAnotherCopyOfARepeatIsPlacedOverTheRepeatOnly() {
        Random random = new Random(3);
        String repeat = random(random, 200);
        String product = random(random, 150) + repeat + random(random, 150);
        String elsewhere = random(random, 150) + repeat + random(random, 150);
        Tally tally = new Tally(List.of(product));

        // Comes from before the repeat at the other locus: matches from the repeat's start on.
        Placement entering = tally.place(elsewhere.substring(110, 210))[0];
        assertFalse(entering.whole());
        assertTrue(entering.entersHere());
        assertFalse(entering.leavesHere());
        assertTrue(Math.abs(entering.start() - 150) <= 2 && entering.end() == 210, entering.toString());

        // Goes on past the repeat at the other locus: matches up to the repeat's end.
        Placement leaving = tally.place(elsewhere.substring(290, 390))[0];
        assertTrue(leaving.leavesHere());
        assertFalse(leaving.entersHere());
        assertTrue(leaving.start() == 290 && Math.abs(leaving.end() - 350) <= 2, leaving.toString());
    }

    // --- the verdict ---

    @Test
    void productCoveredByReadPairsThroughoutIsSupported() {
        Random random = new Random(4);
        String genome = random(random, 1400);
        Tally tally = new Tally(List.of(genome.substring(200, 1200)));
        sequence(tally, genome, 5);

        Verdict verdict = tally.verdict(0);

        assertEquals(Kind.SUPPORTED, verdict.kind());
        assertTrue(verdict.passes());
        assertTrue(verdict.paired());
        // Fragments of 300 bases every 5: a 100-base window lies in 40 or 41 of them, a 200-base one in
        // 20 or 21 and a 250-base one in 10 or 11, which is under 30% of 40: too close to the fragment
        // length to rely on.
        assertEquals(200, verdict.window());
        assertEquals(20, verdict.median());
        // The windows at the two ends are held by fewer pairs: a mate lying mostly outside the product
        // is not placed, so its fragment is not counted.
        assertEquals(13, verdict.weakest());
        assertTrue(verdict.weakestStart() == 1 || verdict.weakestStart() == 801, verdict.summary());
        assertEquals("every 200-base window held by at least 13 read pairs", verdict.summary());
    }

    @Test
    void wrongTurnThroughAShortRepeatIsNotSupported() {
        // Locus X: A-R-B, locus Y: C-R-D; the product A-R-D exists in no fragment.
        Random random = new Random(5);
        String repeat = random(random, 120);
        String a = random(random, 400);
        String d = random(random, 400);
        String x = random(random, 300) + a + repeat + random(random, 700);
        String y = random(random, 700) + repeat + d + random(random, 300);
        Tally tally = new Tally(List.of(a + repeat + d));
        sequence(tally, x, 5);
        sequence(tally, y, 5);

        Verdict verdict = tally.verdict(0);

        assertEquals(Kind.UNSUPPORTED_WINDOW, verdict.kind());
        assertFalse(verdict.passes());
        assertEquals(0, verdict.weakest());
        // The unsupported window lies across the repeat (bases 401-520).
        assertTrue(verdict.weakestStart() < 401 && verdict.weakestStart() + verdict.window() > 520,
                verdict.summary());
        assertTrue(verdict.summary().matches("the \\d+-base window at \\d+-\\d+ is held by 0 read pairs, against \\d+"
                + " for the median window"), verdict.summary());
    }

    @Test
    void realProductThroughTheSameShortRepeatIsSupported() {
        Random random = new Random(5);
        String repeat = random(random, 120);
        String a = random(random, 400);
        String b = random(random, 400);
        String x = random(random, 300) + a + repeat + b + random(random, 300);
        String y = random(random, 700) + repeat + random(random, 700);
        Tally tally = new Tally(List.of(a + repeat + b));
        sequence(tally, x, 5);
        sequence(tally, y, 5);

        assertEquals(Kind.SUPPORTED, tally.verdict(0).kind(), tally.verdict(0).summary());
    }

    @Test
    void repeatLongerThanTheFragmentsIsUnresolvedForARealProductAndAWrongTurnAlike() {
        Random random = new Random(6);
        String repeat = random(random, 500);
        String a = random(random, 300);
        String b = random(random, 300);
        String d = random(random, 300);
        String x = random(random, 300) + a + repeat + b + random(random, 300);
        String y = random(random, 300) + repeat + d + random(random, 300);
        Tally tally = new Tally(List.of(a + repeat + b, a + repeat + d));
        sequence(tally, x, 5);
        sequence(tally, y, 5);

        for (int product = 0; product < 2; product++) {
            Verdict verdict = tally.verdict(product);
            assertEquals(Kind.UNRESOLVED_REPEAT, verdict.kind(), verdict.summary());
            assertFalse(verdict.passes());
            assertTrue(Math.abs(verdict.repeatStart() - 301) <= 2 && Math.abs(verdict.repeatEnd() - 800) <= 2,
                    verdict.summary());
            assertTrue(verdict.repeatEntering() >= PairSupport.MIN_BOUNDARY_READS);
            assertTrue(verdict.summary().startsWith("bases " + verdict.repeatStart() + "-" + verdict.repeatEnd()
                    + " are a repeat with another copy in the sample (" + verdict.repeatEntering()
                    + " reads of that copy enter the product there and " + verdict.repeatLeaving()
                    + " leave it), longer than the " + verdict.window() + "-base window the read pairs can verify"),
                    verdict.summary());
        }
    }

    @Test
    void variantCopiesWithSmallDeletionsDoNotLookLikeARepeat() {
        // A minority of the copies of a locus lack two bases at 400 and two at 700: their reads stop
        // matching at those places from both directions, which is not how a repeat ends.
        Random random = new Random(7);
        String genome = random(random, 1400);
        String variant = genome.substring(0, 600) + genome.substring(602, 900) + genome.substring(902);
        Tally tally = new Tally(List.of(genome.substring(200, 1200)));
        sequence(tally, genome, 5);
        sequence(tally, variant, 10);

        assertEquals(Kind.SUPPORTED, tally.verdict(0).kind(), tally.verdict(0).summary());
    }

    @Test
    void tooFewPairsCannotVerifyAProduct() {
        Random random = new Random(8);
        String genome = random(random, 1400);
        Tally tally = new Tally(List.of(genome.substring(200, 1200)));
        sequence(tally, genome, 60);

        Verdict verdict = tally.verdict(0);

        assertEquals(Kind.TOO_FEW_PAIRS, verdict.kind());
        assertFalse(verdict.passes());
        assertEquals("too few read pairs to verify it: the median 100-base window is held by " + verdict.median()
                + ", 10 needed", verdict.summary());
        assertTrue(verdict.median() < PairSupport.MIN_MEDIAN_FRAGMENTS);
    }

    @Test
    void unpairedReadsAreTheirOwnFragments() {
        Random random = new Random(9);
        String genome = random(random, 1000);
        Tally tally = new Tally(List.of(genome.substring(200, 800)));
        for (int start = 0; start + 250 <= genome.length(); start += 3) {
            tally.addRead(start % 2 == 0 ? genome.substring(start, start + 250)
                    : PairSupport.reverseComplement(genome.substring(start, start + 250)));
        }

        Verdict verdict = tally.verdict(0);

        assertEquals(Kind.SUPPORTED, verdict.kind(), verdict.summary());
        assertFalse(verdict.paired());
        assertTrue(verdict.window() < 250, "a window cannot be longer than a read");
        assertTrue(verdict.summary().matches("every \\d+-base window held by at least \\d+ reads"), verdict.summary());
    }

    @Test
    void productNotCheckedDoesNotPass() {
        assertFalse(Verdict.NOT_CHECKED.passes());
        assertEquals("not checked against read pairs", Verdict.NOT_CHECKED.summary());
    }

    // --- the pieces ---

    @Test
    void windowCountsCountTheFragmentsHoldingEachWholeWindow() {
        List<int[]> fragments = new ArrayList<>();
        fragments.add(new int[]{0, 6});
        fragments.add(new int[]{2, 10});
        fragments.add(new int[]{4, 7}); // shorter than the window

        assertArrayEquals(new int[]{1, 1, 2, 1, 1, 1, 1}, PairSupport.windowCounts(10, fragments, 4));
    }

    @Test
    void repeatNeedsReadsEnteringAtItsStartAndLeavingAtItsEndOnly() {
        int[] depth = new int[1000];
        java.util.Arrays.fill(depth, 40);
        int[] entering = new int[1001];
        int[] leaving = new int[1001];
        entering[300] = 12;
        leaving[800] = 10;

        assertArrayEquals(new int[]{300, 800, 12, 10}, PairSupport.unbridgedRepeat(depth, entering, leaving, 300));
        assertNull(PairSupport.unbridgedRepeat(depth, entering, leaving, 550), "shorter than the window: bridged");

        // Matches ending both ways at one place are a small insertion or deletion, not a repeat end.
        leaving[300] = 11;
        assertNull(PairSupport.unbridgedRepeat(depth, entering, leaving, 300));
        leaving[300] = 0;

        // Too few reads for the depth of the product (15% of 40 is 6).
        entering[300] = 5;
        assertNull(PairSupport.unbridgedRepeat(depth, entering, leaving, 300));
    }
}
