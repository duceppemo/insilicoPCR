package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.Products;
import ca.canada.inspection.pcr.ReportRow;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Writes the final consolidated PCR/qPCR report. Shared by the command-line
 * pipeline and the GUI pipeline so both report identical amplicons.
 */
public final class ConsolidatedReport {

    private ConsolidatedReport() {
    }

    /**
     * Writes {@code rows} as {@code report.tsv} (or {@code report(n).tsv} when it already exists)
     * into {@code consolidatedDir} and returns the file written. The rows are the run's, in
     * order ({@link Products#ofRun}); this only writes them. When the
     * primer file declares product sizes, each row is labelled with what its size is for the
     * assay ({@link ExpectedSizes#COLUMN}, after the columns of 1.x); the label never removes a row.
     */
    public static Path write(Path consolidatedDir, List<ReportRow> rows, PrimerPanel primers) {
        ExpectedSizes expectedSizes = primers.expectedSizes();
        // Every column, every report (since 1.0): the probe columns and the size check were left out when no assay
        // had a probe or a size, which moved the columns after them.
        Path report = nextAvailableReportFile(consolidatedDir);

        // A new file: never over another run's report. Its lines end in LF on every system, as the other files a
        // run writes do: on Windows, newLine() wrote CRLF in some files and not others.
        boolean created = false;
        boolean written = false;
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                created = true;
                writer.write(HEADER);
                writer.write('\n');
                for (ReportRow row : rows) {
                    writer.write(String.join("\t", ReportColumn.BEFORE_SIZE_CHECK.stream()
                            .map(column -> column.value(row)).toList()));
                    writer.write("\t" + expectedSizes.labelText(row.assay(), row.size()));
                    for (ReportColumn column : ReportColumn.AFTER_SIZE_CHECK) {
                        writer.write("\t" + column.value(row));
                    }
                    writer.write('\n');
                }
            }
            written = true; // closed, so flushed: whole
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write consolidated report: " + report, e);
        } finally {
            // A report cut short (a full disk) would take its number from the next run: removed, if this made it.
            if (created && !written) {
                ca.canada.inspection.util.OutputFiles.deleteQuietly(report);
            }
        }
        return report;
    }

    /** The names {@link #write} gives its reports: {@code report.tsv}, then {@code report(1).tsv} and on. */
    private static final Pattern REPORT_NAME = Pattern.compile("report(\\(\\d+\\))?\\.tsv");

    /** The name of {@code report} without {@code .tsv}: {@code report(1)} for {@code report(1).tsv}. */
    public static String baseName(Path report) {
        return report.getFileName().toString().replaceFirst("\\.tsv$", "");
    }

    /**
     * A file that belongs to {@code report}, next to it: its base name and {@code suffix}
     * ({@code report(1).html}, {@code report(1)_fastq_diagnostics.tsv}).
     */
    public static Path sibling(Path report, String suffix) {
        return report.resolveSibling(baseName(report) + suffix);
    }

    /** Whether {@code file} is named as {@link #write} names a consolidated report. */
    public static boolean isReportFile(Path file) {
        return REPORT_NAME.matcher(file.getFileName().toString()).matches();
    }

    /**
     * The name of a new report: numbered after the highest one in the folder ({@code report.tsv}
     * when there is none), so that a run's number says it is the latest even when an earlier report
     * was deleted. Taking the lowest free name gave a later run a lower number than an earlier one,
     * and Open Previous Run, which opens the highest, opened the earlier run.
     */
    static Path nextAvailableReportFile(Path consolidatedDir) {
        int highest = -1;
        try (java.util.stream.Stream<Path> files = Files.list(consolidatedDir)) {
            for (Path file : files.toList()) {
                java.util.regex.Matcher name = REPORT_NAME.matcher(file.getFileName().toString());
                if (name.matches()) {
                    highest = Math.max(highest, number(name.group(1)));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write consolidated report: " + consolidatedDir, e);
        }
        if (highest + 1 >= MAX_NUMBER) {
            // A report numbered past it would not be counted, and written again by the run after.
            throw new IllegalStateException("The folder " + consolidatedDir + " holds report(" + highest + ").tsv:"
                    + " no report number is left in it; choose another output folder");
        }
        return consolidatedDir.resolve(highest < 0 ? "report.tsv" : "report(" + (highest + 1) + ").tsv");
    }

    /**
     * Fails now, rather than after the run's work, when {@code consolidatedDir} has no report number left
     * ({@link #nextAvailableReportFile}).
     *
     * @throws IllegalStateException with the message to show
     */
    public static void checkRoomFor(Path consolidatedDir) {
        if (Files.isDirectory(consolidatedDir)) {
            nextAvailableReportFile(consolidatedDir);
        }
    }

    /** The highest report number a folder is taken to hold: a report(99999999999).tsv is not one of a run's. */
    public static final int MAX_NUMBER = 1_000_000;

    /**
     * The number in {@code (n)}, 0 for none (report.tsv), or -1 for {@link #MAX_NUMBER} or more: a folder
     * holding report(99999999999).tsv failed every run after its BLAST work, and report(2147483647).tsv gave the
     * next report a negative number, then every later run failed.
     */
    public static int number(String parenthesised) {
        if (parenthesised == null) {
            return 0;
        }
        String digits = parenthesised.substring(1, parenthesised.length() - 1);
        // At the cap itself too: its next report would be past it, never counted, and written again (round 11
        // left report(1000000).tsv counted, and every run after the next failed).
        return digits.length() > 7 || Integer.parseInt(digits) >= MAX_NUMBER ? -1 : Integer.parseInt(digits);
    }

    /** The names of every report's columns, in order: those of {@link ReportColumn}, the size check among them. */
    public static final List<String> COLUMNS = java.util.stream.Stream.of(
            ReportColumn.BEFORE_SIZE_CHECK.stream().map(ReportColumn::header),
            java.util.stream.Stream.of(ExpectedSizes.COLUMN),
            ReportColumn.AFTER_SIZE_CHECK.stream().map(ReportColumn::header)).flatMap(names -> names).toList();

    /** The header line of every report ({@link #COLUMNS}). */
    public static final String HEADER = String.join("\t", COLUMNS);
}
