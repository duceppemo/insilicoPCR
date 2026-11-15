package ca.canada.inspection.gel;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelColorsTest {

    @Test
    void genesGetStableColorsInFirstAppearanceOrder() {
        Map<String, List<GelBand>> lanes = new LinkedHashMap<>();
        lanes.put("s1", List.of(new GelBand("s1", "alpha", 100), new GelBand("s1", "beta", 200)));
        lanes.put("s2", List.of(new GelBand("s2", "alpha", 100)));

        Map<String, Color> colors = GelColors.assignGeneColors(lanes);
        assertEquals(2, colors.size());
        assertEquals(GelColors.PALETTE[0], colors.get("alpha"));
        assertEquals(GelColors.PALETTE[1], colors.get("beta"));
    }

    @Test
    void genesPastThePaletteGetColoursOfTheirOwn() {
        Map<String, List<GelBand>> lanes = new LinkedHashMap<>();
        for (int i = 0; i < GelColors.PALETTE.length + 2; i++) {
            lanes.put("s" + i, List.of(new GelBand("s" + i, "gene" + i, 100)));
        }
        Map<String, Color> colors = GelColors.assignGeneColors(lanes);
        // They shared the palette's colours, and so a legend entry, with earlier genes.
        assertEquals(colors.size(), new java.util.HashSet<>(colors.values()).size());
    }

    @Test
    void sizeChecksHaveTheHtmlReportsColoursAndALegendInAFixedOrder() {
        List<GelBand> bands = List.of(new GelBand("a", "its", 500, ca.canada.inspection.pcr.ExpectedSizes.Label.UNEXPECTED),
                new GelBand("a", "its", 434, ca.canada.inspection.pcr.ExpectedSizes.Label.TARGET), new GelBand("b", "plain", 200));

        assertEquals(List.of("Target size", "Unexpected size", "No size declared"),
                List.copyOf(GelColors.sizeCheckLegend(bands).keySet()));
        assertEquals(Color.web("#008300"), GelColors.of(GelColorMode.SIZE_CHECK, bands.get(1), Map.of()));
        assertEquals(Color.web("#d03b3b"), GelColors.of(GelColorMode.SIZE_CHECK, bands.get(0), Map.of()));
        assertEquals(Color.web("#2a78d6"), GelColors.of(GelColorMode.SIZE_CHECK, bands.get(2), Map.of()));
        assertEquals(null, GelColors.of(GelColorMode.BLACK, bands.get(0), Map.of()));
        assertTrue(GelColors.legend(GelColorMode.BLACK, bands, Map.of()).isEmpty());
        assertEquals(List.of("its"), List.copyOf(GelColors.legend(GelColorMode.GENE, bands.subList(0, 2),
                Map.of("its", Color.RED, "plain", Color.BLUE)).keySet()), "only the genes shown");
    }
}
