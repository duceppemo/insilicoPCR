package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.FastqDiagnostics.Assembly;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.ReadsWithOligo;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.HtmlReport.Cell;
import ca.canada.inspection.pcr.HtmlReport.Product;
import ca.canada.inspection.pcr.HtmlReport.State;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlReportTest {

    private static final ExpectedSizes SIZES = ExpectedSizes.fromHeaders(Map.of("its-F", "size=434 other=575"));
    private static final List<String> PRIMERS = List.of("its-F", "its-R", "plain-F", "plain-R_0", "plain-R_1");
    private static final String HEADER = String.join("\t", "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig",
            "Contig Description", "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
            "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand");

    @TempDir
    Path dir;

    private static Product product(String assay, int size) {
        return new Product("S", assay, size, Map.of(), SIZES.label(assay, size));
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
        Cell cell = HtmlReport.cell("its", List.of(product("its", 300), product("its", 436)), SIZES, null);

        assertEquals(State.TARGET, cell.state());
        assertEquals(List.of(436), cell.sizes(), "the size shown is the target product's");
        assertEquals(2, cell.products().size(), "the other product stays listed");
    }

    @Test
    void productOfAnotherSizeIsNotCalledPositiveButIsShown() {
        assertEquals(State.OTHER, HtmlReport.cell("its", List.of(product("its", 575), product("its", 90)), SIZES, null).state());

        Cell unexpected = HtmlReport.cell("its", List.of(product("its", 90), product("its", 1200)), SIZES, null);
        assertEquals(State.UNEXPECTED, unexpected.state());
        assertEquals(List.of(90, 1200), unexpected.sizes());
    }

    @Test
    void assayWithoutDeclaredSizeIsAmplifiedNotJudged() {
        Cell cell = HtmlReport.cell("plain", List.of(product("plain", 200)), SIZES, null);

        assertEquals(State.AMPLIFIED, cell.state());
        assertEquals(List.of(200), cell.sizes());
    }

    @Test
    void noProductIsReadFromTheFastqDiagnostics() {
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, null).state());

        Cell weak = HtmlReport.cell("its", List.of(), SIZES, finding(Status.WEAK_READ_SUPPORT, 500));
        assertEquals(State.INCONCLUSIVE, weak.state());
        assertTrue(weak.note().contains("3 of 17 informative read(s)"), weak.note());

        // Reads could hold 300 bp at most; the declared product starts at 424 bp: not checkable.
        Cell tooLong = HtmlReport.cell("its", List.of(), SIZES, finding(Status.NOT_IN_READS, 300));
        assertEquals(State.UNCHECKED, tooLong.state());
        assertTrue(tooLong.note().contains("target 424-444 bp") && tooLong.note().contains("300 bp"), tooLong.note());

        // Reads long enough to have shown it, or no declared size to compare with: a plain negative.
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, finding(Status.NOT_IN_READS, 500)).state());
        assertEquals(State.NONE, HtmlReport.cell("plain", List.of(), SIZES, finding(Status.NOT_IN_READS, 100)).state());
        assertEquals(State.NONE, HtmlReport.cell("its", List.of(), SIZES, finding(Status.PARTIAL_BAIT, 100)).state());
    }

    @Test
    void writesASelfContainedPageNextToTheReportWithEverySampleAndRow() throws IOException {
        Path tsv = Files.write(dir.resolve("report(1).tsv"), List.of(HEADER,
                row("pos", "its", 434, "contig_1,len=900,cov=5.0", ""),
                row("pos", "its", 300, "contig_2", ""),
                row("fromReads", "its", 575, "read_7", "FASTQ read-level call: complete amplicon in 15 of 24"
                        + " informative read(s) (63%), not in the mini-assembly; representative read r7"),
                row("pos", "plain", 200, "contig_3", "")));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Date", "2026-11-10 12:00");
        details.put("insilicoPCR version", "9.9.9");
        details.putAll(HtmlReport.settings(AmpliconOptions.withMismatches(1)));

        Path html = HtmlReport.write(tsv, List.of("pos", "fromReads", "neg <b>&\"x\""), Set.of("fromReads"), PRIMERS,
                SIZES, List.of(), details);

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
        Map<String, String> primers = Map.of("its-F", "ACGTACGTACGTACGTA", "its-R", "ACGTACGTACGTACGTA");

        String page = Files.readString(HtmlReport.write(tsv, List.of("weak", "mixed", "clean"), Set.of(), primers,
                SIZES, List.of(), Map.of()));

        String mark = "<span class=\"part\" aria-label=\"A primer binds only partly (5′ bases unpaired)\">\u25d0</span>";
        assertEquals(1, count(page, mark), "only the cell whose every target product is weakly bound is marked");
        assertTrue(page.contains("data-s=\"weak\" data-a=\"its\">✓ 430" + mark), "the weak cell carries the mark");
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

        String page = Files.readString(HtmlReport.write(tsv, List.of("a"), Set.of(), PRIMERS, SIZES, List.of(),
                Map.of()));

        assertFalse(page.contains("class=\"part\""));
        assertFalse(page.contains("A primer binds only partly"));
    }

    @Test
    void withoutDeclaredSizesProductsAreShownAsAmplifiedAndTheHintSaysHowToDeclare() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER, row("a", "plain", 200, "c1", "")));

        String page = Files.readString(HtmlReport.write(tsv, List.of("a", "b"), Set.of(), List.of("plain-F", "plain-R"),
                ExpectedSizes.NONE, List.of(), Map.of()));

        assertTrue(page.contains("<span class=\"n\">1</span> of 2 samples amplified"));
        assertFalse(page.contains("<th>Size check</th>"));
        assertFalse(page.contains("positive at target size"));
        assertTrue(page.contains("&gt;assay-F size=434"));
    }

    @Test
    void emptyReportStillGivesAPageWithEverySampleNegative() throws IOException {
        Path tsv = Files.write(dir.resolve("report.tsv"), List.of(HEADER));

        String page = Files.readString(HtmlReport.write(tsv, List.of("a", "b"), Set.of(), PRIMERS, SIZES,
                List.of(finding(Status.WEAK_READ_SUPPORT, 500)), Map.of()));

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

        String page = Files.readString(HtmlReport.write(tsv, List.of("a"), Set.of(), Map.of("its-F", "", "its-R", ""),
                SIZES, List.of(), Map.of(), issues));

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

        String page = Files.readString(HtmlReport.write(tsv, List.of("a"), Set.of(), PRIMERS, SIZES, List.of(),
                Map.of()));

        assertFalse(page.contains("Input not analysed"));
        assertFalse(page.contains("not analysed: faulty"));
    }
}
