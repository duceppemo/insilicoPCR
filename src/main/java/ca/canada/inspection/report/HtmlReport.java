package ca.canada.inspection.report;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.SplitAcrossContigs;
import ca.canada.inspection.util.InputCheck;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Writes the consolidated report as one self-contained HTML page (no network, no
 * external files), next to the TSV it is built from.
 *
 * <p>The page answers "which samples are positive for which assay" at a glance: a
 * sample by assay grid, a summary per assay, then every amplicon. It hides nothing
 * the TSV holds. When the primer file declares product sizes
 * ({@link ExpectedSizes}), a product of the target size is told apart from a product
 * of another size: both are positive PCRs, only the first is what the assay is
 * designed to give.</p>
 */
public final class HtmlReport {

    /** What one sample shows for one assay. */
    public enum State {
        /** A product of the size the assay declares as its target. */
        TARGET("Target size", "Product of the size the assay is designed to give", "✓"),
        /** A product of an assay that declares no size: amplified, size left to the reader. */
        AMPLIFIED("Amplified", "Product found; the primer file declares no size for this assay, so check the size", ""),
        /** Only a product the assay is known to give besides its target. */
        OTHER("Other known product", "Only a product declared as another known size (other=), not the target", "≈"),
        /** Only a product of a size the primer file does not declare. */
        UNEXPECTED("Unexpected size", "Only a product of a size the primer file does not declare", "!"),
        /** A complete amplicon in too few of the reads to be reported (FASTQ input). */
        INCONCLUSIVE("Seen in too few reads", "A complete amplicon is in some reads, too few to be reported", "?"),
        /** Extended reads join the primers, but read pairs do not confirm the product (FASTQ input). */
        UNCONFIRMED("Not confirmed by read pairs", "Reads extended from both primers hold a product, but the read"
                + " pairs do not confirm it; not reported", "?"),
        /** No product on any one contig, but the primers point off contig ends: one could span them (FASTA input). */
        ACROSS_ENDS("Primers at contig ends", "No product on any one contig, but the primers point off the ends of two"
                + " contigs, or both ends of one: a product could span the break, or the end of a circular sequence",
                "\u25c7"),
        /** No product, and the reads could not have shown one of the declared size (FASTQ input). */
        UNCHECKED("Not checkable from reads", "No product, but one of the declared size is longer than the reads"
                + " could hold, so its absence proves nothing", "\u25cb"),
        /** No product. */
        NONE("No product", "No product", "·");

        private final String label;
        private final String description;
        private final String glyph;

        State(String label, String description, String glyph) {
            this.label = label;
            this.description = description;
            this.glyph = glyph;
        }

        public String label() {
            return label;
        }

        String css() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One product of the consolidated report, with what the page adds to it.
     *
     * @param forwardLength length of the forward primer of the row, 0 when unknown
     * @param label         what its size is for the assay, or {@code null} when the assay declares none
     */
    record Product(ReportRow row, int forwardLength, int reverseLength, ExpectedSizes.Label label) {
        String sample() {
            return row.sample();
        }

        String assay() {
            return row.assay();
        }

        int size() {
            return row.size();
        }

        /** Called from the reads themselves, whole or extended, not from the mini-assembly. */
        boolean fromReads() {
            return row.contigDescription().startsWith("FASTQ read-level call") || fromExtension();
        }

        /** Called on reads extended through the baited reads: a product no single read holds. */
        boolean fromExtension() {
            return row.contigDescription().startsWith(ReadExtensionCaller.ROW_PREFIX);
        }

        /** Bases at the 5' end of the forward or reverse primer that do not pair with the template. */
        int unaligned(boolean forward) {
            return forward ? row.forwardUnpaired() : row.reverseUnpaired();
        }

        /** Whether a primer is held by part of its length only: its 5' end does not pair with the template. */
        boolean partlyBound() {
            return unaligned(true) > 0 || unaligned(false) > 0;
        }

        /** How much of each primer pairs, e.g. {@code forward primer 9 of 17 bases, reverse primer 17 of 17}. */
        String binding() {
            return "forward primer " + binding(true) + ", reverse primer " + binding(false);
        }

        private String binding(boolean forward) {
            int length = forward ? forwardLength : reverseLength;
            int unaligned = unaligned(forward);
            return length > 0 ? (length - unaligned) + " of " + length + " bases"
                    : unaligned + " of its 5′ bases unpaired";
        }

        /** The strand as the report writes it; empty when the report has none. */
        String strand() {
            return row.strand() == ' ' ? "" : String.valueOf(row.strand());
        }

        /** The probe's mismatches; empty for a row without probe. */
        String probeMismatches() {
            return row.probe() == null ? "" : String.valueOf(row.probe().mismatches());
        }
    }

    /** Marks a product whose primer is held by part of its length only. */
    private static final String PARTLY_BOUND = "\u25d0";
    private static final String PARTLY_BOUND_LABEL = "A primer binds only partly (5′ bases unpaired)";

