package ca.canada.inspection.report;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
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
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the final consolidated PCR/qPCR report. Shared by the command-line
 * pipeline and the GUI pipeline so both report identical amplicons.
 */
public final class ConsolidatedReport {

    private ConsolidatedReport() {
    }

    /**
     * Writes {@code report.tsv} (or {@code report(n).tsv} when it already exists)
     * into {@code consolidatedDir} and returns the file written.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             PrimerPanel primers, AmpliconOptions options) {
        return write(consolidatedDir, samples, primers, options, Map.of());
    }

    /**
     * As {@link #write(Path, Map, PrimerPanel, AmpliconOptions)}, appending {@code extraRows}
     * (keyed by sample name) after each sample's own rows: the products FASTQ mode calls on
     * reads rather than contigs. When the primer file declares product sizes, each row is
     * labelled with what its size is for the assay (last column, {@link ExpectedSizes#COLUMN});
     * the label never removes a row.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             PrimerPanel primers, AmpliconOptions options,
                             Map<String, List<ReportRow>> extraRows) {
        ExpectedSizes expectedSizes = primers.expectedSizes();
        // The probe columns are present whenever at least one assay defines a probe.
        boolean probeColumns = primers.anyProbe();
        boolean sizeColumn = !expectedSizes.isEmpty();
        List<ReportColumn> columns = ReportColumn.of(probeColumns);
        Path report = nextAvailableReportFile(consolidatedDir);

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(header(probeColumns, sizeColumn));
            writer.newLine();
            // TreeMap keeps samples in a stable, alphabetical order.
            for (Map.Entry<String, Sample> sampleEntry : new TreeMap<>(samples).entrySet()) {
                List<ReportRow> rows = new ArrayList<>(
                        Products.of(sampleEntry.getKey(), sampleEntry.getValue(), primers, options));
                rows.addAll(extraRows.getOrDefault(sampleEntry.getKey(), List.of()));
                for (ReportRow row : rows) {
                    writer.write(String.join("\t", columns.stream().map(column -> column.value(row)).toList()));
                    if (sizeColumn) {
                        writer.write("\t" + expectedSizes.labelText(row.assay(), row.size()));
                    }
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write consolidated report: " + report, e);
        }
        return report;
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
