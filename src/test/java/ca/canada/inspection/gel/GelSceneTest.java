package ca.canada.inspection.gel;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of a gel picture, the same for every way of drawing it. */
class GelSceneTest {

    private static final Color GENE = Color.rgb(31, 119, 180);

    @Test
    void aSceneHoldsTheGelLadderBandsAndLabels() {
        List<String> samples = List.of("s1", "s2");
        GelLayout layout = GelLayout.page(1000, 700, samples);
        List<LaneBand> bands = LaneBand.page(layout, 1, List.of(new GelBand("s1", "toxA", 312)));

        GelScene scene = GelScene.of(layout, samples, bands, band -> GENE, GelLayout.PAGE_LABEL_FONT_SIZE);

        assertEquals(layout.width(), scene.width());
        GelScene.Box gel = scene.background().get(1);
        assertEquals(BandPaint.GEL, gel.fill());
        assertEquals(layout.gelLeft(), gel.x());
        // A lane and the white line at its left edge, for the ladder and each sample.
        assertEquals(BandPaint.laneFill(2), scene.background().get(2 + 2 * 2).fill());
        assertEquals(15, scene.ladder().size());
        assertNull(scene.ladder().getFirst().lane());
        assertEquals(GelScene.Anchor.END, scene.ladderLabels().getFirst().anchor());
        assertEquals(layout.gelLeft() - 8, scene.ladderLabels().getFirst().x());

        assertEquals(1, scene.bands().size());
        GelScene.Band band = scene.bands().getFirst();
        assertSame(bands.getFirst(), band.lane());
        assertEquals(GENE, band.color());
        assertTrue(band.title().startsWith("Sample: s1"), band.title());

        assertEquals(List.of("Ladder", "s1", "s2"), scene.laneLabels().stream().map(GelScene.Label::text).toList());
        assertEquals(GelScene.Anchor.SLANTED, scene.laneLabels().getLast().anchor());
        assertEquals(GelLayout.PAGE_LABEL_FONT_SIZE, scene.laneLabels().getLast().fontSize());
        assertNull(scene.border().fill());
        assertEquals(3.0, scene.border().strokeWidth());
    }

    @Test
    void aBandIsAHaloAroundItAndLinesAlongItsEdges() {
        LaneBand.BandRect rect = new LaneBand.BandRect(10, 100, 40, 4);
        GelScene.Band band = new GelScene.Band(null, rect, GENE, 0.5, null);

        List<GelScene.Box> boxes = band.boxes();

        assertEquals(new GelScene.Box(8.5, 98, 43, 8, BandPaint.halo(GENE, 0.5), null, 0), boxes.get(0));
        assertEquals(new GelScene.Box(10, 100, 40, 4, BandPaint.body(GENE, 0.5), null, 0), boxes.get(1));
        assertEquals(100.5, boxes.get(2).y());
        assertEquals(103.25, boxes.get(3).y());
        assertEquals(BandPaint.shadow(GENE, 0.5), boxes.get(3).fill());
    }
}