    /** One cell of the grid. */
    record Cell(State state, List<Product> products, String note) {
        /** Sizes of the products that decide the state, smallest first. */
        List<Integer> sizes() {
            ExpectedSizes.Label wanted = switch (state) {
                case TARGET -> ExpectedSizes.Label.TARGET;
                case OTHER -> ExpectedSizes.Label.OTHER;
                case UNEXPECTED -> ExpectedSizes.Label.UNEXPECTED;
                default -> null;
            };
            return new ArrayList<>(products.stream()
                    .filter(product -> state == State.AMPLIFIED || product.label() == wanted)
                    .map(Product::size)
                    .collect(Collectors.toCollection(TreeSet::new)));
        }

        /**
         * Whether every product that decides the state has a primer held by part of its
         * length only: the state then rests on primers that may not anneal in a reaction.
         */
        boolean partlyBound() {
            List<Integer> deciding = sizes();
            return !deciding.isEmpty() && products.stream()
                    .filter(product -> deciding.contains(product.size()))
                    .allMatch(Product::partlyBound);
        }
    }

    private static final Pattern READ_SUPPORT =
            Pattern.compile("complete amplicon in (\\d+ of \\d+) informative read\\(s\\) \\((\\d+%)\\)");

    private static final Pattern EXTENSION_SUPPORT =
            Pattern.compile("(\\d+ of \\d+) forward-seeded and (\\d+ of \\d+) reverse-seeded reads");

    private HtmlReport() {
    }

    /**
     * Writes {@code <report>.html} next to {@code reportTsv}.
     *
     * @param reportTsv    consolidated report just written; the page shows exactly its rows
     * @param sampleNames  every sample of the run, so that samples without a product show too
     * @param fastqSamples samples given as reads (marked in the page)
     * @param primers      the run's primers: they give the assays, the primer lengths (so the page
     *                     says how many bases of each primer pair) and the declared product sizes
     * @param findings     FASTQ-mode diagnostics; may be empty
     * @param runDetails   label and value pairs listed under "Run details", in order
     * @param inputIssues  files that could not be analysed; their samples are listed apart, as
     *                     neither positive nor negative
     * @param splits       assays without a product whose product could span a break in the assembly
     * @return the HTML file written
     */
    public static Path write(Path reportTsv, Collection<String> sampleNames, Set<String> fastqSamples,
                             PrimerPanel primers, List<Finding> findings, Map<String, String> runDetails,
                             List<InputCheck.Issue> inputIssues, List<SplitAcrossContigs.Split> splits) {
        Path html = pathFor(reportTsv);
        ExpectedSizes sizes = primers.expectedSizes();
        try {
            String page = render(reportTsv.getFileName().toString(), readProducts(reportTsv, primers), sampleNames,
                    fastqSamples, List.copyOf(primers.completeAssays().keySet()),
                    sizes, findings, runDetails, inputIssues, splits);
            Files.writeString(html, page, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write HTML report: " + html, e);
        }
        return html;
    }

    /** The HTML page of a consolidated report: {@code report(1).html} for {@code report(1).tsv}. */
    public static Path pathFor(Path reportTsv) {
        return reportTsv.resolveSibling(reportTsv.getFileName().toString().replaceFirst("\\.tsv$", "") + ".html");
    }

    /** The amplicon settings of a run as "Run details" entries. */
    public static Map<String, String> settings(AmpliconOptions options) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Primer mismatches (-m)", String.valueOf(options.maxTotalMismatches()));
        details.put("Probe mismatches", String.valueOf(options.maxProbeMismatches()));
        details.put("3' window / max penalty", options.threePrimeWindow() + " / " + options.maxThreePrimePenalty());
        details.put("5' truncation allowed", String.valueOf(options.maxFivePrimeTruncation()));
        details.put("Product length", options.minAmpliconLength() + " to "
                + (options.maxAmpliconLength() > 0 ? options.maxAmpliconLength() + " bp" : "no limit"));
        return details;
    }

    static List<Product> readProducts(Path reportTsv, PrimerPanel primers) {
        return ReportTsv.read(reportTsv).stream()
                .map(row -> new Product(row, primers.length(row.forwardPrimer()),
                        primers.length(row.reversePrimer()), primers.expectedSizes().label(row.assay(), row.size())))
                .toList();
    }

    /** Decides what a sample shows for an assay from its products and, without any, from the reads. */
    static Cell cell(String assay, List<Product> products, ExpectedSizes sizes, Finding finding) {
        return cell(assay, products, sizes, finding, null);
    }

