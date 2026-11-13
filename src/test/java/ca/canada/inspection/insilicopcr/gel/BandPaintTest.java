package ca.canada.inspection.insilicopcr.gel;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BandPaintTest {

    private static final Color GENE = Color.rgb(31, 119, 180);

    @Test
    void lanesAlternateBetweenTwoGreys() {
        assertEquals(Color.rgb(244, 244, 244), BandPaint.laneFill(0));
        assertEquals(Color.rgb(232, 232, 232), BandPaint.laneFill(1));
        assertEquals(Color.rgb(244, 244, 244), BandPaint.laneFill(6));
    }

    @Test
    void bandDecorationsFollowTheIntensityWithCaps() {
        assertEquals(Color.rgb(25, 25, 25, 0.1), BandPaint.halo(0.5));
        assertEquals(0.07, BandPaint.highlight(0.5).getOpacity(), 1e-6);
        assertEquals(0.18, BandPaint.highlight(2.0).getOpacity(), 1e-6);
        assertEquals(0.12, BandPaint.shadow(0.5).getOpacity(), 1e-6);
        assertEquals(0.32, BandPaint.shadow(1.5).getOpacity(), 1e-6);
        assertEquals(Color.WHITE.getRed(), BandPaint.highlight(0.5).getRed(), 1e-6);
        assertEquals(0.0, BandPaint.shadow(0.5).getRed(), 1e-6);
    }

    @Test
    void withOpacityKeepsTheColour() {
        Color color = BandPaint.withOpacity(GENE, 0.4);
        assertEquals(GENE.getRed(), color.getRed(), 1e-6);
        assertEquals(GENE.getGreen(), color.getGreen(), 1e-6);
        assertEquals(GENE.getBlue(), color.getBlue(), 1e-6);
        assertEquals(0.4, color.getOpacity(), 1e-6);
    }

    @Test
    void imageBandsAreDarkerShadesOfTheGeneColour() {
        assertEquals(GENE.getBrightness() * 0.35, BandPaint.imageHalo(GENE, 0.5).getBrightness(), 1e-4);
        assertEquals(0.11, BandPaint.imageHalo(GENE, 0.5).getOpacity(), 1e-6);
        assertEquals(GENE.getBrightness() * 0.75, BandPaint.imageBody(GENE, 0.5).getBrightness(), 1e-4);
        assertEquals(0.775, BandPaint.imageBody(GENE, 0.5).getOpacity(), 1e-6);
        assertEquals(1.0, BandPaint.imageBody(GENE, 1.0).getOpacity(), 1e-6);
        assertEquals(GENE.getHue(), BandPaint.imageBody(GENE, 0.5).getHue(), 1e-3);
        assertEquals(GENE.getBrightness() * 0.25, BandPaint.imageShadow(GENE, 0.5).getBrightness(), 1e-4);
        assertEquals(0.12, BandPaint.imageShadow(GENE, 0.5).getOpacity(), 1e-6);
        assertEquals(0.32, BandPaint.imageShadow(GENE, 2.0).getOpacity(), 1e-6);
    }

    @Test
    void restingBandIsDarkGreyWithoutOutline() {
        BandPaint.State state = BandPaint.State.of(false, false, false, true, false, GENE, 0.6);
        assertEquals(1.0, state.opacity(), 1e-6);
        assertEquals(Color.TRANSPARENT, state.laneFill());
        assertEquals(BandPaint.withOpacity(BandPaint.BAND, 0.6), state.bandFill());
        assertNull(state.stroke());
        assertEquals(BandPaint.halo(0.6), state.halo());
    }

    @Test
    void colourByGeneUsesTheGeneColour() {
        BandPaint.State state = BandPaint.State.of(false, false, false, true, true, GENE, 0.6);
        assertEquals(BandPaint.withOpacity(GENE, 0.6), state.bandFill());
    }

    @Test
    void bandsThatDoNotMatchASearchFade() {
        BandPaint.State state = BandPaint.State.of(false, false, true, false, false, GENE, 0.6);
        assertEquals(0.18, state.opacity(), 1e-6);
        assertNull(state.stroke());
    }

    @Test
    void searchMatchesGetAGreenOutline() {
        BandPaint.State state = BandPaint.State.of(false, false, true, true, false, GENE, 0.6);
        assertEquals(BandPaint.SEARCH_MATCH, state.stroke());
        assertEquals(1.25, state.strokeWidth(), 1e-6);
        assertEquals(Color.rgb(60, 180, 75, 0.20), state.halo());
        assertEquals(Color.TRANSPARENT, state.laneFill());
    }

    @Test
    void hoverGetsABlueOutlineAndLightensTheLane() {
        BandPaint.State state = BandPaint.State.of(true, false, true, true, false, GENE, 0.6);
        assertEquals(BandPaint.HOVER, state.stroke());
        assertEquals(1.25, state.strokeWidth(), 1e-6);
        assertEquals(Color.rgb(30, 144, 255, 0.25), state.halo());
        assertEquals(Color.rgb(30, 144, 255, 0.10), state.laneFill());
    }

    @Test
    void selectionWinsOverHover() {
        BandPaint.State state = BandPaint.State.of(true, true, true, true, false, GENE, 0.6);
        assertEquals(BandPaint.SELECTED, state.stroke());
        assertEquals(2.0, state.strokeWidth(), 1e-6);
        assertEquals(Color.rgb(255, 140, 0, 0.28), state.halo());
        assertEquals(Color.rgb(30, 144, 255, 0.18), state.laneFill());
    }
}
