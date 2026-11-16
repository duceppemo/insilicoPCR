package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TestRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The columns of report.tsv are written and read from one list, so a row reads back as written. */
class ReportColumnTest {

    @TempDir
    Path dir;

    private static final ReportRow WITH_PROBE = TestRows.of("S", "stxQ", 7313, 7430, 118, "contig_1",
            "len=9000\tcov=12\nmore", "stxQ-F", "stxQ-R", 1, 2, 0, 1, '-',
            new ReportRow.Probe("stxQ-P", 7350, 7369, 1), 3, 0);
    private static final ReportRow WITHOUT = TestRows.of("T", "toxA", 4001, 4312, 312, "c2", "", "toxA-F", "toxA-R",
            0, 0, 0, 0, '+', null, 0, 2);

    @Test
    void theHeaderIsTheReportsColumnOrder() {
        assertEquals(List.of("Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand", "Probe", "ProbeLocation",
                "ProbeSize", "ProbeMismatches", "Forward5PrimeUnaligned", "Reverse5PrimeUnaligned"),
                ReportColumn.of(true).stream().map(ReportColumn::header).toList());
        assertEquals(15, ReportColumn.of(false).size());
        assertEquals(ReportColumn.STRAND, ReportColumn.FIRST.getLast());
    }

    @Test
    void aCellHoldsNoTabOrLineBreakAndAMissingProbeIsEmpty() {
        assertEquals("len=9000 cov=12 more", ReportColumn.CONTIG_DESCRIPTION.value(WITH_PROBE));
        assertEquals("7350-7369", ReportColumn.PROBE_LOCATION.value(WITH_PROBE));
        assertEquals("20", ReportColumn.PROBE_SIZE.value(WITH_PROBE));
        assertEquals("", ReportColumn.PROBE_MISMATCHES.value(WITHOUT));
    }

    @Test
    void rowsReadBackAsWritten() throws IOException {
        for (boolean probeColumns : new boolean[]{true, false}) {
            List<ReportColumn> columns = ReportColumn.of(probeColumns);
            StringBuilder text = new StringBuilder(String.join("\t", columns.stream().map(ReportColumn::header).toList()));
            for (ReportRow row : List.of(WITH_PROBE, WITHOUT)) {
                text.append('\n').append(String.join("\t", columns.stream().map(column -> column.value(row)).toList()));
            }
            Path report = Files.writeString(dir.resolve("report" + probeColumns + ".tsv"), text + "\n");

            List<ReportRow> read = ReportTsv.read(report);

            ReportRow expected = probeColumns ? WITH_PROBE.calledFrom(ReportRow.Source.ASSEMBLY, "len=9000 cov=12 more")
                    : TestRows.of("S", "stxQ", 7313, 7430, 118, "contig_1", "len=9000 cov=12 more", "stxQ-F",
                    "stxQ-R", 1, 2, 0, 1, '-', null, 3, 0);
            assertEquals(List.of(expected, WITHOUT), read, "probe columns: " + probeColumns);
        }
    }

    @Test
    void textBetweenQuotesReadsBackAsItIs() throws IOException {
        // A file named "isolate 7".fasta: the reader takes a spreadsheet's quotes off, which split its lane.
        for (String name : List.of("\"isolate 7\"", "\"a \"\"b\"\" c\"", "say \"hi\"", "\"", "plain\ttab")) {
            Path report = Files.writeString(dir.resolve("quoted.tsv"), String.join("\t",
                    ReportColumn.of(false).stream().map(ReportColumn::header).toList()) + "\n"
                    + String.join("\t", ReportColumn.of(false).stream().map(column -> column == ReportColumn.SAMPLE
                    ? ReportColumn.cell(name) : column.value(WITHOUT)).toList()) + "\n");

            assertEquals(ReportColumn.readBack(name), ReportTsv.read(report).getFirst().sample(), name);
        }
    }

    @Test
    void aCellASpreadsheetTakesForAFormulaIsWrittenAsTextAndReadBackAsItWas() throws IOException {
        // A contig description =HYPERLINK(...) became a link in Excel, a sample file -ctrl.fasta showed #NAME?.
        assertEquals("'=HYPERLINK(\"http://x\",\"c\")", ReportColumn.cell("=HYPERLINK(\"http://x\",\"c\")"));
        assertEquals("'-ctrl", ReportColumn.cell("-ctrl"));
        assertEquals("'@SUM(1)", ReportColumn.cell("@SUM(1)"));
        assertEquals("'+cmd", ReportColumn.cell("+cmd"));
        // A strand, a number: as they are.
        assertEquals("-", ReportColumn.cell("-"));
        assertEquals("-5", ReportColumn.cell("-5"));
        assertEquals("+1.5e3", ReportColumn.cell("+1.5e3"));
        for (String name : List.of("-ctrl", "=1+2", "'quoted", "'-already")) {
            Path report = Files.writeString(dir.resolve("formula.tsv"), String.join("\t",
                    ReportColumn.of(false).stream().map(ReportColumn::header).toList()) + "\n"
                    + String.join("\t", ReportColumn.of(false).stream().map(column -> column == ReportColumn.SAMPLE
                    ? ReportColumn.cell(name) : column.value(WITHOUT)).toList()) + "\n");

            assertEquals(name, ReportTsv.read(report).getFirst().sample(), name);
        }
    }
}
