package ca.canada.inspection.insilicopcr.gel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GelPagesTest {

    private static final List<String> SAMPLES = List.of("s1", "s2", "Alpha", "s4", "s5");

    @Test
    void pagesHoldAtMostSamplesPerGelSamples() {
        GelPages pages = new GelPages(SAMPLES, 2);
        assertEquals(3, pages.totalPages());
        assertEquals(List.of("s1", "s2"), pages.samples(0));
        assertEquals(List.of("Alpha", "s4"), pages.samples(1));
        assertEquals(List.of("s5"), pages.samples(2));
        assertEquals(4, pages.startIndex(2));
        assertEquals(5, pages.endIndex(2));
    }

    @Test
    void pageCountRoundsUpAndIsAtLeastOne() {
        assertEquals(2, new GelPages(List.of("a", "b", "c", "d"), 2).totalPages());
        assertEquals(1, new GelPages(SAMPLES, 100).totalPages());
        assertEquals(1, new GelPages(List.of(), 100).totalPages());
    }

    @Test
    void samplesPerGelMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new GelPages(SAMPLES, 0));
    }

    @Test
    void sampleNamesAreCopied() {
        List<String> names = new ArrayList<>(SAMPLES);
        GelPages pages = new GelPages(names, 2);
        names.clear();
        assertEquals(5, pages.sampleNames().size());
    }

    @Test
    void rangeTextNamesTheFirstAndLastSampleOfThePage() {
        GelPages pages = new GelPages(SAMPLES, 2);
        assertEquals("Samples 1–2 of 5 (s1 ... s2)", pages.rangeText(0));
        assertEquals("Samples 5–5 of 5 (s5 ... s5)", pages.rangeText(2));
    }

    @Test
    void findLooksAtSampleNamesGenesAndSizesWithoutCase() {
        Map<String, List<GelBand>> lanes = new LinkedHashMap<>();
        lanes.put("s1", List.of(new GelBand("s1", "toxA", 312)));
        lanes.put("s2", List.of());
        lanes.put("Alpha", List.of(new GelBand("Alpha", "stxQ", 118)));
        lanes.put("s4", List.of(new GelBand("s4", "TOXA", 583)));
        GelPages pages = new GelPages(SAMPLES, 2);

        assertEquals(new GelPages.Search(1, 1), pages.find(lanes, " alpha "));
        assertEquals(new GelPages.Search(0, 2), pages.find(lanes, "ToxA"));
        assertEquals(new GelPages.Search(1, 1), pages.find(lanes, "118"));
        // "Alpha" matches through its gene stxQ; "s5" has no lane but matches by name.
        assertEquals(new GelPages.Search(0, 5), pages.find(lanes, "s"));
        assertEquals(new GelPages.Search(2, 1), pages.find(lanes, "S5"));
        assertEquals(new GelPages.Search(-1, 0), pages.find(lanes, "nothing"));
    }

    @Test
    void blankSearchFindsNothing() {
        GelPages pages = new GelPages(SAMPLES, 2);
        assertEquals(new GelPages.Search(-1, 0), pages.find(Map.of(), null));
        assertEquals(new GelPages.Search(-1, 0), pages.find(Map.of(), "  "));
    }
}
