package ca.canada.inspection.report;

import ca.canada.inspection.pcr.NoProduct;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes {@code report_no_product.tsv} beside a report ({@code report(1)_no_product.tsv} beside {@code report(1).tsv},
 * since 2.0): each assay without a product in a sample, and why ({@link NoProduct}). The report lists what the
 * reactions make; this, what they do not, and the rule that decided it.
 */
public final class NoProductReport {

    /** The file's name after the report's own: {@code report_no_product.tsv}. */
    public static final String SUFFIX = "_no_product.tsv";

    public static final String HEADER = String.join("\t", "Sample", "Assay", "Oligo", "Reason", "BestSite",
            "BestSiteTm", "NeededTm");

    private NoProductReport() {
    }

    /** Writes {@code noProducts} beside {@code report} and returns the file. */
    public static Path write(Path report, List<NoProduct> noProducts) {
        Path file = ConsolidatedReport.sibling(report, SUFFIX);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write('\n');
            for (NoProduct row : noProducts) {
                writer.write(String.join("\t", List.of(row.sample(), row.assay(), row.oligo(), row.reason(),
                        row.site(), ReportColumn.tm(row.siteTm()), ReportColumn.tm(row.neededTm())).stream()
                        .map(ReportColumn::cell).toList()));
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write the assays without a product: " + file, e);
        }
        return file;
    }
}