    /** As {@link #cell(String, List, ExpectedSizes, Finding)}; {@code split} says where a product could span contigs. */
    static Cell cell(String assay, List<Product> products, ExpectedSizes sizes, Finding finding,
                     SplitAcrossContigs.Split split) {
        if (!products.isEmpty()) {
            State state;
            if (products.stream().allMatch(product -> product.label() == null)) {
                state = State.AMPLIFIED;
            } else if (products.stream().anyMatch(product -> product.label() == ExpectedSizes.Label.TARGET)) {
                state = State.TARGET;
            } else if (products.stream().anyMatch(product -> product.label() == ExpectedSizes.Label.OTHER)) {
                state = State.OTHER;
            } else {
                state = State.UNEXPECTED;
            }
            return new Cell(state, products, "");
        }
        if (split != null) {
            return new Cell(State.ACROSS_ENDS, products, split.note() + " Check with a more complete assembly.");
        }
        if (finding != null && finding.status() == Status.WEAK_READ_SUPPORT) {
            return new Cell(State.INCONCLUSIVE, products, "Not reported: " + finding.readDetail());
        }
        if (finding != null && (finding.status() == Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS
                || finding.status() == Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT)) {
            return new Cell(State.UNCONFIRMED, products, "Not reported: " + finding.readDetail());
        }
        if (finding != null && finding.status() == Status.NOT_CLOSED_BY_EXTENSION) {
            // The reads were extended, so the product's length is no longer what hides it.
            return new Cell(State.NONE, products, "Both primers are in the reads: " + finding.readDetail());
        }
        OptionalInt target = sizes.smallestTarget(assay);
        if (finding != null && finding.status() == Status.NOT_IN_READS && target.isPresent()
                && target.getAsInt() > finding.checkableLength()) {
            // The declared product cannot fit in a read: its absence from reads proves nothing.
            return new Cell(State.UNCHECKED, products, "Both primers are in the reads, but the mini-assembly"
                    + " did not join them, and a product of the declared size (" + sizes.describe(assay)
                    + ") is longer than any read could hold (" + finding.checkableLength()
                    + " bp). Confirm on an assembly of the reads.");
        }
        return new Cell(State.NONE, products, "");
    }

    static String render(String reportName, List<Product> products, Collection<String> sampleNames,
                         Set<String> fastqSamples, List<String> assays, ExpectedSizes sizes,
                         List<Finding> findings, Map<String, String> runDetails) {
        return render(reportName, products, sampleNames, fastqSamples, assays, sizes, findings, runDetails, List.of());
    }

    static String render(String reportName, List<Product> products, Collection<String> sampleNames,
                         Set<String> fastqSamples, List<String> assays, ExpectedSizes sizes,
                         List<Finding> findings, Map<String, String> runDetails,
                         List<InputCheck.Issue> inputIssues) {
        return render(reportName, products, sampleNames, fastqSamples, assays, sizes, findings, runDetails, inputIssues,
                List.of());
    }

