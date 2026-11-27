package ca.canada.inspection.report;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.util.InputCheck;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes {@code report_samples.tsv} beside a report ({@code report(1)_samples.tsv} beside {@code report(1).tsv}):
 * every sample of the run, analysed or left out and why, with its products counted. A sample with no product has
 * no row in the report, so this is where a script finds the negatives (since 1.0; before, only the lines of
 * {@code QAlog.txt} listed the samples).
 */
public final class SamplesReport {

    /** The file's name after the report's own: {@code report_samples.tsv}. */
    public static final String SUFFIX = "_samples.tsv";

    public static final String HEADER = String.join("\t", "Sample", "Status", "Input", "Files", "Products", "Reason");

    /** A sample analysed. */
    public static final String ANALYSED = "analysed";
    /** A sample left out for a faulty input file (named in a warning and in QAlog.txt). */
    public static final String NOT_ANALYSED = "not-analysed";

    private SamplesReport() {
    }

    /**
     * Writes the samples of the run that wrote {@code report}, sorted by name, and returns the file.
     *
     * @param samples the samples analysed
     * @param issues  the samples left out, one or more problems each
     * @param rows    the report's rows, counted per sample
     */
    public static Path write(Path report, Collection<Sample> samples, List<InputCheck.Issue> issues,
                             List<ReportRow> rows) {
        // Products, not rows: one product is one assay at one place, whatever rows show it.
        Map<String, Integer> products = new TreeMap<>();
        rows.stream().map(row -> List.of(row.sample(), row.assay(), row.contig(), String.valueOf(row.start()),
                String.valueOf(row.end()))).distinct().forEach(product -> products.merge(product.getFirst(), 1, Integer::sum));
        Map<String, List<String>> lines = new TreeMap<>();
        for (Sample sample : samples) {
            lines.put(sample.getName(), List.of(sample.getName(), ANALYSED, input(sample.getFileType()),
                    String.join("; ", sample.files().stream().map(file -> file.getFileName().toString()).toList()),
                    String.valueOf(products.getOrDefault(sample.getName(), 0)), ""));
        }
        Map<String, List<InputCheck.Issue>> leftOut = new LinkedHashMap<>();
        issues.forEach(issue -> leftOut.computeIfAbsent(issue.sample(), name -> new ArrayList<>()).add(issue));
        leftOut.forEach((name, problems) -> lines.putIfAbsent(name, List.of(name, NOT_ANALYSED,
                problems.stream().flatMap(issue -> issue.files().stream()).anyMatch(SamplesReport::reads) ? "reads"
                        : "assembly",
                String.join("; ", problems.stream().flatMap(issue -> issue.sampleFiles().stream()).distinct().toList()), "",
                String.join("; ", problems.stream().map(issue -> issue.problem()
                        + (issue.detail().isEmpty() ? "" : " (" + issue.detail() + ")")).toList()))));

        Path file = ConsolidatedReport.sibling(report, SUFFIX);
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.write('\n');
            for (List<String> line : lines.values()) {
                writer.write(String.join("\t", line.stream().map(ReportColumn::cell).toList()));
                writer.write('\n');
            }
        } catch (IOException e) {
            ca.canada.inspection.util.OutputFiles.deleteQuietly(file); // cut short, it would be read as whole
            throw new UncheckedIOException("Unable to write the samples of the report: " + file, e);
        }
        return file;
    }

    /** Whether a file named {@code name} holds reads, as the run names its input files. */
    private static boolean reads(String name) {
        return ca.canada.inspection.util.SequenceFileUtils.hasExtension(name,
                ca.canada.inspection.util.SequenceFileUtils.FASTQ_EXTENSIONS);
    }

    private static String input(Sample.FileType type) {
        return type == Sample.FileType.FASTQ ? "reads" : "assembly";
    }
}
