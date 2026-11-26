package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ReportRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelTextTest {

    private static final GelBand TOXA = new GelBand("isolate_01", "toxA", 312);
    private static final GelBand STXQ = new GelBand("isolate_01", "stxQ", 310);
    private static final GelBand TOXA_AGAIN = new GelBand("isolate_01", "toxA", 305);

    @Test
    void tooltipOfOneAmplicon() {
        assertEquals("Sample: isolate_01\nAssay: toxA\nAmplicon size: 312 bp", GelText.tooltip(List.of(TOXA)));
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

    /** GamelRos as a G. rostochiensis assembly gives it: one reverse base unpaired, its site at 51.2 °C. */
    private static final ReportRow GAMEL = new ReportRow("1BC", "GamelRos",
            new ReportRow.Site(961, 1119, 159, "contig_3", "", '+'),
            new ReportRow.Binding("GamelRos-F", 0, 0, 0, 58.9), new ReportRow.Binding("GamelRos-R", 0, 0, 1, 51.2),
            new ReportRow.Probe("GamelRos-P", 1044, 1065, 0, 22, 69.1), ReportRow.Source.ASSEMBLY);

    /** BMros from reads: a forward mismatch (none in the 3' window) and 4 unpaired bases, no probe. */
    private static final ReportRow BM = new ReportRow("1BC", "BMros",
            new ReportRow.Site(1, 160, 160, "read_7", "Read-level call: ...", '-'),
            new ReportRow.Binding("BMros-F", 1, 0, 4, 51.5), new ReportRow.Binding("BMros-R", 2, 1, 0, 67.4),
            null, new ReportRow.Source.Reads(144, 150, "96%"));

    @Test
    void aBandShowsHowEachOligoBindsAndWhatItWasCalledOn() {
        GelBand band = GelBand.of(GAMEL, ca.canada.inspection.pcr.ExpectedSizes.Label.TARGET);

        assertEquals("""
                Sample: 1BC
                Assay: GamelRos
                Amplicon size: 159 bp
                Size check: target
                Forward GamelRos-F: no mismatch, Tm 58.9 °C
                Reverse GamelRos-R: 1 unpaired 5' base, Tm 51.2 °C
                Probe GamelRos-P: no mismatch, Tm 69.1 °C
                Weakest site: GamelRos-R, 51.2 °C
                Called on: assembly""", GelText.tooltip(List.of(band)));
        assertEquals("tip\nOn: contig_3, 961-1119, + strand\n\nRows:\n1BC\tGamelRos\t159 bp\ttarget",
                GelText.details("tip", List.of(band)));
    }

    @Test
    void coMigratingAmpliconsEachGiveTheirWeakestSiteAndTheDetailsHowEachBinds() {
        List<GelBand> bands = List.of(GelBand.of(GAMEL, null), GelBand.of(BM, null));

        assertEquals("""
                Sample: 1BC
                Co-migrating amplicons: 2
                GamelRos — 159 bp, weakest site 51.2 °C, 1 unpaired 5' base
                BMros — 160 bp, weakest site 51.5 °C, 3 mismatches and indels (1 in the 3' window), 4 unpaired 5' bases""",
                GelText.tooltip(bands));
        String details = GelText.details("tip", bands);
        assertTrue(details.contains("""

                BMros — 160 bp
                  Forward BMros-F: 1 mismatch or indel, 4 unpaired 5' bases, Tm 51.5 °C
                  Reverse BMros-R: 2 mismatches and indels (1 in the 3' window), Tm 67.4 °C
                  Weakest site: BMros-F, 51.5 °C
                  Called on: reads, the whole amplicon in 144 of 150 informative reads (96%)
                  On: read_7, 1-160, - strand
                  Description: Read-level call: ..."""), details);
    }

    @Test
    void aReportWithoutTmsShowsNone() {
        // A 1.x report: no Tm columns, so no Tm and no weakest site.
        ReportRow old = new ReportRow("s", "a", new ReportRow.Site(1, 100, 100, "c", "", ' '),
                new ReportRow.Binding("a-F", 1, 0, 0), new ReportRow.Binding("a-R", 0, 0, 0), null,
                new ReportRow.Source.Extension(5, 10, 3, 8));
        assertEquals("""
                Sample: s
                Assay: a
                Amplicon size: 100 bp
                Forward a-F: 1 mismatch or indel
                Reverse a-R: no mismatch
                Called on: extended reads, 5 of 10 forward-seeded and 3 of 8 reverse-seeded reaching the other \
                primer""", GelText.tooltip(List.of(GelBand.of(old, null))));
        assertEquals("tip\nOn: c, 1-100\n\nRows:\ns\ta\t100 bp", GelText.details("tip", List.of(GelBand.of(old, null))));
    }

    @Test
    void aSearchFindsTheBandsOfAPrimerOrProbe() {
        String text = GelText.searchText(List.of(GelBand.of(GAMEL, null)));
        assertTrue(text.contains("gamelros-r"), text);
        assertTrue(text.contains("gamelros-p"), text);
        assertFalse(GelText.searchText(List.of(GelBand.of(BM, null))).contains("-p"));
    }

    @Test
    void differencesNameOnlyWhatTheSiteHas() {
        assertEquals("no mismatch", GelText.differences(0, 0, 0));
        assertEquals("2 mismatches and indels (1 in the 3' window), 1 unpaired 5' base",
                GelText.differences(2, 1, 1));
        assertEquals("1 mismatch or indel", GelText.differences(1, 0, 0));
        assertEquals("1 in the 3' window", GelText.differences(0, 1, 0));
    }

    @Test
    void aQueryIsLowerCasedAsTheBandTextIs() {
        // In Turkish, "I" lower-cases to a dotless i: a query for ITS found no band, whose text is in Locale.ROOT.
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals("its", GelText.normalizeQuery("ITS"));
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void thePopupOpensBesideThePointerWhereItFits() {
        // A 300-wide popup, the pointer at 500 on a screen from 0 to 1000: after it; at 900, before it.
        assertEquals(514, InteractiveGelViewer.beside(500, 14, 300, 0, 1000));
        assertEquals(586, InteractiveGelViewer.beside(900, 14, 300, 0, 1000));
        // 700 wide, fitting on neither side: on the side with more room, never over the pointer.
        assertEquals(314, InteractiveGelViewer.beside(300, 14, 700, 0, 1000));
        assertEquals(-14, InteractiveGelViewer.beside(700, 14, 700, 0, 1000));
    }

    @Test
    void theSizeCheckIsShownAndSearched() {
        GelBand unexpected = new GelBand("s", "its", 500, ca.canada.inspection.pcr.ExpectedSizes.Label.UNEXPECTED);
        GelBand plain = new GelBand("s", "plain", 200);

        assertEquals("Sample: s\nAssay: its\nAmplicon size: 500 bp\nSize check: unexpected",
                GelText.tooltip(List.of(unexpected)));
        assertEquals("Sample: s\nAssay: plain\nAmplicon size: 200 bp", GelText.tooltip(List.of(plain)));
        assertEquals("s its 500 unexpected", GelText.searchText(List.of(unexpected)));
        assertEquals(true, GelText.details("t", List.of(unexpected)).endsWith("s\tits\t500 bp\tunexpected"));
    }
}