    static String render(String reportName, List<Product> products, Collection<String> sampleNames,
                         Set<String> fastqSamples, List<String> assays, ExpectedSizes sizes,
                         List<Finding> findings, Map<String, String> runDetails,
                         List<InputCheck.Issue> inputIssues, List<SplitAcrossContigs.Split> splits) {
        Set<String> samples = new TreeSet<>(sampleNames);
        Set<String> assayOrder = new TreeSet<>(assays);
        Map<String, List<Product>> byPair = new HashMap<>();
        for (Product product : products) {
            samples.add(product.sample());
            assayOrder.add(product.assay());
            byPair.computeIfAbsent(key(product.sample(), product.assay()), ignored -> new ArrayList<>()).add(product);
        }
        Map<String, Finding> findingByPair = new HashMap<>();
        for (Finding finding : findings) {
            findingByPair.putIfAbsent(key(finding.sample(), finding.assay()), finding);
        }
        Map<String, SplitAcrossContigs.Split> splitByPair = new HashMap<>();
        for (SplitAcrossContigs.Split split : splits) {
            splitByPair.putIfAbsent(key(split.sample(), split.assay()), split);
        }
        // sample -> assay -> cell, in display order
        Map<String, Map<String, Cell>> grid = new LinkedHashMap<>();
        Map<State, Integer> totals = new EnumMap<>(State.class);
        for (String sample : samples) {
            Map<String, Cell> row = new LinkedHashMap<>();
            for (String assay : assayOrder) {
                String key = key(sample, assay);
                Cell cell = cell(assay, byPair.getOrDefault(key, List.of()), sizes, findingByPair.get(key),
                        splitByPair.get(key));
                row.put(assay, cell);
                totals.merge(cell.state(), 1, Integer::sum);
            }
            grid.put(sample, row);
        }
        boolean anyDeclared = assayOrder.stream().anyMatch(sizes.assays()::contains);
        boolean anyUndeclared = assayOrder.stream().anyMatch(assay -> !sizes.assays().contains(assay));

        StringBuilder html = new StringBuilder(64_000);
        html.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>insilicoPCR report</title>\n<style>\n").append(page("report.css")).append("</style>\n</head>\n<body>\n")
                .append("<main>\n<header>\n<h1>insilicoPCR report</h1>\n<p class=\"sub\">")
                .append(escape(String.join(" · ", headline(runDetails, reportName)))).append("</p>\n</header>\n");

        long notAnalysed = inputIssues.stream().map(InputCheck.Issue::sample).distinct().count();
        tiles(html, samples.size(), assayOrder.size(), products.size(), totals, anyDeclared, anyUndeclared,
                (int) notAnalysed);
        inputSection(html, inputIssues);
        gridSection(html, grid, assayOrder, sizes, fastqSamples, totals, anyDeclared);
        assaySection(html, grid, assayOrder, sizes);
        productSection(html, products, sizes);
        readSection(html, findings);
        splitSection(html, splits);
        detailSection(html, runDetails, reportName, anyDeclared);

        html.append("</main>\n<script>\n").append(page("report.js")).append("</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private static List<String> headline(Map<String, String> runDetails, String reportName) {
        List<String> parts = new ArrayList<>();
        for (String label : List.of("Date", "insilicoPCR version")) {
            if (runDetails.containsKey(label)) {
                parts.add(label.equals("Date") ? runDetails.get(label) : "version " + runDetails.get(label));
            }
        }
        parts.add(reportName);
        return parts;
    }

    private static void tiles(StringBuilder html, int samples, int assays, int products, Map<State, Integer> totals,
                              boolean anyDeclared, boolean anyUndeclared, int notAnalysed) {
        html.append("<section class=\"tiles\" aria-label=\"Summary\">\n");
        tile(html, samples, samples == 1 ? "sample" : "samples", "");
        if (notAnalysed > 0) {
            tile(html, notAnalysed, notAnalysed == 1 ? "sample not analysed: faulty input file"
                    : "samples not analysed: faulty input files", "unexpected");
        }
        tile(html, assays, assays == 1 ? "assay" : "assays", "");
        if (anyDeclared) {
            tile(html, totals.getOrDefault(State.TARGET, 0), "positive at target size", "target");
        }
        if (anyUndeclared) {
            tile(html, totals.getOrDefault(State.AMPLIFIED, 0), "amplified, size not declared", "amplified");
        }
        int otherSize = totals.getOrDefault(State.OTHER, 0) + totals.getOrDefault(State.UNEXPECTED, 0);
        if (anyDeclared) {
            tile(html, otherSize, "product of another size only", otherSize > 0 ? "unexpected" : "");
        }
        if (totals.getOrDefault(State.INCONCLUSIVE, 0) > 0) {
            tile(html, totals.get(State.INCONCLUSIVE), "seen in too few reads, not reported", "inconclusive");
        }
        if (totals.getOrDefault(State.UNCONFIRMED, 0) > 0) {
            tile(html, totals.get(State.UNCONFIRMED), "not confirmed by read pairs, not reported", "inconclusive");
        }
        if (totals.getOrDefault(State.ACROSS_ENDS, 0) > 0) {
            tile(html, totals.get(State.ACROSS_ENDS), "no product, primers at contig ends", "inconclusive");
        }
        tile(html, products, products == 1 ? "amplicon in the report" : "amplicons in the report", "");
        html.append("</section>\n");
    }

    /** The samples left out of the run, and why; nothing when every input file was analysed. */
    private static void inputSection(StringBuilder html, List<InputCheck.Issue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        html.append("<section>\n<h2>Input not analysed</h2>\n<p class=\"hint\">A file given for these samples is")
                .append(" faulty. Nothing was run on them and they are not in the tables below: they are neither")
                .append(" positive nor negative. Replace the files and run the samples again.</p>\n")
                .append("<div class=\"scroll\"><table class=\"rows\">\n<thead><tr><th>Sample</th><th>File</th>")
                .append("<th>Problem</th><th>Detail</th></tr></thead>\n<tbody>\n");
        for (InputCheck.Issue issue : issues) {
            html.append("<tr><td>").append(escape(issue.sample())).append("</td><td>")
                    .append(escape(String.join(", ", issue.files()))).append("</td><td class=\"wrap\">")
                    .append(escape(issue.problem())).append("</td><td class=\"wrap\">")
                    .append(escape(issue.detail())).append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n</section>\n");
    }

    private static void tile(StringBuilder html, int value, String label, String state) {
        html.append("<div class=\"tile").append(state.isEmpty() ? "" : " t-" + state).append("\"><span class=\"n\">")
                .append(value).append("</span><span class=\"l\">").append(escape(label)).append("</span></div>\n");
    }

    private static void gridSection(StringBuilder html, Map<String, Map<String, Cell>> grid, Set<String> assays,
                                    ExpectedSizes sizes, Set<String> fastqSamples, Map<State, Integer> totals,
                                    boolean anyDeclared) {
        html.append("<section>\n<h2>Samples by assay</h2>\n");
        html.append("<p class=\"hint\">Each cell is one sample tested with one assay; the number is the product size")
                .append(" in bp. Every product is listed, whatever its size. Click a cell to see its amplicons.</p>\n");
        html.append("<div class=\"legend\" aria-label=\"Legend\">");
        for (State state : State.values()) {
            if (totals.getOrDefault(state, 0) > 0 || state == State.NONE
                    || (state == State.TARGET && anyDeclared)) {
                html.append("<span class=\"key\" title=\"").append(escape(state.description))
                        .append("\"><span class=\"chip ").append(state.css()).append("\">")
                        .append(state.glyph.isEmpty() ? "bp" : state.glyph).append("</span>")
                        .append(escape(state.label)).append("</span>");
            }
        }
        html.append("<span class=\"key\"><span class=\"tag\">reads</span>FASTQ input, or a product called from reads")
                .append("</span>");
        if (grid.values().stream().flatMap(row -> row.values().stream())
                .flatMap(cell -> cell.products().stream()).anyMatch(Product::partlyBound)) {
            html.append("<span class=\"key\" title=\"The product is reported, but a primer pairs with the template")
                    .append(" over part of its length only; see the 5′ unpaired column of the amplicons\">")
                    .append("<span class=\"part\">").append(PARTLY_BOUND).append("</span>")
                    .append(escape(PARTLY_BOUND_LABEL)).append("</span>");
        }
        html.append("</div>\n");
        if (grid.size() > 12) {
            html.append("<div class=\"tools\"><input type=\"search\" id=\"sampleFilter\" placeholder=\"Filter samples…\"")
                    .append(" aria-label=\"Filter samples\"><label><input type=\"checkbox\" id=\"onlyProducts\">")
                    .append(" only samples with a product</label></div>\n");
        }
        html.append("<div class=\"scroll\"><table class=\"grid").append(assays.size() > 8 ? " many" : "")
                .append("\" id=\"grid\">\n<thead><tr><th class=\"corner\">Sample</th>");
        for (String assay : assays) {
            int positive = 0;
            for (Map<String, Cell> row : grid.values()) {
                State state = row.get(assay).state();
                positive += state == State.TARGET || state == State.AMPLIFIED ? 1 : 0;
            }
            String declared = sizes.describe(assay);
            html.append("<th scope=\"col\" title=\"").append(escape(assay + (declared.isEmpty()
                            ? ": no product size declared" : ": " + declared))).append("\"><span class=\"an\">")
                    .append(escape(assay)).append("</span><span class=\"as\">").append(positive).append('/')
                    .append(grid.size()).append("</span></th>");
        }
        html.append("</tr></thead>\n<tbody>\n");
        grid.forEach((sample, row) -> {
            boolean any = row.values().stream().anyMatch(cell -> !cell.products().isEmpty());
            html.append("<tr data-sample=\"").append(escape(sample)).append("\" data-any=\"").append(any ? 1 : 0)
                    .append("\"><th scope=\"row\">").append(escape(sample));
            if (fastqSamples.contains(sample)) {
                html.append(" <span class=\"tag\">reads</span>");
            }
            html.append("</th>");
            row.forEach((assay, cell) -> html.append("<td>").append(chip(sample, assay, cell, true)).append("</td>"));
            html.append("</tr>\n");
        });
        html.append("</tbody></table></div>\n</section>\n");
    }

    /** The coloured mark for one sample and assay: glyph and size, with the detail as tooltip. */
    private static String chip(String sample, String assay, Cell cell, boolean inGrid) {
        State state = cell.state();
        List<Integer> sizes = cell.sizes();
        StringBuilder text = new StringBuilder(state.glyph);
        if (!sizes.isEmpty()) {
            text.append(text.isEmpty() ? "" : " ").append(sizes.getFirst());
            if (sizes.size() > 1) {
                text.append(sizes.size() == 2 ? "/" + sizes.get(1) : "…");
            }
        }
        // Products that do not decide the state (e.g. an unexpected size next to the target) stay visible.
        long others = cell.products().stream().map(Product::size).distinct().count() - sizes.size();
        boolean reads = cell.products().stream().anyMatch(Product::fromReads);
        StringBuilder tip = new StringBuilder(sample + " · " + assay + ": " + state.label);
        for (Product product : cell.products()) {
            tip.append("\n").append(product.size()).append(" bp");
            if (product.label() != null) {
                tip.append(" (").append(product.label().text()).append(")");
            }
            tip.append(product.fromExtension() ? ", from extended reads" : product.fromReads() ? ", from reads" : "");
            if (product.partlyBound()) {
                tip.append("; ").append(product.binding());
            }
        }
        if (!cell.note().isEmpty()) {
            tip.append("\n").append(cell.note());
        }
        String element = cell.products().isEmpty() ? "span" : "button";
        return "<" + element + (element.equals("button") ? " type=\"button\"" : "") + " class=\"chip " + state.css()
                + "\" title=\"" + escape(tip.toString()) + "\""
                + (inGrid && !cell.products().isEmpty()
                ? " data-s=\"" + escape(sample) + "\" data-a=\"" + escape(assay) + "\"" : "")
                + ">" + escape(text.toString())
                + (others > 0 ? "<sup>+" + others + "</sup>" : "")
                + (cell.partlyBound() ? "<span class=\"part\" aria-label=\"" + escape(PARTLY_BOUND_LABEL) + "\">"
                + PARTLY_BOUND + "</span>" : "")
                + (reads ? "<span class=\"dot\" aria-label=\"from reads\"></span>" : "")
                + "</" + element + ">";
    }

    private static void assaySection(StringBuilder html, Map<String, Map<String, Cell>> grid, Set<String> assays,
                                     ExpectedSizes sizes) {
        html.append("<section>\n<h2>By assay</h2>\n<div class=\"cards\">\n");
        for (String assay : assays) {
            Map<State, List<String>> byState = new EnumMap<>(State.class);
            grid.forEach((sample, row) ->
                    byState.computeIfAbsent(row.get(assay).state(), ignored -> new ArrayList<>()).add(sample));
            boolean declares = sizes.assays().contains(assay);
            State positiveState = declares ? State.TARGET : State.AMPLIFIED;
            int positive = byState.getOrDefault(positiveState, List.of()).size();
            html.append("<article class=\"card\">\n<h3>").append(escape(assay)).append("</h3>\n<p class=\"decl\">")
                    .append(declares ? escape(sizes.describe(assay)) : "no product size declared").append("</p>\n")
                    .append("<p class=\"count\"><span class=\"n\">").append(positive).append("</span> of ")
                    .append(grid.size()).append(grid.size() == 1 ? " sample " : " samples ")
                    .append(declares ? "positive at the target size" : "amplified").append("</p>\n")
                    .append("<div class=\"meter\" role=\"img\" aria-label=\"").append(positive).append(" of ")
                    .append(grid.size()).append("\"><span class=\"").append(positiveState.css()).append("\" style=\"width:")
                    .append(grid.isEmpty() ? 0 : Math.round(1000.0 * positive / grid.size()) / 10.0)
                    .append("%\"></span></div>\n");
            for (State state : List.of(State.TARGET, State.AMPLIFIED, State.OTHER, State.UNEXPECTED,
                    State.INCONCLUSIVE, State.UNCONFIRMED)) {
                List<String> names = byState.getOrDefault(state, List.of());
                if (names.isEmpty()) {
                    continue;
                }
                html.append("<p class=\"group\"><span class=\"chip ").append(state.css()).append("\">")
                        .append(state.glyph.isEmpty() ? "bp" : state.glyph).append("</span> ")
                        .append(escape(state.label)).append(" (").append(names.size()).append(")</p>\n<ul class=\"names\">");
                for (String sample : names) {
                    Cell cell = grid.get(sample).get(assay);
                    List<Integer> found = cell.sizes();
                    html.append("<li title=\"").append(escape(cell.note())).append("\">").append(escape(sample));
                    if (!found.isEmpty()) {
                        html.append(" <span class=\"bp\">").append(found.stream().map(String::valueOf)
                                .collect(Collectors.joining(", "))).append(" bp</span>");
                    }
                    if (cell.partlyBound()) {
                        html.append(" <span class=\"part\" title=\"").append(escape(PARTLY_BOUND_LABEL))
                                .append("\">").append(PARTLY_BOUND).append("</span>");
                    }
                    html.append("</li>");
                }
                html.append("</ul>\n");
            }
            for (State state : List.of(State.ACROSS_ENDS, State.UNCHECKED, State.NONE)) {
                List<String> names = byState.getOrDefault(state, List.of());
                if (!names.isEmpty()) {
                    html.append("<details><summary>").append(names.size())
                            .append(switch (state) {
                                case NONE -> " with no product";
                                case ACROSS_ENDS -> " with the primers at contig ends";
                                default -> " not checkable from reads";
                            })
                            .append("</summary><ul class=\"names muted\">");
                    names.forEach(sample -> html.append("<li>").append(escape(sample)).append("</li>"));
                    html.append("</ul></details>\n");
                }
            }
            html.append("</article>\n");
        }
        html.append("</div>\n</section>\n");
    }

    private static void productSection(StringBuilder html, List<Product> products, ExpectedSizes sizes) {
        boolean probes = products.stream().anyMatch(product -> product.row().probe() != null);
        boolean labels = !sizes.isEmpty();
        html.append("<section id=\"amplicons\">\n<h2>All amplicons</h2>\n");
        if (products.isEmpty()) {
            html.append("<p class=\"hint\">No amplicon was reported.</p>\n</section>\n");
            return;
        }
        html.append("<div class=\"tools\"><input type=\"search\" id=\"rowFilter\" placeholder=\"Filter by sample, assay,")
                .append(" size…\" aria-label=\"Filter amplicons\"><button type=\"button\" id=\"showAll\" hidden>")
                .append("Show all</button><span id=\"rowCount\" class=\"hint\"></span></div>\n")
                .append("<div class=\"scroll\"><table class=\"rows\" id=\"rows\">\n<thead><tr><th>Sample</th><th>Assay</th>")
                .append("<th class=\"num\">Size (bp)</th>").append(labels ? "<th>Size check</th>" : "")
                .append("<th>Found on</th><th>Location</th><th class=\"num\" title=\"Forward / reverse primer mismatches\">")
                .append("Mismatches F/R</th><th class=\"num\" title=\"Imperfections in the 3' window, forward / reverse\">")
                .append("3′ F/R</th><th class=\"num\" title=\"Bases at the 5' end of each primer that do not pair with the template,")
                .append(" forward / reverse; the mismatch columns do not count them\">5′ unpaired F/R</th><th>Strand</th>")
                .append(probes ? "<th>Probe</th><th class=\"num\">Probe mismatches</th>" : "").append("</tr></thead>\n<tbody>\n");
        for (Product product : products) {
            String contig = product.row().contig();
            int comma = contig.indexOf(',');
            String source = comma > 0 ? contig.substring(0, comma) : contig;
            String description = product.row().contigDescription();
            String sourceTitle = contig + (description.isEmpty() ? "" : " — " + description);
            if (product.fromExtension()) {
                Matcher support = EXTENSION_SUPPORT.matcher(description);
                source = "reads extended" + (support.find() ? " (" + support.group(1) + " forward-seeded, "
                        + support.group(2) + " reverse-seeded)" : "");
            } else if (product.fromReads()) {
                Matcher support = READ_SUPPORT.matcher(description);
                source = "reads" + (support.find() ? " (" + support.group(1) + ", " + support.group(2) + ")" : "");
            }
            html.append("<tr data-s=\"").append(escape(product.sample())).append("\" data-a=\"")
                    .append(escape(product.assay())).append("\"><td>").append(escape(product.sample())).append("</td><td>")
                    .append(escape(product.assay())).append("</td><td class=\"num\">").append(product.size()).append("</td>");
            if (labels) {
                html.append("<td>");
                if (product.label() != null) {
                    State state = switch (product.label()) {
                        case TARGET -> State.TARGET;
                        case OTHER -> State.OTHER;
                        case UNEXPECTED -> State.UNEXPECTED;
                    };
                    html.append("<span class=\"chip ").append(state.css()).append("\">").append(state.glyph)
                            .append(' ').append(product.label().text()).append("</span>");
                }
                html.append("</td>");
            }
            html.append("<td title=\"").append(escape(sourceTitle)).append("\">")
                    .append(product.fromReads() ? "<span class=\"tag\">reads</span> " + escape(source.substring(5).trim())
                            : escape(source))
                    .append("</td><td>").append(escape(product.row().location())).append("</td><td class=\"num\">")
                    .append(escape(product.row().forwardMismatches() + " / " + product.row().reverseMismatches()))
                    .append("</td><td class=\"num\">").append(escape(product.row().forwardThreePrime() + " / "
                            + product.row().reverseThreePrime()))
                    .append("</td><td class=\"num\" title=\"").append(escape(product.binding())).append("\">")
                    .append(product.partlyBound() ? "<span class=\"part\">" + PARTLY_BOUND + "</span> " : "")
                    .append(product.unaligned(true)).append(" / ").append(product.unaligned(false))
                    .append("</td><td>").append(escape(product.strand())).append("</td>");
            if (probes) {
                html.append("<td>").append(escape(product.row().probeId())).append("</td><td class=\"num\">")
                        .append(escape(product.probeMismatches())).append("</td>");
            }
            html.append("</tr>\n");
        }
        html.append("</tbody></table></div>\n</section>\n");
    }

    private static void readSection(StringBuilder html, List<Finding> findings) {
        List<Finding> shown = findings.stream()
                .filter(finding -> finding.status() == Status.CALLED_FROM_READS
                        || finding.status() == Status.CALLED_BY_EXTENSION
                        || finding.status() == Status.WEAK_READ_SUPPORT
                        || finding.status() == Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS
                        || finding.status() == Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT)
                .toList();
        long notInReads = findings.stream().filter(finding -> finding.status() == Status.NOT_IN_READS).count();
        long notClosed = findings.stream()
                .filter(finding -> finding.status() == Status.NOT_CLOSED_BY_EXTENSION).count();
        if (shown.isEmpty() && notInReads == 0 && notClosed == 0) {
            return;
        }
        html.append("<section>\n<h2>Read-level evidence (FASTQ input)</h2>\n<p class=\"hint\">Reads are baited and")
                .append(" micro-assembled; a product the assembly misses is called from the reads when enough of them")
                .append(" hold it, or, when no read is long enough to hold it, when reads extended from both of its")
                .append(" primers hold it and the read pairs span it throughout. A negative from reads is weaker")
                .append(" evidence than a negative on a genome assembly.</p>\n");
        if (!shown.isEmpty()) {
            html.append("<div class=\"scroll\"><table class=\"rows\">\n<thead><tr><th>Sample</th><th>Assay</th><th>Outcome")
                    .append("</th><th>What the reads show</th></tr></thead>\n<tbody>\n");
            for (Finding finding : shown) {
                boolean called = finding.status() == Status.CALLED_FROM_READS
                        || finding.status() == Status.CALLED_BY_EXTENSION;
                html.append("<tr><td>").append(escape(finding.sample())).append("</td><td>")
                        .append(escape(finding.assay())).append("</td><td>")
                        .append(called ? "<span class=\"tag\">reads</span> reported"
                                : "<span class=\"chip inconclusive\">?</span> not reported")
                        .append("</td><td class=\"wrap\">").append(escape(finding.readDetail())).append("</td></tr>\n");
            }
            html.append("</tbody></table></div>\n");
        }
        if (notInReads > 0) {
            html.append("<p class=\"hint\">").append(notInReads).append(notInReads == 1 ? " negative has" : " negatives have")
                    .append(" both primers in the reads but no complete amplicon in any read. That only rules out")
                    .append(" products short enough to fit in a read or merged pair; see the diagnostics TSV.</p>\n");
        }
        if (notClosed > 0) {
            html.append("<p class=\"hint\">").append(notClosed).append(notClosed == 1 ? " negative has" : " negatives have")
                    .append(" both primers in the reads, but no read holds a complete amplicon and extending the reads")
                    .append(" through the baited reads did not join the primers; see the diagnostics TSV.</p>\n");
        }
        html.append("</section>\n");
    }

    /** Assembly samples whose product could span a break in the assembly; nothing when there are none. */
    private static void splitSection(StringBuilder html, List<SplitAcrossContigs.Split> splits) {
        if (splits.isEmpty()) {
            return;
        }
        html.append("<section>\n<h2>Primers at contig ends (FASTA input)</h2>\n<p class=\"hint\">A product is")
                .append(" called on one contig only. For these assays no contig holds one, but the primers point off")
                .append(" the ends of two contigs, or off both ends of one: a product could span a break in the")
                .append(" assembly, or the end of a circular sequence. Neither positive nor negative: check with a more")
                .append(" complete assembly, or with the reads.</p>\n<div class=\"scroll\"><table class=\"rows\">\n")
                .append("<thead><tr><th>Sample</th><th>Assay</th><th>At least</th><th>Where</th></tr></thead>\n<tbody>\n");
        for (SplitAcrossContigs.Split split : splits) {
            html.append("<tr><td>").append(escape(split.sample())).append("</td><td>").append(escape(split.assay()))
                    .append("</td><td class=\"num\">").append(split.minimumSize()).append(" bp</td><td class=\"wrap\">")
                    .append(escape(split.note())).append("</td></tr>\n");
        }
        html.append("</tbody></table></div>\n</section>\n");
    }

    private static void detailSection(StringBuilder html, Map<String, String> runDetails, String reportName,
                                      boolean anyDeclared) {
        html.append("<section>\n<h2>Run details</h2>\n<dl class=\"details\">\n");
        runDetails.forEach((label, value) -> html.append("<dt>").append(escape(label)).append("</dt><dd>")
                .append(escape(value)).append("</dd>\n"));
        html.append("</dl>\n<p class=\"hint\">This page shows the rows of <code>").append(escape(reportName))
                .append("</code>, in the same folder. insilicoPCR reports every product a PCR would amplify, whatever")
                .append(" its size; ").append(anyDeclared
                        ? "the size check compares each product with the sizes declared in the primer file and removes nothing."
                        : "declare the product size an assay is designed to give in the primer file"
                        + " (e.g. <code>&gt;assay-F size=434</code>) to have products of that size marked.")
                .append("</p>\n</section>\n");
    }

    private static String key(String sample, String assay) {
        return sample + "\u0000" + assay;
    }

    static String escape(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                case '\n' -> escaped.append("&#10;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * The page's style and script, kept as resources next to this class, read the first time a
     * page needs them. A build missing one fails each write with an exception, which the command
     * line and the application report like any other failure; a later write tries again.
     */
    private static final Map<String, String> RESOURCES = new ConcurrentHashMap<>();

    private static String page(String name) {
        return RESOURCES.computeIfAbsent(name, HtmlReport::resource);
    }

    private static String resource(String name) {
        try (InputStream in = HtmlReport.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException(name + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
