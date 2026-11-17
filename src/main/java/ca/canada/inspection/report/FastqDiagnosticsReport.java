package ca.canada.inspection.report;

import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes the findings of {@link FastqDiagnostics} as {@code report_fastq_diagnostics.tsv}. */
public final class FastqDiagnosticsReport {

    public static final String HEADER = String.join("\t",
            "Sample", "Gene", "Status", "Assembly", "ReadsWithForward", "ReadsWithReverse", "ReadsWithProbe",
            "CheckableLength", "ReadDetail", "AssemblyDetail");

    private FastqDiagnosticsReport() {
    }

    /** Writes the findings as TSV; the header is written even when there are none. */
    public static void write(Path file, List<Finding> findings) {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write('\n');
            for (Finding finding : findings) {
                writer.write(String.join("\t",
                        ReportColumn.cell(finding.sample()),
                        ReportColumn.cell(finding.assay()),
                        finding.status().name(),
                        finding.assembly().name(),
                        String.valueOf(finding.readsWithOligo().forward()),
                        String.valueOf(finding.readsWithOligo().reverse()),
                        String.valueOf(finding.readsWithOligo().probe()),
                        String.valueOf(finding.checkableLength()),
                        ReportColumn.cell(finding.readDetail()),
                        ReportColumn.cell(finding.assemblyDetail())));
                writer.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write FASTQ diagnostics: " + file, e);
        }
    }
}
