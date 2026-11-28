package ca.canada.inspection.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a run reported, read from its files by column name: the products of {@code report.tsv}, and for a sample and
 * assay without one, what the run said of it ({@link #said}). This class is the whole of what the benchmark needs
 * of the program's output; a version that writes its results otherwise changes this class and nothing else.
 */
final class Observed {

    /** A sample and an assay. */
    record Key(String sample, String assay) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int bySample = sample.compareTo(other.sample);
            return bySample != 0 ? bySample : assay.compareTo(other.assay);
        }
    }

    /** One product of the report. */
    record Product(int size, String source, String pairing) {
    }

    private final Map<Key, List<Product>> products = new TreeMap<>();
    private final Map<Key, String> said = new TreeMap<>();

    private Observed() {
    }

    private static final java.util.regex.Pattern CONTIG_ENDS = java.util.regex.Pattern
            .compile("Primers at contig ends: sample (.+?), assay (.+?): no product");

    /** Reads the reports of the run written into {@code output}. */
    static Observed of(Path output) throws IOException {
        Path reports = output.resolve("consolidated_report");
        return ofText(text(reports.resolve("report.tsv")), text(reports.resolve("report_no_product.tsv")),
                text(output.resolve("QAlog.txt")), text(reports.resolve("report_fastq_diagnostics.tsv")));
    }

    /**
     * What a run reported, from the text of its files (empty for a file it did not write): the products, the reasons
     * of the assays without one, the run's log of settings and notes, and the reads' diagnostics.
     */
    static Observed ofText(String report, String noProduct, String qaLog, String diagnostics) {
        Observed observed = new Observed();
        for (Map<String, String> row : rows(report)) {
            observed.products.computeIfAbsent(new Key(row.get("Sample"), row.get("Assay")), key -> new ArrayList<>())
                    .add(new Product(Integer.parseInt(row.get("AmpliconSize")), row.getOrDefault("Source", ""),
                            row.getOrDefault("Pairing", "")));
        }
        // Later files win: the reads' status says more than "no site in the mini-assembly".
        Map<Key, Set<String>> reasons = new LinkedHashMap<>();
        for (Map<String, String> row : rows(noProduct)) {
            reasons.computeIfAbsent(new Key(row.get("Sample"), row.get("Assay")), key -> new LinkedHashSet<>())
                    .add(reason(row.get("Reason")));
        }
        reasons.forEach((key, texts) -> observed.said.put(key, String.join(" + ", texts)));
        qaLog.lines().forEach(line -> {
            java.util.regex.Matcher split = CONTIG_ENDS.matcher(line);
            if (split.find()) {
                observed.said.put(new Key(split.group(1), split.group(2)), "PRIMERS_AT_CONTIG_ENDS");
            }
        });
        for (Map<String, String> row : rows(diagnostics)) {
            observed.said.put(new Key(row.get("Sample"), row.get("Assay")), row.get("Status"));
        }
        return observed;
    }

    private static String text(Path file) throws IOException {
        return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    }

    /** The products of a sample and assay; empty for none. */
    List<Product> products(Key key) {
        return products.getOrDefault(key, List.of());
    }

    /** Every sample and assay with a product. */
    Set<Key> withProducts() {
        return products.keySet();
    }

    /** What the run said of a sample and assay beside its products: a status, a reason, or empty. */
    String said(Key key) {
        return said.getOrDefault(key, "");
    }

    /**
     * A reason of {@code report_no_product.tsv} as a few words: the sentence up to its first number or detail, which
     * change from site to site.
     */
    static String reason(String text) {
        if (text == null) {
            return "";
        }
        if (text.startsWith("no site: ")) {
            return "no site found";
        }
        if (text.contains("fails the 3' rule")) {
            return "3' rule";
        }
        if (text.startsWith("no site reaches")) {
            return text.contains("on a perfect match") ? "oligo Tm too low" : "site Tm too low";
        }
        if (text.startsWith("every oligo has a site")) {
            return "sites do not pair";
        }
        if (text.startsWith("its product is reported under")) {
            return "reported under another assay";
        }
        return text.length() > 40 ? text.substring(0, 40) : text;
    }

    /**
     * The rows of a TSV text with a header line, each by column name. A cell's leading apostrophe, which the program
     * writes before text a spreadsheet would take for a formula, is taken off.
     */
    static List<Map<String, String>> rows(String text) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> lines = text.lines().toList();
        if (lines.isEmpty()) {
            return rows;
        }
        String[] header = lines.getFirst().split("\t", -1);
        for (String line : lines.subList(1, lines.size())) {
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split("\t", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                String cell = i < cells.length ? cells[i] : "";
                row.put(header[i], cell.startsWith("'") ? cell.substring(1) : cell);
            }
            rows.add(row);
        }
        return rows;
    }
}
