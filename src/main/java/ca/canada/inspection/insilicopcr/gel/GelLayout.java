package ca.canada.inspection.insilicopcr.gel;

import java.util.Collection;
import java.util.List;

/**
 * Geometry of a synthetic gel picture: where the gel sits, how wide a lane is, the size of the
 * whole picture and the baseline of the slanted lane labels. Lane 0 is the ladder.
 */
record GelLayout(double gelLeft,
                 double gelTop,
                 double gelWidth,
                 double gelHeight,
                 double laneWidth,
                 double width,
                 double height,
                 double labelY) {

    private static final double LEFT_LABEL_WIDTH = 82;
    private static final double TOP_INSET = 18;
    private static final double RIGHT_INSET = 18;
    private static final double LABEL_OFFSET = 34;

    static final double PAGE_LABEL_FONT_SIZE = 14.0;
    private static final double PAGE_LABEL_START_OFFSET = 48.0;
    private static final double PAGE_LABEL_MARGIN = 56.0;

    /** Layout of the interactive viewer, sized to fill a window of the given size. */
    static GelLayout window(double sceneWidth, double sceneHeight, Collection<String> sampleNames) {
        int laneCount = sampleNames.size() + 1;
        double visibleWidth = Math.max(sceneWidth, 800);
        double visibleHeight = Math.max(sceneHeight, 500);
        double gelHeight = Math.max(540, visibleHeight * 1.10);
        double labelAreaHeight = Math.max(150, Math.min(360, GelScale.laneLabelWidth(sampleNames) * 0.72 + 36));
        double laneWidth = Math.max(74, (visibleWidth - LEFT_LABEL_WIDTH - RIGHT_INSET) / Math.max(laneCount, 11));
        double gelWidth = laneWidth * laneCount;
        double gelBottom = TOP_INSET + gelHeight;
        double width = Math.max(visibleWidth, LEFT_LABEL_WIDTH + gelWidth + RIGHT_INSET);
        double height = gelBottom + labelAreaHeight;
        return new GelLayout(LEFT_LABEL_WIDTH, TOP_INSET, gelWidth, gelHeight, laneWidth, width, height,
                gelBottom + LABEL_OFFSET);
    }

    /**
     * Layout of the PNG image written without a window: fixed lane width and gel height, and a
     * picture that hugs the gel, leaving room for the slanted label of the last lane and for a
     * legend of {@code legendWidth} to the right of the gel.
     */
    static GelLayout image(Collection<String> sampleNames, double legendWidth) {
        int laneCount = sampleNames.size() + 1;
        double gelHeight = 560;
        double longestLabelWidth = GelScale.laneLabelWidth(sampleNames);
        double labelAreaHeight = Math.max(150, Math.min(360, longestLabelWidth * 0.72 + 36));
        double laneWidth = 110;
        double gelWidth = laneWidth * laneCount;
        double gelBottom = TOP_INSET + gelHeight;
        double legendX = LEFT_LABEL_WIDTH + gelWidth + 20;
        double width = LEFT_LABEL_WIDTH + gelWidth + Math.max(RIGHT_INSET, longestLabelWidth * 0.72 - laneWidth / 2);
        width = Math.max(width, legendX + legendWidth + 12);
        double height = gelBottom + labelAreaHeight;
        return new GelLayout(LEFT_LABEL_WIDTH, TOP_INSET, gelWidth, gelHeight, laneWidth, width, height,
                gelBottom + LABEL_OFFSET);
    }

    /** Layout of one page of the paginated viewer, whose lane labels use a larger font. */
    static GelLayout page(double sceneWidth, double sceneHeight, List<String> sampleNames) {
        int laneCount = sampleNames.size() + 1;
        double labelWidth = Math.max("Ladder".length(), sampleNames.stream().mapToInt(String::length).max().orElse(10))
                * PAGE_LABEL_FONT_SIZE * 0.62;
        double labelFootprint = (labelWidth + PAGE_LABEL_FONT_SIZE) / Math.sqrt(2.0);
        double rightInset = Math.max(64, labelFootprint + PAGE_LABEL_MARGIN);
        double labelAreaHeight = PAGE_LABEL_START_OFFSET + labelFootprint + PAGE_LABEL_MARGIN;
        double laneWidth = Math.max(74, (Math.max(sceneWidth, 800) - LEFT_LABEL_WIDTH - rightInset) / Math.max(laneCount, 11));
        double gelHeight = Math.max(540, Math.max(sceneHeight, 500) * 1.10);
        double gelWidth = laneWidth * laneCount;
        double gelBottom = TOP_INSET + gelHeight;
        double width = Math.max(900, LEFT_LABEL_WIDTH + gelWidth + rightInset);
        double height = gelBottom + labelAreaHeight;
        return new GelLayout(LEFT_LABEL_WIDTH, TOP_INSET, gelWidth, gelHeight, laneWidth, width, height,
                gelBottom + PAGE_LABEL_START_OFFSET);
    }

    /** Width of the gene legend drawn beside the PNG image; 0 when there is no gene. */
    static double legendWidth(Collection<String> genes) {
        if (genes.isEmpty()) {
            return 0;
        }
        int longest = "Gene legend".length();
        for (String gene : genes) {
            longest = Math.max(longest, gene.length());
        }
        return Math.max(130, longest * 6.8 + 48);
    }

    /** Left edge of lane {@code lane}; lane 0 is the ladder. */
    double laneX(int lane) {
        return gelLeft + (lane * laneWidth);
    }

    /** Horizontal centre of lane {@code lane}, where its slanted label starts. */
    double laneCenterX(int lane) {
        return gelLeft + (lane * laneWidth) + (laneWidth / 2);
    }

    /** Bottom edge of the gel. */
    double gelBottom() {
        return gelTop + gelHeight;
    }

    /** Vertical centre of a band of the given size. */
    double bandY(int basePairs) {
        return GelScale.ladderY(gelTop, gelHeight, basePairs);
    }

    /** Left edge of the PNG image's gene legend. */
    double legendX() {
        return gelLeft + gelWidth + 20;
    }
}
