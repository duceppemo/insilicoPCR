package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReportRow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code report.tsv} back into {@link ReportRow}s: for the gel (of a run, a past run, or a
 * report opened in the application). A finished run's HTML report is made from its rows, not from
 * the file. Columns are found by their header ({@link ReportColumn}), so a report of an
 * earlier release (without the probe or the 5' columns) reads too, with those left empty.
 * A line that is not a product (blank, a header again, a size that is not a number) is
 * skipped. A file without an insilicoPCR header (saved from a spreadsheet without it) is read
 * in the report's column order, its first line as a product.
 */
public final class ReportTsv {

    private ReportTsv() {
    }

    /**
     * The rows of {@code report}.
     *
     * @throws UncheckedIOException when the file cannot be read, or is not a consolidated report
     *                              (such as a BLAST TSV of {@code detailed_report}, whose numbers
     *                              would otherwise read as products)
     */
    public static List<ReportRow> read(Path report) {
        return readChecked(report).stream().map(Checked::row).toList();
    }

    /**
     * A row and what its size is for its assay, as the report's {@value ExpectedSizes#COLUMN}
     * column says.
     *
     * @param sizeCheck {@code null} when the row has none: the assay declares no size, or the
     *                  report has no such column (no size declared, or a report before 0.12)
     */
    public record Checked(ReportRow row, ExpectedSizes.Label sizeCheck) {
    }

    /** As {@link #read}, each row with its size check. */
    public static List<Checked> readChecked(Path report) {
        List<String> lines = lines(report);
        List<Checked> rows = new ArrayList<>();
        if (lines.isEmpty()) {
            return rows;
        }
        // A spreadsheet may save the file with a byte order mark before the first name.
        String headerLine = lines.getFirst().startsWith("\uFEFF") ? lines.getFirst().substring(1) : lines.getFirst();
        List<String> header = List.of(cells(headerLine));
        boolean hasHeader = header.contains(ReportColumn.SIZE.header());
        Map<String, Integer> columns = new HashMap<>();
        List<String> names = hasHeader ? header : ReportColumn.FIRST.stream().map(ReportColumn::header).toList();
        for (int i = 0; i < names.size(); i++) {
            columns.putIfAbsent(names.get(i), i);
        }
        if (!hasHeader) {
            // Without a header, the first line must be a product: a location and a size.
            Fields first = new Fields(columns, cells(headerLine));
            if (!first.text(ReportColumn.LOCATION).matches("\\d+-\\d+") || first.number(ReportColumn.SIZE) == null) {
                throw new UncheckedIOException("Not a consolidated report: " + report, new IOException(
                        "its first line has no " + ReportColumn.SIZE.header() + " column and is not a product"));
            }
            rows.add(new Checked(row(first), null));
        }
        Integer sizeCheck = columns.get(ExpectedSizes.COLUMN);
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isBlank() && !(hasHeader && line.equals(headerLine))) {
                String[] values = cells(line);
                ReportRow row = row(new Fields(columns, values));
                if (row != null) {
                    rows.add(new Checked(row, sizeCheck == null || sizeCheck >= values.length ? null
                            : label(values[sizeCheck])));
                }
            }
        }
        return rows;
    }

    /** The label a report writes, or {@code null} for an empty or unknown one. */
    private static ExpectedSizes.Label label(String text) {
        for (ExpectedSizes.Label label : ExpectedSizes.Label.values()) {
            if (label.text().equals(text.strip())) {
                return label;
            }
        }
        return null;
    }

    /**
     * The lines of a report: UTF-8, as the run writes it, or what a spreadsheet may save it in:
     * UTF-16 with its byte order mark ("Unicode Text" in Excel), or the Windows encoding ("Text
     * (Tab delimited)"), whose accented letters are not UTF-8.
     */
    private static List<String> lines(Path report) {
        try {
            checkFirstLine(report);
            return decode(Files.readAllBytes(report)).lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read consolidated report: " + report, e);
        }
    }

    /**
     * Refuses a file whose first line is neither a report's header nor a product, from its first block
     * only: a sample's BLAST table ({@code detailed_report/<sample>/<sample>.tsv}, gigabytes on a large
     * assembly) is named like a report, and was read whole, freezing the window, before being refused.
     */
    private static void checkFirstLine(Path report) throws IOException {
        byte[] head;
        try (java.io.InputStream in = Files.newInputStream(report)) {
            head = in.readNBytes(1 << 16);
        }
        String first = decode(head).lines().findFirst().orElse("");
        first = first.startsWith("\uFEFF") ? first.substring(1) : first;
        List<String> cells = List.of(cells(first));
        if (!first.isBlank() && !cells.contains(ReportColumn.SIZE.header())
                && cells.stream().noneMatch(cell -> cell.matches("\\d+-\\d+"))) {
            throw new UncheckedIOException("Not a consolidated report: " + report, new IOException(
                    "its first line has no " + ReportColumn.SIZE.header() + " column and is not a product"));
        }
    }

    /** The text of a report's {@code bytes}, in whichever encoding it was saved ({@link #lines}). */
    private static String decode(byte[] bytes) {
        Charset utf16 = bytes.length >= 2 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe
                ? StandardCharsets.UTF_16LE
                : bytes.length >= 2 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff
                ? StandardCharsets.UTF_16BE : null;
        if (utf16 != null) {
            return new String(bytes, 2, bytes.length - 2, utf16);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    /**
     * The cells of a line. A spreadsheet saving a cell that holds a quote (or, for some, a comma)
     * puts it between quotes and doubles the quotes in it: those are taken off.
     */
    private static String[] cells(String line) {
        String[] cells = line.split("\t", -1);
        for (int i = 0; i < cells.length; i++) {
            String cell = cells[i];
            if (cell.length() >= 2 && cell.startsWith("\"") && cell.endsWith("\"")) {
                cells[i] = cell.substring(1, cell.length() - 1).replace("\"\"", "\"");
            } else if (cell.startsWith("'") && ReportColumn.needsApostrophe(cell.substring(1))) {
                cells[i] = cell.substring(1); // written so for spreadsheets (ReportColumn.cell)
            }
        }
        return cells;
    }

    private static ReportRow row(Fields fields) {
        Integer size = fields.number(ReportColumn.SIZE);
        if (size == null) {
            return null;
        }
        int[] location = fields.span(ReportColumn.LOCATION);
        int[] probeLocation = fields.span(ReportColumn.PROBE_LOCATION);
        String probeId = fields.text(ReportColumn.PROBE);
        // Its size as written: a site across a circle's origin ends before it starts.
        ReportRow.Probe probe = probeId.isEmpty() ? null : new ReportRow.Probe(probeId,
                probeLocation[0], probeLocation[1], fields.numberOrZero(ReportColumn.PROBE_MISMATCHES),
                probeLocation[1] >= probeLocation[0] ? probeLocation[1] - probeLocation[0] + 1
                        : fields.numberOrZero(ReportColumn.PROBE_SIZE));
        String strand = fields.text(ReportColumn.STRAND);
        String description = fields.text(ReportColumn.CONTIG_DESCRIPTION);
        return new ReportRow(fields.text(ReportColumn.SAMPLE), fields.text(ReportColumn.GENE),
                new ReportRow.Site(location[0], location[1], size, fields.text(ReportColumn.CONTIG), description,
                        strand.isEmpty() ? ' ' : strand.charAt(0)),
                new ReportRow.Binding(fields.text(ReportColumn.FORWARD_PRIMERS),
                        fields.numberOrZero(ReportColumn.FORWARD_MISMATCHES),
                        fields.numberOrZero(ReportColumn.FORWARD_THREE_PRIME),
                        fields.numberOrZero(ReportColumn.FORWARD_UNALIGNED)),
                new ReportRow.Binding(fields.text(ReportColumn.REVERSE_PRIMERS),
                        fields.numberOrZero(ReportColumn.REVERSE_MISMATCHES),
                        fields.numberOrZero(ReportColumn.REVERSE_THREE_PRIME),
                        fields.numberOrZero(ReportColumn.REVERSE_UNALIGNED)),
                probe, source(description));
    }

    private static final Pattern READ_SUPPORT = Pattern.compile(Pattern.quote(ReadLevelCaller.ROW_PREFIX)
            + ": complete amplicon in (\\d+) of (\\d+) informative read\\(s\\) \\((\\d+%)\\)");
    private static final Pattern EXTENSION_SUPPORT = Pattern.compile(Pattern.quote(ReadExtensionCaller.ROW_PREFIX)
            + ".*?(\\d+) of (\\d+) forward-seeded and (\\d+) of (\\d+) reverse-seeded reads");

    /**
     * Where a row read back from a report was called, from its {@code Contig Description}: a run
     * keeps this as data ({@link ReportRow#source}); a report on disk keeps only the text.
     */
    static ReportRow.Source source(String description) {
        Matcher reads = READ_SUPPORT.matcher(description);
        if (reads.lookingAt()) {
            return new ReportRow.Source.Reads(Integer.parseInt(reads.group(1)), Integer.parseInt(reads.group(2)),
                    reads.group(3));
        }
        Matcher extension = EXTENSION_SUPPORT.matcher(description);
        if (extension.lookingAt()) {
            return new ReportRow.Source.Extension(Integer.parseInt(extension.group(1)),
                    Integer.parseInt(extension.group(2)), Integer.parseInt(extension.group(3)),
                    Integer.parseInt(extension.group(4)));
        }
        return ReportRow.Source.ASSEMBLY;
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
