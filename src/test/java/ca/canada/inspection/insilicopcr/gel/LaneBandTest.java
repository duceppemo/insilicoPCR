package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.gel.LaneBand.BandRect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaneBandTest {

    private static final GelLayout LAYOUT = GelLayout.image(List.of("s1", "s2"), 0);

    /** Bands of one lane, largest first, as {@link GelReportReader} returns them. */
    private static final List<GelBand> LANE = List.of(
            new GelBand("s1", "big", 1_024),
            new GelBand("s1", "toxA", 312),
            new GelBand("s1", "stxQ", 310),
            new GelBand("s1", "toxA", 305));

    @Test
    void bandRectIsTwoThirdsOfTheLaneAndCentred() {
        BandRect rect = BandRect.inLane(100, 50, 110, 4);
        assertEquals(110 * 0.66, rect.width(), 1e-9);
        assertEquals(100 + (110 - 110 * 0.66) / 2, rect.x(), 1e-9);
        assertEquals(48, rect.y(), 1e-9);
        assertEquals(4, rect.height(), 1e-9);
    }

    @Test
    void bandRectIsAtLeastFourPixelsWide() {
        BandRect rect = BandRect.inLane(100, 50, 5, 2);
        assertEquals(4, rect.width(), 1e-9);
        assertEquals(100.5, rect.x(), 1e-9);
    }

    @Test
    void windowBandsGroupCoMigratingAmpliconsFromSmallToLarge() {
        List<LaneBand> bands = LaneBand.window(LAYOUT, 1, "s1", LANE, 2.0);
        assertEquals(2, bands.size());

        LaneBand small = bands.get(0);
        assertEquals(1, small.lane());
        assertEquals(LAYOUT.laneX(1), small.laneX(), 1e-12);
        assertEquals(3, small.bands().size());
        assertEquals("toxA", small.gene());
        assertTrue(Math.abs(small.centerY() - LAYOUT.bandY(300)) <= 0.45);
        double expected = Math.min(1.0, GelScale.sizeIntensity(300, 3) * GelScale.deterministicRange("s1", 0.90, 1.10));
        assertEquals(expected, small.intensity(), 1e-12);
        assertEquals(2.0 + expected * 1.2, small.rect().height(), 1e-12);
        assertEquals(small.centerY() - small.rect().height() / 2, small.rect().y(), 1e-12);

        LaneBand large = bands.get(1);
        assertEquals(List.of(LANE.getFirst()), large.bands());
        assertEquals("big", large.gene());
        assertTrue(large.centerY() < small.centerY(), "larger amplicons sit higher on the gel");
    }

    @Test
    void windowBandsVaryByLaneButNotBetweenDraws() {
        List<GelBand> other = List.of(new GelBand("s2", "toxA", 312));
        LaneBand first = LaneBand.window(LAYOUT, 2, "s2", other, 2.0).getFirst();
        LaneBand again = LaneBand.window(LAYOUT, 2, "s2", other, 2.0).getFirst();
        assertEquals(first, again);
        assertEquals(LAYOUT.bandY(300) + GelScale.deterministicRange("s2:300", -0.45, 0.45), first.centerY(), 1e-12);
    }

    @Test
    void imageBandsDrawOneBandPerGeneAtASharedSize() {
        List<LaneBand> bands = LaneBand.image(LAYOUT, 1, "s1", LANE, 2.0);
        assertEquals(3, bands.size());

        // Sizes keep the lane order (largest first); genes at a size are sorted by name.
        assertEquals("big", bands.get(0).gene());
        assertEquals("stxQ", bands.get(1).gene());
        assertEquals("toxA", bands.get(2).gene());
        assertEquals(1, bands.get(1).bands().size());
        assertEquals(2, bands.get(2).bands().size());

        double jitter = GelScale.deterministicRange("s1:300", -0.45, 0.45);
        assertEquals(LAYOUT.bandY(300) + jitter - 1.5, bands.get(1).centerY(), 1e-12);
        assertEquals(LAYOUT.bandY(300) + jitter + 1.5, bands.get(2).centerY(), 1e-12);
        assertEquals(LAYOUT.bandY(1_000) + GelScale.deterministicRange("s1:1000", -0.45, 0.45), bands.get(0).centerY(), 1e-12);

        assertEquals(GelColors.bandIntensity(1), bands.get(1).intensity(), 1e-12);
        assertEquals(GelColors.bandIntensity(2), bands.get(2).intensity(), 1e-12);
        assertEquals(2.0 + GelColors.bandIntensity(2) * 1.2, bands.get(2).rect().height(), 1e-12);
        assertEquals(110 * 0.66, bands.get(2).rect().width(), 1e-12);
    }

    @Test
    void imageBandOfASingleGeneIsNotOffset() {
        List<GelBand> lane = List.of(new GelBand("s2", "toxA", 500));
        LaneBand band = LaneBand.image(LAYOUT, 2, "s2", lane, 2.0).getFirst();
        assertEquals(LAYOUT.bandY(500) + GelScale.deterministicRange("s2:500", -0.45, 0.45), band.centerY(), 1e-12);
        assertEquals(LAYOUT.laneX(2), band.laneX(), 1e-12);
    }

    @Test
    void pageBandsSitExactlyOnTheScale() {
        List<LaneBand> bands = LaneBand.page(LAYOUT, 1, LANE);
        assertEquals(2, bands.size());
        LaneBand small = bands.getFirst();
        assertEquals(LAYOUT.bandY(300), small.centerY(), 0.0);
        assertEquals(GelColors.bandIntensity(3), small.intensity(), 1e-12);
        assertEquals(2.0 + GelColors.bandIntensity(3), small.rect().height(), 1e-12);
        assertEquals("toxA", small.gene());
        assertEquals(GelText.tooltip(small.bands()), small.tooltip());
    }

    @Test
    void emptyLanesHaveNoBands() {
        assertTrue(LaneBand.window(LAYOUT, 1, "s1", List.of(), 2.0).isEmpty());
        assertTrue(LaneBand.image(LAYOUT, 1, "s1", List.of(), 2.0).isEmpty());
        assertTrue(LaneBand.page(LAYOUT, 1, List.of()).isEmpty());
    }
}
