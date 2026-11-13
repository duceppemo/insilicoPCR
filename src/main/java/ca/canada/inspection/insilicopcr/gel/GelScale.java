package ca.canada.inspection.insilicopcr.gel;

import java.util.Collection;
import java.util.List;

/** Positions on the synthetic gel's log scale, the ladder, and the small per-band variations. */
final class GelScale {
    private static final int MIN_BP = 0;
    private static final int MAX_BP = 25_000;
    private static final double LOG_OFFSET_BP = 50.0;

    /** One ladder band: its size in base pairs and the label drawn beside it. */
    record LadderBand(int size, String label) {
    }

    /** Ladder bands from the top of the gel (largest) to the bottom. */
    static final List<LadderBand> LADDER = List.of(
            new LadderBand(20_000, "20kb"), new LadderBand(10_000, "10kb"), new LadderBand(7_000, "7kb"),
            new LadderBand(5_000, "5kb"), new LadderBand(4_000, "4kb"), new LadderBand(3_000, "3kb"),
            new LadderBand(2_000, "2kb"), new LadderBand(1_500, "1.5kb"), new LadderBand(1_000, "1kb"),
            new LadderBand(700, "700"), new LadderBand(500, "500"), new LadderBand(400, "400"),
            new LadderBand(300, "300"), new LadderBand(200, "200"), new LadderBand(100, "100"));

    private GelScale() {
    }

    /** Distance travelled by a band, from 0 (bottom of the gel, smallest) to 1 (top, largest). */
    static double normalizedPosition(int basePairs) {
        double clampedBasePairs = Math.max(MIN_BP, Math.min(MAX_BP, basePairs));
        double minLog = Math.log10(MIN_BP + LOG_OFFSET_BP);
        double maxLog = Math.log10(MAX_BP + LOG_OFFSET_BP);
        double valueLog = Math.log10(clampedBasePairs + LOG_OFFSET_BP);
        return Math.clamp((valueLog - minLog) / (maxLog - minLog), 0.0, 1.0);
    }

    /** Vertical centre of a band of the given size on a gel drawn from {@code gelTop} down. */
    static double ladderY(double gelTop, double gelHeight, int basePairs) {
        return gelTop + gelHeight - (gelHeight * normalizedPosition(basePairs));
    }

    /** Opacity of an interactive-viewer band: small and co-migrating amplicons look stronger. */
    static double sizeIntensity(int basePairs, int count) {
        double sizeFactor = 0.35 + (0.30 * (1.0 - normalizedPosition(basePairs)));
        double countFactor = Math.min(0.35, Math.max(0, count - 1) * 0.12);
        return Math.min(1.0, Math.max(0.30, sizeFactor + countFactor));
    }

    /** A value in [minimum, maximum] that depends only on {@code key}, so redraws look the same. */
    static double deterministicRange(String key, double minimum, double maximum) {
        int hash = key == null ? 0 : key.hashCode();
        double unit = ((hash & 0x7fffffff) % 10_000) / 9_999.0;
        return minimum + ((maximum - minimum) * unit);
    }

    /** Estimated width of a ladder label in the 10 pt Verdana used on the gel. */
    static double approximateTextWidth(String text) {
        return text.length() * 6.1;
    }

    /** Estimated width of the longest lane label (the ladder lane included) in 10 pt Verdana. */
    static double laneLabelWidth(Collection<String> laneNames) {
        double longest = "Ladder".length();
        for (String laneName : laneNames) {
            longest = Math.max(longest, laneName.length());
        }
        return longest * 6.2;
    }
}
