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
     * assay (last column, {@link ExpectedSizes#COLUMN}); the label never removes a row.
     */
    public static Path write(Path consolidatedDir, List<ReportRow> rows, PrimerPanel primers) {
        ExpectedSizes expectedSizes = primers.expectedSizes();
        // The probe columns are present whenever at least one assay defines a probe.
        boolean probeColumns = primers.anyProbe();
        boolean sizeColumn = !expectedSizes.isEmpty();
        List<ReportColumn> columns = ReportColumn.of(probeColumns);
        Path report = nextAvailableReportFile(consolidatedDir);

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(header(probeColumns, sizeColumn));
            writer.newLine();
            for (ReportRow row : rows) {
                writer.write(String.join("\t", columns.stream().map(column -> column.value(row)).toList()));
                if (sizeColumn) {
                    writer.write("\t" + expectedSizes.labelText(row.assay(), row.size()));
                }
                writer.newLine();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write consolidated report: " + report, e);
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

    private static Path nextAvailableReportFile(Path consolidatedDir) {
        Path report = consolidatedDir.resolve("report.tsv");
        int counter = 1;
        while (Files.exists(report)) {
            report = consolidatedDir.resolve("report(" + counter + ").tsv");
            counter++;
        }
        return report;
    }

    private static String header(boolean probeColumns, boolean sizeColumn) {
        List<String> columns = new ArrayList<>(ReportColumn.of(probeColumns).stream().map(ReportColumn::header).toList());
        if (sizeColumn) {
            columns.add(ExpectedSizes.COLUMN);
        }
        return String.join("\t", columns);
    }
}
