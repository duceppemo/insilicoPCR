package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.FastqDiagnostics.Assembly;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.ReadsWithOligo;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.TestRows;
import ca.canada.inspection.report.HtmlReport.Cell;
import ca.canada.inspection.report.HtmlReport.Product;
import ca.canada.inspection.report.HtmlReport.State;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportTest {

    private static final ExpectedSizes SIZES = ExpectedSizes.fromHeaders(Map.of("its-F", "size=434 other=575"));
    private static final List<String> PRIMERS = List.of("its-F", "its-R", "plain-F", "plain-R_0", "plain-R_1");
    private static final String HEADER = String.join("\t", "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig",
            "Contig Description", "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
            "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand");

    /** A panel of these primer IDs, their sequences unknown (the page then shows no primer lengths). */
    private static PrimerPanel panel(List<String> ids, ExpectedSizes sizes) {
        Map<String, String> sequences = new java.util.LinkedHashMap<>();
        ids.forEach(id -> sequences.put(id, ""));
        return PrimerPanel.of(sequences, sizes);
    }

    private static Path write(Path tsv, List<String> samples, Set<String> fastqSamples, PrimerPanel primers,
                              List<Finding> findings, RunDetails details) {
        // As a run gives them, the rows made from the report's text (their source read back from it).
        return HtmlReport.write(tsv, ReportTsv.read(tsv), samples, fastqSamples, primers, findings, details,
                List.of(), List.of());
    }

    /** A run's details, where a test is not about them. */
    private static final RunDetails DETAILS = new RunDetails("2026-11-10 12:00", "9.9.9", List.of());

    @TempDir
    Path dir;

    private static Product product(String assay, int size) {
        return new Product(TestRows.row("S", assay, 11, 10 + size, "c1", ""), 0, 0, SIZES.label(assay, size));
    }

    private static Finding finding(Status status, int checkable) {
        return new Finding("S", "its", status, Assembly.SPLIT_ACROSS_CONTIGS, new ReadsWithOligo(5, 5, 0), checkable,
                "complete amplicon in 3 of 17 informative read(s) (18%), size(s) 434 bp", "");
    }

    private static String row(String sample, String assay, int size, String contig, String description) {
        return String.join("\t", sample, assay, "11-" + (10 + size), String.valueOf(size), contig, description,
                assay + "-F", assay + "-R", "0", "1", "0", "0", "+");
    }

    @Test
    void targetSizeMakesTheSamplePositiveWhateverElseAmplifies() {
        Cell cell = HtmlReport.cell("its", List.of(product("its", 300), product("its", 436)), SIZES, null, null);

        assertEquals(State.TARGET, cell.state());
        assertEquals(List.of(436), cell.sizes(), "the size shown is the target product's");
        assertEquals(2, cell.products().size(), "the other product stays listed");
    }

    @Test
    void productOfAnotherSizeIsNotCalledPositiveButIsShown() {
        assertEquals(State.OTHER, HtmlReport.cell("its", List.of(product("its", 575), product("its", 90)), SIZES, null, null).state());

        Cell unexpected = HtmlReport.cell("its", List.of(product("its", 90), product("its", 1200)), SIZES, null, null);
        assertEquals(State.UNEXPECTED, unexpected.state());
        assertEquals(List.of(90, 1200), unexpected.sizes());
    }

    @Test
    void assayWithoutDeclaredSizeIsAmplifiedNotJudged() {
        Cell cell = HtmlReport.cell("plain", List.of(product("plain", 200)), SIZES, null, null);

        assertEquals(State.AMPLIFIED, cell.state());
        assertEquals(List.of(200), cell.sizes());
    }

    @Test
    void noProductIsReadFromTheFastqDiagnostics() {
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, null, null).state());

        Cell weak = HtmlReport.cell("its", List.of(), SIZES, finding(Status.WEAK_READ_SUPPORT, 500), null);
        assertEquals(State.INCONCLUSIVE, weak.state());
        assertTrue(weak.note().contains("3 of 17 informative read(s)"), weak.note());

        // Reads could hold 300 bp at most; the declared product starts at 424 bp: not checkable.
        Cell tooLong = HtmlReport.cell("its", List.of(), SIZES, finding(Status.NOT_IN_READS, 300), null);
        assertEquals(State.UNCHECKED, tooLong.state());
        assertTrue(tooLong.note().contains("target 424-444 bp") && tooLong.note().contains("300 bp"), tooLong.note());

        // Reads long enough to have shown it, or no declared size to compare with: a plain negative.
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, finding(Status.NOT_IN_READS, 500), null).state());
        assertEquals(State.NONE, HtmlReport.cell("plain", List.of(), SIZES, finding(Status.NOT_IN_READS, 100), null).state());
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, finding(Status.PARTIAL_BAIT, 100), null).state());
    }

    @Test
    void negativeAfterReadExtensionIsNoLongerNotCheckable() {
        // Reads could hold 300 bp at most and the declared product starts at 424 bp, but the
        // reads were extended and did not join the primers: a negative, with the reason.
        Cell cell = HtmlReport.cell("its", List.of(), SIZES, new Finding("S", "its", Status.NOT_CLOSED_BY_EXTENSION,
                Assembly.SPLIT_ACROSS_CONTIGS, new ReadsWithOligo(19, 7, 0), 300,
                "no complete amplicon in the reads, which could hold amplicons up to 300 bp; extending 19"
                        + " forward-seeded and 7 reverse-seeded read(s) through the reads baited in rounds, by up to 2000 bases"
                        + " each way, gave no product held by at least 2 extended reads seeded from one primer", ""), null);

        assertEquals(State.NONE, cell.state());
        assertTrue(cell.note().startsWith("Both primers are in the reads: no complete amplicon in the reads"),
                cell.note());
    }

    @Test
    void productTheReadPairsDoNotConfirmIsNotShownAsAPlainNegative() throws IOException {
        Finding withheld = new Finding("S", "its", Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT,
                Assembly.SPLIT_ACROSS_CONTIGS, new ReadsWithOligo(50, 50, 0), 300, "extended reads hold a product of"
                + " 434 bp (40 of 50 forward-seeded and 38 of 50 reverse-seeded reads), not reported: bases 100-400 are"
                + " a repeat with another copy in the sample", "");

        Cell cell = HtmlReport.cell("its", List.of(), SIZES, withheld, null);
        assertEquals(State.UNCONFIRMED, cell.state());
        assertTrue(cell.note().startsWith("Not reported: extended reads hold a product of 434 bp"), cell.note());
        assertEquals(State.UNCONFIRMED, HtmlReport.cell("its", List.of(), SIZES, new Finding("S", "its",
                Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, Assembly.NOT_ASSEMBLED, new ReadsWithOligo(5, 5, 0), 300,
                "x", ""), null).state());

        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER));
        String page = Files.readString(write(tsv, List.of("S"), Set.of("S"), panel(PRIMERS, SIZES),
                List.of(withheld), DETAILS));
        assertTrue(page.contains("class=\"chip unconfirmed\""));
        assertTrue(page.contains("not confirmed by read pairs, not reported"), "its own summary tile");
        // Listed with the reads under the mark of its grid cell, not the "?" of "seen in too few reads".
        assertTrue(page.contains("<span class=\"chip unconfirmed\" role=\"img\" aria-label=\"Not confirmed by read"
                + " pairs\">\u21c4</span> not reported"), "listed with the reads");
    }

    @Test
    void productCalledByExtensionIsShownAsCalledFromReads() throws IOException {
        String description = "FASTQ read-extension call: no read holds the product; 142 of 150 forward-seeded and"
                + " 145 of 150 reverse-seeded reads, extended through the reads baited in rounds (k=31), hold it";
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER,
                row("long", "its", 434, "extended_read_3", description)));
        Finding called = new Finding("long", "its", Status.CALLED_BY_EXTENSION, Assembly.SPLIT_ACROSS_CONTIGS,
                new ReadsWithOligo(6134, 5424, 0), 300, "no complete amplicon in the reads, which could hold amplicons"
                + " up to 300 bp; reads extended through the reads baited in rounds hold a product of 434 bp (142 of 150"
                + " forward-seeded and 145 of 150 reverse-seeded reads)", "");
        Finding notClosed = new Finding("short", "its", Status.NOT_CLOSED_BY_EXTENSION, Assembly.NOT_ASSEMBLED,
                new ReadsWithOligo(19, 7, 0), 144, "gave no product held by at least 2 extended reads seeded from one primer", "");

        String page = Files.readString(write(tsv, List.of("long", "short"), Set.of("long", "short"),
                panel(PRIMERS, SIZES), List.of(called, notClosed), DETAILS));

        assertTrue(page.contains("434 bp (target), from extended reads"), "the grid tooltip says where it comes from");
        assertTrue(page.contains("<span class=\"tag\">reads</span> extended (142 of 150 forward-seeded, 145 of 150"
                + " reverse-seeded)"), "the amplicon table shows the support");
        assertTrue(page.contains("<span class=\"n\">1</span> of 2 samples positive at the target size"));
        assertTrue(page.contains("<span class=\"tag\">reads</span> reported"), "listed with the read-level evidence");
        assertTrue(page.contains("1 negative has both primers in the reads, but no read holds a complete amplicon and"
                + " extending the reads"));
        assertFalse(page.contains("class=\"chip unchecked\""),
                "nothing is left as not checkable once the reads were extended");
    }

    @Test
    void writesASelfContainedPageNextToTheReportWithEverySampleAndRow() throws IOException {
        Path tsv = Files.write(dir.resolve("report(1).tsv"), List.of(HEADER,
                row("pos", "its", 434, "contig_1,len=900,cov=5.0", ""),
                row("pos", "its", 300, "contig_2", ""),
                row("fromReads", "its", 575, "read_7", "FASTQ read-level call: complete amplicon in 15 of 24"
                        + " informative read(s) (63%), not in the mini-assembly; representative read r7"),
                row("pos", "plain", 200, "contig_3", "")));
        RunDetails details = new RunDetails("2026-11-10 12:00", "9.9.9",
                List.of(new RunDetails.Setting("Primer mismatches (-m)", "1")));

        Path html = write(tsv, List.of("pos", "fromReads", "neg <b>&\"x\""), Set.of("fromReads"),
                panel(PRIMERS, SIZES), List.of(), details);

        assertEquals(dir.resolve("report(1).html"), html);
        String page = Files.readString(html);
        assertTrue(page.startsWith("<!DOCTYPE html>"));
        assertFalse(page.contains("http://") || page.contains("https://") || page.contains(" src="),
                "no network, no external file");
        assertTrue(page.contains("2026-11-10 12:00 · version 9.9.9 · report(1).tsv"));

        // Every sample has a grid row, the negative one included, with its name escaped.
        assertEquals(3, count(page, "<tr data-sample="));
        assertTrue(page.contains("neg &lt;b&gt;&amp;&quot;x&quot;"));
        assertFalse(page.contains("<b>&"));
        // Both assays are columns; expansions of a degenerate primer are one assay.
        assertEquals(1, count(page, "<span class=\"an\">plain</span>"));
        assertEquals(1, count(page, "<span class=\"an\">its</span>"));

        // Grid cells: target (with the unexpected product kept as +1), other from reads, amplified.
        assertTrue(page.contains("class=\"chip target\"") && page.contains("✓ 434<sup>+1</sup>"), "target cell");
        assertTrue(page.contains("class=\"chip other\"") && page.contains("≈ 575<span class=\"dot\""), "other cell");
        assertTrue(page.contains("class=\"chip amplified\"") && page.contains(">200</button>"), "amplified cell");
        // All four TSV rows are in the detail table, labelled; nothing is dropped.
        assertEquals(4, count(page, "<tr data-s="));
        assertTrue(page.contains("! unexpected</span>"));
        assertTrue(page.contains("<span class=\"tag\">reads</span> (15 of 24, 63%)"));
        assertTrue(page.contains(">contig_1</td>"), "long Tadpole contig names are shortened");
        assertTrue(page.contains("<h3>its</h3>\n<p class=\"decl\">target 424-444 bp; other 565-585 bp</p>"));
        assertTrue(page.contains("<span class=\"n\">1</span> of 3 samples positive at the target size"));
        assertTrue(page.contains("<dt>Primer mismatches (-m)</dt><dd>1</dd>"));
    }

    @Test
    void aProductWhosePrimerBindsOnlyPartlyIsMarkedAndItsBindingGiven() throws IOException {
        String header = HEADER + "\tForward5PrimeUnaligned\tReverse5PrimeUnaligned";
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(header,
                // Target size, but each primer is held by its last 9 bases: the state rests on that product.
                row("weak", "its", 430, "c1", "") + "\t8\t8",
                // The same weak product next to one whose primers bind in full: the state does not rest on it.
                row("mixed", "its", 430, "c1", "") + "\t8\t8",
                row("mixed", "its", 434, "c2", "") + "\t0\t0",
                row("clean", "its", 434, "c1", "") + "\t0\t0"));
        PrimerPanel primers = PrimerPanel.of(Map.of("its-F", "ACGTACGTACGTACGTA", "its-R", "ACGTACGTACGTACGTA"));

        String page = Files.readString(write(tsv, List.of("weak", "mixed", "clean"), Set.of(),
                PrimerPanel.of(primers.sequences(), SIZES), List.of(), DETAILS));

        String mark = "<span class=\"part\" role=\"img\" aria-label=\"A primer binds only partly (5′ bases unpaired)\">\u25d0</span>";
        assertEquals(1, count(page, mark), "only the cell whose every target product is weakly bound is marked");
        assertTrue(page.contains("data-s=\"weak\" data-a=\"its\" aria-label=\"weak · its: Target size ✓ 430\">✓ 430"
                + mark), "the weak cell carries the mark, and is read out as which cell it is");
        // The tooltip and the table say how much of each primer pairs, of its length.
        assertTrue(page.contains("430 bp (target); forward primer 9 of 17 bases, reverse primer 9 of 17 bases"));
        assertTrue(page.contains("<th class=\"num\" title=\"Bases at the 5' end of each primer that do not pair with"
                + " the template, forward / reverse; the mismatch columns do not count them\">5′ unpaired F/R</th>"));
        assertEquals(2, count(page, "<span class=\"part\">\u25d0</span> 8 / 8</td>"), "both weak rows are marked");
        assertEquals(2, count(page, ">0 / 0</td><td>+</td>"), "rows bound in full show zeros and no mark");
        // The legend explains the mark, and the assay card repeats it beside the sample it concerns.
        assertTrue(page.contains("<span class=\"part\">\u25d0</span>A primer binds only partly (5′ bases unpaired)</span>"));
        assertTrue(page.contains(">weak <span class=\"bp\">430 bp</span> <span class=\"part\" title="));
        assertFalse(page.contains(">clean <span class=\"bp\">434 bp</span> <span class=\"part\""));
    }

    @Test
    void aReportWithoutTheUnalignedColumnsOrFullyBoundPrimersShowsNoMark() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER, row("a", "its", 434, "c1", "")));

        String page = Files.readString(write(tsv, List.of("a"), Set.of(), panel(PRIMERS, SIZES), List.of(),
                DETAILS));

        assertFalse(page.contains("class=\"part\""));
        assertFalse(page.contains("A primer binds only partly"));
    }

    @Test
    void withoutDeclaredSizesProductsAreShownAsAmplifiedAndTheHintSaysHowToDeclare() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER, row("a", "plain", 200, "c1", "")));

        String page = Files.readString(write(tsv, List.of("a", "b"), Set.of(), panel(List.of("plain-F", "plain-R"),
                ExpectedSizes.NONE), List.of(), DETAILS));

        assertTrue(page.contains("<span class=\"n\">1</span> of 2 samples amplified"));
        assertFalse(page.contains("<th>Size check</th>"));
        assertFalse(page.contains("positive at target size"));
        assertTrue(page.contains("&gt;assay-F size=434"));
    }

    @Test
    void emptyReportStillGivesAPageWithEverySampleNegative() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER));

        String page = Files.readString(write(tsv, List.of("a", "b"), Set.of(), panel(PRIMERS, SIZES),
                List.of(finding(Status.WEAK_READ_SUPPORT, 500)), DETAILS));

        assertEquals(2, count(page, "<tr data-sample="));
        assertTrue(page.contains("No amplicon was reported."));
        assertTrue(page.contains("<span class=\"n\">0</span> of 2 samples positive at the target size"));
        // The finding is for a sample of another run ("S"): listed, but it adds no grid row.
        assertTrue(page.contains("Read-level evidence (FASTQ input)"));
    }

    @Test
    void pageIsNamedAfterItsReport() {
        assertEquals(Path.of("out", "consolidated_report", "report.html"),
                HtmlReport.pathFor(Path.of("out", "consolidated_report", "report.tsv")));
        assertEquals(Path.of("report(12).html"), HtmlReport.pathFor(Path.of("report(12).tsv")));
    }

    @Test
    void aCellShowsTwoSizesAndHintsAtMore() throws IOException {
        Path tsv = Files.writeString(dir.resolve("report.tsv"), String.join("\n", HEADER,
                row("two", "plain", 300, "c1", ""), row("two", "plain", 200, "c1", ""),
                row("three", "plain", 300, "c1", ""), row("three", "plain", 100, "c1", ""),
                row("three", "plain", 200, "c1", "")) + "\n");

        String page = Files.readString(write(tsv, List.of("two", "three"), Set.of(), panel(PRIMERS, SIZES),
                List.of(), DETAILS));

        // Smallest first; a third size is a "…", and every size is in the cell's hover text, in report order.
        assertTrue(page.contains(">200/300</button>"), "two sizes");
        assertTrue(page.contains(">100…</button>"), "three sizes");
        assertTrue(page.contains("title=\"three · plain: Amplified&#10;300 bp&#10;100 bp&#10;200 bp\""), "hover text");
    }

    private static int count(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void samplesLeftOutForAFaultyFileAreListedApartAndCounted() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER, row("a", "its", 434, "c1", "")));
        List<InputCheck.Issue> issues = List.of(
                new InputCheck.Issue("failed", List.of("failed_R1.fastq.gz"), "the file holds no read", "the file is empty"),
                new InputCheck.Issue("failed", List.of("failed_R2.fastq.gz"), "the file holds no read", "the file is empty"),
                new InputCheck.Issue("odd <b>", List.of("odd_R1.fastq", "odd_R2.fastq"),
                        "the two files of the pair hold different numbers of reads", "3 and 2"));

        String page = Files.readString(HtmlReport.write(tsv, ReportTsv.read(tsv), List.of("a"), Set.of(), panel(List.of("its-F", "its-R"), SIZES),
                List.of(), DETAILS, issues, List.of()));

        assertTrue(page.contains("<h2>Input not analysed</h2>"));
        assertTrue(page.contains("<span class=\"n\">2</span><span class=\"l\">samples not analysed: faulty input files</span>"),
                "two samples, three problems");
        assertTrue(page.contains("<td>failed</td><td>failed_R1.fastq.gz</td><td class=\"wrap\">the file holds no read</td>"
                + "<td class=\"wrap\">the file is empty</td>"));
        assertTrue(page.contains("<td>odd &lt;b&gt;</td><td>odd_R1.fastq, odd_R2.fastq</td>"), "escaped, both mates named");
        assertFalse(page.contains("data-sample=\"failed\""), "not a row of the grid: it is not a negative");
        assertTrue(page.indexOf("Input not analysed") < page.indexOf("Samples by assay"), "said before the results");
    }

    @Test
    void aRunWithoutFaultyInputHasNoSuchSection() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER, row("a", "its", 434, "c1", "")));

        String page = Files.readString(write(tsv, List.of("a"), Set.of(), panel(PRIMERS, SIZES), List.of(),
                DETAILS));

        assertFalse(page.contains("Input not analysed"));
        assertFalse(page.contains("not analysed: faulty"));
    }

    @Test
    void everyVerdictHasItsLookOnThePage() {
        for (ca.canada.inspection.pcr.AssayVerdict.Kind kind : ca.canada.inspection.pcr.AssayVerdict.Kind.values()) {
            assertEquals(kind.name(), HtmlReport.State.of(kind).name());
        }
        assertEquals(ca.canada.inspection.pcr.AssayVerdict.Kind.values().length, HtmlReport.State.values().length);
        // Told apart without hovering: "seen in too few reads" and "not confirmed by read pairs" were both "?".
        List<String> glyphs = java.util.Arrays.stream(HtmlReport.State.values()).map(HtmlReport.State::glyph)
                .filter(glyph -> !glyph.isEmpty()).toList();
        assertEquals(glyphs.size(), new java.util.HashSet<>(glyphs).size(), glyphs.toString());
    }

    @Test
    void notCheckableFromReadsHasItsTileAndAPlainNegativeNoTitle() throws IOException {
        // The FASTQ sample S has both primers in reads that could not hold the declared 434 bp.
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER));
        String page = Files.readString(write(tsv, List.of("S", "neg"), Set.of("S"), panel(PRIMERS, SIZES),
                List.of(finding(Status.NOT_IN_READS, 300)), DETAILS));

        assertTrue(page.contains("no product, not checkable from reads"), "a tile for it");
        // Its outcome reaches a screen reader, not only the mouse (a title).
        assertTrue(page.contains("<span class=\"chip unchecked\" title=\"S · its: Not checkable from reads"), page);
        assertTrue(page.contains("role=\"img\" aria-label=\"S · its: Not checkable from reads \u25cb. "), page);
        // neg shows "no product" with nothing to add: no title, a third of a large run's page.
        assertTrue(page.contains("<span class=\"chip none\">"), "a plain negative cell without a title");
    }

    @Test
    void productsNotMadeAsDesignedAreTaggedWithWhatMadeThem() throws IOException {
        List<ca.canada.inspection.pcr.ReportRow> rows = List.of(
                ca.canada.inspection.pcr.TestRows.of("S", "its", 101, 600, 500, "c1", "", "its-F", "its-F",
                        0, 0, 0, 0, '+', null, 0, 0),
                ca.canada.inspection.pcr.TestRows.of("S", "its+plain", 101, 400, 300, "c1", "", "its-F", "plain-R_0",
                        0, 0, 0, 0, '+', null, 0, 0));
        Path html = HtmlReport.write(Files.writeString(dir.resolve("report.tsv"), ""), rows, List.of("S"), Set.of(),
                panel(PRIMERS, SIZES), List.of(), DETAILS, List.of(), List.of());
        String page = Files.readString(html);

        assertTrue(page.contains("its <span class=\"tag\" title=\"made by one primer alone: its-F on both strands,"
                + " where its site repeats inverted\">one primer</span>"), "one primer");
        assertTrue(page.contains("its+plain <span class=\"tag\" title=\"made by primers of two assays that share a"
                + " reaction (mix=): its-F and plain-R_0\">two assays</span>"), "two assays");
        // The grid's hover text says it too, and the product of two assays has its own column.
        assertTrue(page.contains("500 bp (unexpected); made by one primer alone"), "grid hover text");
        assertEquals(1, count(page, "<span class=\"an\">its+plain</span>"));
    }

    @Test
    void aLargeRunListsTheFirstAmpliconsAndPointsToTheReportForAll() throws IOException {
        // Millions of rows made a page no browser opens, and built whole in memory, failed the run.
        List<ca.canada.inspection.pcr.ReportRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < HtmlReport.MAX_LISTED + 10; i++) {
            rows.add(ca.canada.inspection.pcr.TestRows.of("S" + (i % 100), "its", 101, 534, 434, "c" + i, "", "its-F",
                    "its-R", 0, 0, 0, 0, '+', null, 0, 0));
        }
        Path html = HtmlReport.write(Files.writeString(dir.resolve("report.tsv"), ""), rows, List.of(), Set.of(),
                panel(PRIMERS, SIZES), List.of(), DETAILS, List.of(), List.of());
        String page = Files.readString(html);

        assertEquals(HtmlReport.MAX_LISTED, count(page, "<tr data-s="));
        assertTrue(page.contains("The first 50,000 of 50,010 amplicons are listed here, and the filter searches those:"
                + " report.tsv has every one."), "the note");
        assertEquals(100, count(page, "<tr data-sample="), "the grid has every sample");
    }

    @Test
    void aPageThatFailsPartWayLeavesNoPageCutShort() throws IOException {
        // Streamed straight into report.html, a failure part way left the start of a page where it is looked for.
        Path tsv = Files.writeString(dir.resolve("report.tsv"), "");
        List<String> failing = new java.util.AbstractList<>() {
            @Override
            public String get(int index) {
                throw new IllegalStateException("part way");
            }

            @Override
            public int size() {
                return 3;
            }
        };

        assertThrows(IllegalStateException.class, () -> HtmlReport.write(tsv, List.of(), failing, Set.of(),
                panel(PRIMERS, SIZES), List.of(), DETAILS, List.of(), List.of()));

        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("report.tsv"), files.map(file -> file.getFileName().toString()).toList());
        }
    }
}
