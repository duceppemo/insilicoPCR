package ca.canada.inspection.insilicopcr.gel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelLayoutTest {

    private static List<String> samples(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            names.add("s" + i);
        }
        return names;
    }

    @Test
    void windowLayoutFillsTheWindowWithAtLeastElevenLaneWidths() {
        GelLayout layout = GelLayout.window(1000, 700, samples(3));
        assertEquals(82, layout.gelLeft(), 1e-9);
        assertEquals(18, layout.gelTop(), 1e-9);
        assertEquals(770, layout.gelHeight(), 1e-9);
        assertEquals((1000 - 82 - 18) / 11.0, layout.laneWidth(), 1e-9);
        assertEquals(4 * layout.laneWidth(), layout.gelWidth(), 1e-9);
        assertEquals(1000, layout.width(), 1e-9);
        assertEquals(18 + 770 + 150, layout.height(), 1e-9);
        assertEquals(18 + 770 + 34, layout.labelY(), 1e-9);
    }

    @Test
    void windowLayoutHasAMinimumSize() {
        GelLayout layout = GelLayout.window(300, 200, samples(1));
        assertEquals(550, layout.gelHeight(), 1e-9);
        assertEquals(74, layout.laneWidth(), 1e-9);
        assertEquals(800, layout.width(), 1e-9);
    }

    @Test
    void windowLayoutGrowsWiderThanTheWindowForManyLanes() {
        GelLayout layout = GelLayout.window(800, 500, samples(20));
        assertEquals(74, layout.laneWidth(), 1e-9);
        assertEquals(74 * 21, layout.gelWidth(), 1e-9);
        assertEquals(82 + 74 * 21 + 18, layout.width(), 1e-9);
    }

    @Test
    void longSampleNamesGrowTheLabelAreaUpToALimit() {
        String name40 = "x".repeat(40);
        GelLayout medium = GelLayout.window(800, 500, List.of(name40));
        assertEquals(40 * 6.2 * 0.72 + 36, medium.height() - medium.gelBottom(), 1e-9);
        GelLayout longest = GelLayout.window(800, 500, List.of("x".repeat(200)));
        assertEquals(360, longest.height() - longest.gelBottom(), 1e-9);
    }

    @Test
    void imageLayoutHasFixedLanesAndHugsTheGel() {
        GelLayout layout = GelLayout.image(samples(2), 0);
        assertEquals(110, layout.laneWidth(), 1e-9);
        assertEquals(560, layout.gelHeight(), 1e-9);
        assertEquals(330, layout.gelWidth(), 1e-9);
        // Room is always kept for the legend's margin, even without genes.
        assertEquals(82 + 330 + 20 + 12, layout.width(), 1e-9);
        assertEquals(18 + 560 + 150, layout.height(), 1e-9);
        assertEquals(18 + 560 + 34, layout.labelY(), 1e-9);
    }

    @Test
    void imageLayoutMakesRoomForTheLegendAndTheLastSlantedLabel() {
        GelLayout withLegend = GelLayout.image(samples(2), 130);
        assertEquals(82 + 330 + 20, withLegend.legendX(), 1e-9);
        assertEquals(82 + 330 + 20 + 130 + 12, withLegend.width(), 1e-9);

        String name = "y".repeat(60);
        GelLayout longLabel = GelLayout.image(List.of(name), 0);
        assertEquals(82 + 220 + (60 * 6.2 * 0.72 - 55), longLabel.width(), 1e-9);
    }

    @Test
    void pageLayoutLeavesRoomForLargeSlantedLabels() {
        GelLayout layout = GelLayout.page(800, 500, List.of("abc"));
        double labelWidth = 6 * 14.0 * 0.62;
        double footprint = (labelWidth + 14.0) / Math.sqrt(2.0);
        assertEquals(74, layout.laneWidth(), 1e-9);
        assertEquals(148, layout.gelWidth(), 1e-9);
        assertEquals(550, layout.gelHeight(), 1e-9);
        assertEquals(900, layout.width(), 1e-9);
        assertEquals(18 + 550 + 48 + footprint + 56, layout.height(), 1e-9);
        assertEquals(18 + 550 + 48, layout.labelY(), 1e-9);
    }

    @Test
    void pageLayoutUsesTheWindowSizeAndKeepsTheLabelFootprintOnTheRight() {
        GelLayout wide = GelLayout.page(2000, 900, samples(3));
        double rightInset = (6 * 14.0 * 0.62 + 14.0) / Math.sqrt(2.0) + 56;
        assertEquals((2000 - 82 - rightInset) / 11.0, wide.laneWidth(), 1e-9);
        assertEquals(990, wide.gelHeight(), 1e-9);
        assertEquals(900, wide.width(), 1e-9);

        GelLayout manyLanes = GelLayout.page(800, 500, samples(30));
        assertEquals(82 + 74 * 31 + rightInset, manyLanes.width(), 1e-9);

        String name = "z".repeat(30);
        GelLayout longNames = GelLayout.page(2000, 900, List.of(name));
        double footprint = (30 * 14.0 * 0.62 + 14.0) / Math.sqrt(2.0);
        assertEquals((2000 - 82 - (footprint + 56)) / 11.0, longNames.laneWidth(), 1e-9);
    }

    @Test
    void pageLayoutOfAnEmptyPageAssumesTenCharacterNames() {
        GelLayout layout = GelLayout.page(800, 500, List.of());
        double footprint = (10 * 14.0 * 0.62 + 14.0) / Math.sqrt(2.0);
        assertEquals(18 + 550 + 48 + footprint + 56, layout.height(), 1e-9);
    }

    @Test
    void legendWidthDependsOnTheLongestGeneName() {
        assertEquals(0, GelLayout.legendWidth(List.of()), 1e-9);
        assertEquals(130, GelLayout.legendWidth(List.of("toxA", "stxQ")), 1e-9);
        assertEquals(30 * 6.8 + 48, GelLayout.legendWidth(List.of("g".repeat(30))), 1e-9);
    }

    @Test
    void laneAndBandCoordinates() {
        GelLayout layout = GelLayout.image(samples(3), 0);
        assertEquals(82, layout.laneX(0), 1e-9);
        assertEquals(82 + 2 * 110, layout.laneX(2), 1e-9);
        assertEquals(82 + 55, layout.laneCenterX(0), 1e-9);
        assertEquals(82 + 3 * 110 + 55, layout.laneCenterX(3), 1e-9);
        assertEquals(578, layout.gelBottom(), 1e-9);
        assertEquals(GelScale.ladderY(18, 560, 700), layout.bandY(700), 1e-12);
        assertTrue(layout.bandY(100) > layout.bandY(10_000));
    }
}
