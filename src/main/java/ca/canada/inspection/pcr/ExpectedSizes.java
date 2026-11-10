package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Product sizes declared for assays in the primer file, used to label report rows.
 *
 * <p>A primer FASTA header can carry, after the primer ID, the size of the product the
 * assay is designed to give ({@code size=434}) and the sizes of other products it is
 * known to give ({@code other=575,1249}). Every amplicon is reported whatever its
 * size; the declaration only adds a {@value #COLUMN} column saying which kind of
 * product a row is. Nothing is filtered.</p>
 */
public final class ExpectedSizes {

    /** No declaration: the report gets no {@value #COLUMN} column. */
    public static final ExpectedSizes NONE = new ExpectedSizes(Map.of());

    /** Report column holding the label. */
    public static final String COLUMN = "SizeCheck";

    /**
     * A single declared size matches amplicons this many bp either side of it: length
     * variants of one locus, as for read-level products
     * ({@link ReadLevelCaller#PRODUCT_SIZE_GAP}). A declared range matches exactly.
     */
    public static final int TOLERANCE = ReadLevelCaller.PRODUCT_SIZE_GAP;

    private static final String TARGET_KEY = "size";
    private static final String OTHER_KEY = "other";

    /** What a row's amplicon size is, for an assay that declares sizes. */
    public enum Label {
        /** The product the assay is designed to give. */
        TARGET("target"),
        /** Another product the assay is known to give. */
        OTHER("other"),
        /** A size the primer file does not declare. */
        UNEXPECTED("unexpected");

        private final String text;

        Label(String text) {
            this.text = text;
        }

        /** The value written in the report. */
        public String text() {
            return text;
        }
    }

    /** Inclusive size range in bp. */
    record Range(int min, int max) {
        boolean contains(int size) {
            return size >= min && size <= max;
        }
    }

    /** What one assay declares. */
    record Declared(List<Range> target, List<Range> other) {
    }

    private final Map<String, Declared> byAssay;

    private ExpectedSizes(Map<String, Declared> byAssay) {
        this.byAssay = Map.copyOf(byAssay);
    }

    /**
     * Reads the declarations from primer FASTA headers.
     *
     * @param descriptions header text following the primer ID, per primer ID as written
     *                     in the file ({@code assay-F}, {@code assay-R}, {@code assay-P});
     *                     words other than {@code size=} and {@code other=} are free text
     * @throws IllegalArgumentException for a value that is not a size, a range or a list of
     *                                  them, or an assay declaring one key with two values
     */
    public static ExpectedSizes fromHeaders(Map<String, String> descriptions) {
        // assay -> key -> the primer that declared it and its value as written
        Map<String, Map<String, String[]>> declared = new TreeMap<>();
        descriptions.forEach((primerId, description) -> {
            int lastDash = primerId.lastIndexOf('-');
            String assay = lastDash > 0 ? primerId.substring(0, lastDash) : primerId;
            for (String word : description.trim().split("\\s+")) {
                int equals = word.indexOf('=');
                String key = equals < 0 ? "" : word.substring(0, equals).toLowerCase(Locale.ROOT);
                if (!key.equals(TARGET_KEY) && !key.equals(OTHER_KEY)) {
                    continue;
                }
                String value = word.substring(equals + 1);
                parse(primerId, word, value); // fail on the primer that holds the bad value
                String[] previous = declared.computeIfAbsent(assay, ignored -> new HashMap<>())
                        .putIfAbsent(key, new String[]{primerId, value});
                if (previous != null && !parse(primerId, word, value).equals(parse(previous[0], word, previous[1]))) {
                    throw new IllegalArgumentException("Assay " + assay + " declares " + key + "= twice with different"
                            + " values: " + previous[1] + " on " + previous[0] + ", " + value + " on " + primerId);
                }
            }
        });

        Map<String, Declared> byAssay = new HashMap<>();
        declared.forEach((assay, keys) -> byAssay.put(assay, new Declared(
                ranges(keys.get(TARGET_KEY)), ranges(keys.get(OTHER_KEY)))));
        return byAssay.isEmpty() ? NONE : new ExpectedSizes(byAssay);
    }

    private static List<Range> ranges(String[] declaration) {
        return declaration == null ? List.of() : parse(declaration[0], "", declaration[1]);
    }

    /** Parses {@code 434}, {@code 420-450} or a comma-separated list of either. */
    private static List<Range> parse(String primerId, String word, String value) {
        List<Range> ranges = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            String[] bounds = item.split("-", -1);
            try {
                if (bounds.length == 1) {
                    int size = Integer.parseInt(bounds[0]);
                    requirePositive(size);
                    ranges.add(new Range(Math.max(1, size - TOLERANCE), size + TOLERANCE));
                } else if (bounds.length == 2) {
                    Range range = new Range(Integer.parseInt(bounds[0]), Integer.parseInt(bounds[1]));
                    requirePositive(range.min());
                    if (range.min() > range.max()) {
                        throw new NumberFormatException();
                    }
                    ranges.add(range);
                } else {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Primer " + primerId + ": cannot read the product size '" + item
                        + "' in '" + word + "'. Write a size in bp (size=434), a range (size=420-450) or a list"
                        + " (size=159,163).");
            }
        }
        return List.copyOf(ranges);
    }

    private static void requirePositive(int size) {
        if (size < 1) {
            throw new NumberFormatException();
        }
    }

    /** Whether no assay declares a size. */
    public boolean isEmpty() {
        return byAssay.isEmpty();
    }

    /** Assays that declare at least one size. */
    public Set<String> assays() {
        return byAssay.keySet();
    }

    /**
     * @return what an amplicon of {@code size} bp is for {@code assay}, or {@code null}
     *         when the assay declares no size
     */
    public Label label(String assay, int size) {
        Declared declared = byAssay.get(assay);
        if (declared == null) {
            return null;
        }
        if (declared.target().stream().anyMatch(range -> range.contains(size))) {
            return Label.TARGET;
        }
        return declared.other().stream().anyMatch(range -> range.contains(size)) ? Label.OTHER : Label.UNEXPECTED;
    }

    /** The report value for {@link #label}: empty for an assay that declares no size. */
    public String labelText(String assay, int size) {
        Label label = label(assay, size);
        return label == null ? "" : label.text();
    }

    /** One line per declaring assay, for logs: {@code BMros: target 424-444 bp; other 565-585 bp}. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        for (String assay : new TreeMap<>(byAssay).keySet()) {
            lines.add(assay + ": " + describe(assay));
        }
        return lines;
    }

    /**
     * What {@code assay} declares, e.g. {@code target 424-444 bp; other 565-585 bp};
     * empty when it declares nothing.
     */
    public String describe(String assay) {
        Declared declared = byAssay.get(assay);
        if (declared == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (!declared.target().isEmpty()) {
            parts.add("target " + describe(declared.target()) + " bp");
        }
        if (!declared.other().isEmpty()) {
            parts.add("other " + describe(declared.other()) + " bp");
        }
        return String.join("; ", parts);
    }

    /** The smallest size that would be labelled {@code target} for {@code assay}, if it declares one. */
    public OptionalInt smallestTarget(String assay) {
        Declared declared = byAssay.get(assay);
        return declared == null ? OptionalInt.empty() : declared.target().stream().mapToInt(Range::min).min();
    }

    /** Lines for QAlog.txt; empty when no assay declares a size. */
    public List<String> qaLogLines() {
        if (isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("Product sizes declared in the primer file (" + COLUMN + " column of the report; every product is"
                + " reported whatever its size):");
        summaryLines().forEach(line -> lines.add("  " + line));
        return lines;
    }

    private static String describe(List<Range> ranges) {
        return ranges.stream().map(range -> range.min() + "-" + range.max()).collect(Collectors.joining(", "));
    }
}
