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
 * past run opened in the application. Columns are found by their header ({@link ReportColumn}), so a report of an
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
            boolean hasHeader = header.contains(ReportColumn.SIZE.header());
            Map<String, Integer> columns = new HashMap<>();
            List<String> names = hasHeader ? header : ReportColumn.FIRST.stream().map(ReportColumn::header).toList();
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
        Integer size = fields.number(ReportColumn.SIZE);
        if (size == null) {
            return null;
        }
        int[] location = fields.span(ReportColumn.LOCATION);
        int[] probeLocation = fields.span(ReportColumn.PROBE_LOCATION);
        String probeId = fields.text(ReportColumn.PROBE);
        ReportRow.Probe probe = probeId.isEmpty() ? null : new ReportRow.Probe(probeId,
                probeLocation[0], probeLocation[1], fields.numberOrZero(ReportColumn.PROBE_MISMATCHES));
        String strand = fields.text(ReportColumn.STRAND);
        return new ReportRow(fields.text(ReportColumn.SAMPLE), fields.text(ReportColumn.GENE), location[0], location[1],
                size, fields.text(ReportColumn.CONTIG), fields.text(ReportColumn.CONTIG_DESCRIPTION),
                fields.text(ReportColumn.FORWARD_PRIMERS), fields.text(ReportColumn.REVERSE_PRIMERS),
                fields.numberOrZero(ReportColumn.FORWARD_MISMATCHES), fields.numberOrZero(ReportColumn.REVERSE_MISMATCHES),
                fields.numberOrZero(ReportColumn.FORWARD_THREE_PRIME), fields.numberOrZero(ReportColumn.REVERSE_THREE_PRIME),
                strand.isEmpty() ? ' ' : strand.charAt(0), probe,
                fields.numberOrZero(ReportColumn.FORWARD_UNALIGNED), fields.numberOrZero(ReportColumn.REVERSE_UNALIGNED));
    }

    /** One line's values, by column name. */
    private record Fields(Map<String, Integer> columns, String[] values) {
        String text(ReportColumn column) {
            Integer index = columns.get(column.header());
            return index == null || index >= values.length ? "" : values[index];
        }

        Integer number(ReportColumn column) {
            try {
                return Integer.parseInt(text(column).strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }

        int numberOrZero(ReportColumn column) {
            Integer number = number(column);
            return number == null ? 0 : number;
        }

        /** {@code start-end} as two numbers; zeros when it is not one. */
        int[] span(ReportColumn column) {
            String[] ends = text(column).split("-");
            try {
                return ends.length == 2 ? new int[]{Integer.parseInt(ends[0]), Integer.parseInt(ends[1])} : new int[2];
            } catch (NumberFormatException e) {
                return new int[2];
            }
        }
    }
}
