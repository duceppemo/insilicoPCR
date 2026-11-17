package ca.canada.inspection.gel;

import java.util.Collection;

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


    /** Layout of the interactive viewer, sized to fill a window of the given size. */
    static GelLayout window(double sceneWidth, double sceneHeight, Collection<String> sampleNames) {
        int laneCount = sampleNames.size() + 1;
        double visibleWidth = Math.max(sceneWidth, 800);
        double visibleHeight = Math.max(sceneHeight, 500);
        double gelHeight = Math.max(540, visibleHeight * 1.10);
        double longestLabelWidth = GelScale.laneLabelWidth(sampleNames);
        double labelAreaHeight = labelAreaHeight(longestLabelWidth);
        double laneWidth = Math.max(74, (visibleWidth - LEFT_LABEL_WIDTH - RIGHT_INSET) / Math.max(laneCount, 11));
        double gelWidth = laneWidth * laneCount;
        double gelBottom = TOP_INSET + gelHeight;
        // Room for the slanted name of the last lane, which reaches past the gel's right edge.
        double width = Math.max(visibleWidth, LEFT_LABEL_WIDTH + gelWidth + rightMargin(longestLabelWidth, laneWidth));
        double height = gelBottom + labelAreaHeight;
        return new GelLayout(LEFT_LABEL_WIDTH, TOP_INSET, gelWidth, gelHeight, laneWidth, width, height,
                gelBottom + LABEL_OFFSET);
    }

    /**
     * Layout of the PNG image written without a window: fixed lane width and gel height, and a
     * picture that hugs the gel, leaving room for the slanted label of the last lane and for a
     * legend of {@code legendWidth} to the right of the gel.
     */
    static GelLayout image(Collection<String> sampleNames, double legendWidth, int legendRows) {
        int laneCount = sampleNames.size() + 1;
        double gelHeight = 560;
        double longestLabelWidth = GelScale.laneLabelWidth(sampleNames);
        double labelAreaHeight = labelAreaHeight(longestLabelWidth);
        double laneWidth = 110;
        double gelWidth = laneWidth * laneCount;
        double gelBottom = TOP_INSET + gelHeight;
        double legendX = LEFT_LABEL_WIDTH + gelWidth + 20;
        double width = LEFT_LABEL_WIDTH + gelWidth + rightMargin(longestLabelWidth, laneWidth);
        width = Math.max(width, legendX + legendWidth + 12);
        // Tall enough for the legend too, however many genes it lists.
        double height = Math.max(gelBottom + labelAreaHeight, TOP_INSET + legendHeight(legendRows) + 12);
        return new GelLayout(LEFT_LABEL_WIDTH, TOP_INSET, gelWidth, gelHeight, laneWidth, width, height,
                gelBottom + LABEL_OFFSET);
    }

    /** Height of the slanted lane names under the gel: long names are not cut. */
    private static double labelAreaHeight(double longestLabelWidth) {
        return Math.max(150, longestLabelWidth * 0.72 + 36);
    }

    /** Space right of the gel for the last lane's slanted name. */
    private static double rightMargin(double longestLabelWidth, double laneWidth) {
        return Math.max(RIGHT_INSET, longestLabelWidth * 0.72 - laneWidth / 2 + RIGHT_INSET);
    }

    /** Height of the gene legend drawn beside the PNG image, for {@code rows} genes. */
    static double legendHeight(int rows) {
        return rows == 0 ? 0 : 32 + rows * 20.0;
    }

    /** Width of the gene legend drawn beside the PNG image; 0 when there is no gene. */
    static double legendWidth(Collection<String> genes) {
        if (genes.isEmpty()) {
            return 0;
        }
        int longest = GelColorMode.GENE.legendTitle().length();
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
