package ca.canada.inspection.insilicopcr.gel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GelTextTest {

    private static final GelBand TOXA = new GelBand("isolate_01", "toxA", 312);
    private static final GelBand STXQ = new GelBand("isolate_01", "stxQ", 310);
    private static final GelBand TOXA_AGAIN = new GelBand("isolate_01", "toxA", 305);

    @Test
    void tooltipOfOneAmplicon() {
        assertEquals("Sample: isolate_01\nGene: toxA\nAmplicon size: 312 bp", GelText.tooltip(List.of(TOXA)));
    }

    @Test
    void tooltipOfCoMigratingAmplicons() {
        assertEquals("Sample: isolate_01\nCo-migrating amplicons: 2\ntoxA — 312 bp\nstxQ — 310 bp",
                GelText.tooltip(List.of(TOXA, STXQ)));
    }

    @Test
    void primaryGeneIsTheFirstAmpliconsGene() {
        assertEquals("stxQ", GelText.primaryGene(List.of(STXQ, TOXA)));
        assertEquals("", GelText.primaryGene(List.of()));
        assertEquals("", GelText.primaryGene(null));
    }

    @Test
    void bandSummaries() {
        List<GelBand> bands = List.of(TOXA, STXQ, TOXA_AGAIN);
        assertEquals("isolate_01", GelText.sampleName(bands));
        assertEquals("", GelText.sampleName(List.of()));
        assertEquals("toxA, stxQ", GelText.geneNames(bands));
        assertEquals("312 bp, 310 bp, 305 bp", GelText.ampliconSizes(bands));
        assertEquals("", GelText.geneNames(List.of()));
    }

    @Test
    void detailsListTheRowsAfterTheTooltip() {
        List<GelBand> bands = List.of(TOXA, STXQ);
        assertEquals("tip\n\nRows:\nisolate_01\ttoxA\t312 bp\nisolate_01\tstxQ\t310 bp", GelText.details("tip", bands));
        assertEquals("No band details available.", GelText.details(null, bands));
        assertEquals("No band details available.", GelText.details("  ", bands));
    }

    @Test
    void searchTextHoldsSampleGeneAndSizeInLowerCase() {
        assertEquals("isolate_01 toxa 312 isolate_01 stxq 310", GelText.searchText(List.of(TOXA, STXQ)));
        assertEquals("", GelText.searchText(List.of()));
    }

    @Test
    void queriesAreTrimmedAndLowerCased() {
        assertEquals("", GelText.normalizeQuery(null));
        assertEquals("", GelText.normalizeQuery("   "));
        assertEquals("toxa 3", GelText.normalizeQuery("  ToxA 3\t"));
    }

    @Test
    void selectionText() {
        List<List<GelBand>> selected = new ArrayList<>();
        assertEquals("No band selected", GelText.selection(selected));
        selected.add(List.of(TOXA, STXQ));
        assertEquals("Selected: isolate_01 / toxA, stxQ / 312 bp, 310 bp", GelText.selection(selected));
        selected.add(List.of(TOXA_AGAIN));
        assertEquals("Selected bands: 2", GelText.selection(selected));
    }

    @Test
    void matchCounts() {
        assertEquals("0 matches", GelText.matches(0));
        assertEquals("1 match", GelText.matches(1));
        assertEquals("12 matches", GelText.matches(12));
    }
}
