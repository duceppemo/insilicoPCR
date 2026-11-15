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
}
