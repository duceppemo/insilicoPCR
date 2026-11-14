package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ReportRow;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a {@code report.tsv} back into {@link ReportRow}s: for the HTML report, the gel, and a
 * past run opened in the application. Columns are found by their header, so a report of an
 * earlier release (without the probe or the 5' columns) reads too, with those left empty.
 * A line that is not a product (blank, a header again, a size that is not a number) is
 * skipped. A file without an insilicoPCR header (saved from a spreadsheet without it) is read
 * in the report's column order, its first line as a product.
 */
public final class ReportTsv {

    private ReportTsv() {
    }

    public static List<ReportRow> read(Path report) {
        List<ReportRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return rows;
            }
            // A spreadsheet may save the file with a byte order mark before the first name.
            headerLine = headerLine.startsWith("\uFEFF") ? headerLine.substring(1) : headerLine;
            List<String> header = List.of(headerLine.split("\t", -1));
            boolean hasHeader = header.contains("AmpliconSize");
            Map<String, Integer> columns = new HashMap<>();
            List<String> names = hasHeader ? header : ConsolidatedReport.FIRST_COLUMNS;
            for (int i = 0; i < names.size(); i++) {
                columns.putIfAbsent(names.get(i), i);
            }
            if (!hasHeader) {
                ReportRow first = row(new Fields(columns, headerLine.split("\t", -1)));
                if (first != null) {
                    rows.add(first);
                }
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank() && !(hasHeader && line.equals(headerLine))) {
                    ReportRow row = row(new Fields(columns, line.split("\t", -1)));
                    if (row != null) {
                        rows.add(row);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read consolidated report: " + report, e);
        }
        return rows;
    }

    private static ReportRow row(Fields fields) {
        Integer size = fields.number("AmpliconSize");
        if (size == null) {
            return null;
        }
        int[] location = fields.span("GenomeLocation");
        int[] probeLocation = fields.span("ProbeLocation");
        ReportRow.Probe probe = fields.text("Probe").isEmpty() ? null : new ReportRow.Probe(fields.text("Probe"),
                probeLocation[0], probeLocation[1], fields.numberOrZero("ProbeMismatches"));
        String strand = fields.text("Strand");
        return new ReportRow(fields.text("Sample"), fields.text("Gene"), location[0], location[1], size,
                fields.text("Contig"), fields.text("Contig Description"), fields.text("ForwardPrimers"),
                fields.text("ReversePrimers"), fields.numberOrZero("ForwardMismatches"),
                fields.numberOrZero("ReverseMismatches"), fields.numberOrZero("Forward3PrimeMismatches"),
                fields.numberOrZero("Reverse3PrimeMismatches"), strand.isEmpty() ? ' ' : strand.charAt(0), probe,
                fields.numberOrZero(ConsolidatedReport.FORWARD_UNALIGNED_COLUMN),
                fields.numberOrZero(ConsolidatedReport.REVERSE_UNALIGNED_COLUMN));
    }

    /** One line's values, by column name. */
    private record Fields(Map<String, Integer> columns, String[] values) {
        String text(String column) {
            Integer index = columns.get(column);
            return index == null || index >= values.length ? "" : values[index];
        }

        Integer number(String column) {
            try {
                return Integer.parseInt(text(column).strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        int numberOrZero(String column) {
            Integer number = number(column);
            return number == null ? 0 : number;
        }

        /** {@code start-end} as two numbers; zeros when it is not one. */
        int[] span(String column) {
            String[] ends = text(column).split("-");
            try {
                return ends.length == 2 ? new int[]{Integer.parseInt(ends[0]), Integer.parseInt(ends[1])} : new int[2];
            } catch (NumberFormatException e) {
                return new int[2];
            }
        }
    }
}
