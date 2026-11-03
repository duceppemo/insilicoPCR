package ca.canada.inspection.insilicopcr.gel;

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
    void paletteWrapsAroundForManyGenes() {
        Map<String, List<GelBand>> lanes = new LinkedHashMap<>();
        for (int i = 0; i < GelColors.PALETTE.length + 2; i++) {
            lanes.put("s" + i, List.of(new GelBand("s" + i, "gene" + i, 100)));
        }
        Map<String, Color> colors = GelColors.assignGeneColors(lanes);
        assertEquals(GelColors.PALETTE[0], colors.get("gene" + GelColors.PALETTE.length));
    }

    @Test
    void bandIntensityGrowsWithCountAndSaturates() {
        assertEquals(0.45, GelColors.bandIntensity(1), 1e-9);
        assertEquals(0.45, GelColors.bandIntensity(0), 1e-9);
        assertTrue(GelColors.bandIntensity(3) > GelColors.bandIntensity(2));
        assertEquals(1.0, GelColors.bandIntensity(100), 1e-9);
    }
}
