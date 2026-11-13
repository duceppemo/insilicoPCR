package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ConsolidatedReport.PrimerGroup;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Calls products too long for a read or merged pair (FASTQ mode).
 *
 * <p>The mini-assembly breaks inside a high-copy, variable locus such as rDNA, and a
 * read-level call needs one read or merged pair to hold the whole product. For an
 * assay that neither reported, although reads carry both of its primers, the reads
 * carrying a primer are used as seeds and extended through the k-mers of all the
 * baited reads, each time along the better-supported branch (the pipeline runs
 * Tadpole for this). The extended reads are then searched like contigs, with the
 * same hit filters and amplicon/probe rules.</p>
 *
 * <p>An extension that picks the wrong branch gives a product that the opposite
 * primer's reads, extended the other way, often do not give. A product must therefore
 * be closed by reads seeded by the forward primer and by reads seeded by the reverse
 * primer ({@link #MIN_CLOSING_READS_PER_SIDE}). That is not enough where two loci share
 * a repeat, through which both sides take the same wrong turn: a closed product is
 * reported only when the sample's read pairs support it over its whole length
 * ({@link PairSupport}).</p>
 *
 * <p>An extended read is the best-supported path through a locus that can exist in
 * many slightly different copies: its size is what is reported, and its sequence is
 * not an assembled consensus.</p>
 */
public final class ReadExtensionCaller {

    /** Reads used as seeds per primer of an assay (all expanded variants of a degenerate primer together). */
    public static final int MAX_SEEDS_PER_PRIMER = 150;

    /** Extended reads seeded by each primer that must hold a product for it to be called. */
    public static final int MIN_CLOSING_READS_PER_SIDE = 2;

    /** k-mer length of the extension. */
    public static final int K = 31;

    /** How far a seed is extended each way when the run sets no maximum amplicon length. */
    public static final int DEFAULT_REACH = AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH;

    /** Start of the {@code Contig Description} of a row called this way. */
    public static final String ROW_PREFIX = "FASTQ read-extension call";

    /** Which assays reads are extended for (command line: {@code --read-extension}). */
    public enum Mode {
        /** No read is extended. */
        OFF("off", "off"),
        /** Assays that nothing reports in the sample: no contig and no read holds a product. */
        UNREPORTED("unreported", "assays in no contig and in no read"),
        /**
         * As {@link #UNREPORTED}, and assays that declare a target size ({@code size=}) of
         * which the sample shows no product yet, whatever other products it shows.
         */
        TARGET("target", "assays in no contig and in no read, and assays without a product of their declared"
                + " target size");

        /** The mode used when none is asked for. */
        public static final Mode DEFAULT = UNREPORTED;

        private final String text;
        private final String description;

        Mode(String text, String description) {
            this.text = text;
            this.description = description;
        }

        /** The value on the command line. */
        public String text() {
            return text;
        }

        /** Which assays are extended, for logs and reports. */
        public String description() {
            return description;
        }

        /** @throws IllegalArgumentException naming the accepted values */
        public static Mode parse(String value) {
            for (Mode mode : values()) {
                if (mode.text.equalsIgnoreCase(value == null ? "" : value.trim())) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("read extension must be off, unreported or target, got: " + value);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /**
     * A read used as a seed.
     *
     * @param id    ID of the seed and of the extended read made from it
     * @param read  ID of the baited read
     * @param assay assay whose primer the read carries
     * @param role  {@code F} or {@code R}: which of the assay's primers
     */
    public record Seed(String id, String read, String assay, char role) {
    }

    /**
     * One product of an assay on the extended reads.
     *
     * @param size           the most frequent size among the extended reads holding the product
     * @param sizes          every size observed
     * @param forwardClosing extended reads seeded by the forward primer that hold the product
     * @param reverseClosing extended reads seeded by the reverse primer that hold the product
     * @param row            report row on a representative extended read of the reported size
     * @param support        what the sample's read pairs say about the product ({@link PairSupport})
     */
    public record Product(String assay, int size, Set<Integer> sizes, int forwardClosing, int forwardSeeds,
                          int reverseClosing, int reverseSeeds, List<String> row, PairSupport.Verdict support) {
        public Product {
            sizes = Collections.unmodifiableSortedSet(new TreeSet<>(sizes));
        }

        /** A product not yet checked against the read pairs. */
        public Product(String assay, int size, Set<Integer> sizes, int forwardClosing, int forwardSeeds,
                       int reverseClosing, int reverseSeeds, List<String> row) {
            this(assay, size, sizes, forwardClosing, forwardSeeds, reverseClosing, reverseSeeds, row,
                    PairSupport.Verdict.NOT_CHECKED);
        }

        /** Whether reads seeded from both primers hold the product. */
        public boolean isClosed() {
            return forwardClosing >= MIN_CLOSING_READS_PER_SIDE && reverseClosing >= MIN_CLOSING_READS_PER_SIDE;
        }

        /**
         * Whether the product is reported: reads seeded from both primers hold it, and the
         * sample's read pairs support it over its whole length.
         */
        public boolean isReportable() {
            return isClosed() && support.passes();
        }

        /** This product with the verdict of the read pairs. */
        public Product withSupport(PairSupport.Verdict verdict) {
            return new Product(assay, size, sizes, forwardClosing, forwardSeeds, reverseClosing, reverseSeeds, row,
                    verdict);
        }

        /** E.g. {@code 142 of 150 forward-seeded and 145 of 150 reverse-seeded reads}. */
        public String supportSummary() {
            return forwardClosing + " of " + forwardSeeds + " forward-seeded and " + reverseClosing + " of "
                    + reverseSeeds + " reverse-seeded reads";
        }

        /** The observed sizes, e.g. {@code 1072,1074}. */
        public String sizeSummary() {
            return sizes.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
    }

    /**
     * What extending the reads showed for one assay.
     *
     * @param forwardSeeds reads carrying the forward primer that were extended
     * @param reverseSeeds reads carrying the reverse primer that were extended
     * @param reach        bases each seed was extended by at most, each way
     * @param products     products found on the extended reads, reportable or not, by increasing size
     * @param reported     sizes the sample already shows for the assay, on contigs or in reads
     *                     ({@link #knownSizes})
     */
    public record Outcome(String assay, int forwardSeeds, int reverseSeeds, int reach, List<Product> products,
                          Set<Integer> reported) {
        public Outcome {
            products = List.copyOf(products);
            reported = Set.copyOf(reported);
        }

        /** An outcome for an assay the sample does not report yet. */
        public Outcome(String assay, int forwardSeeds, int reverseSeeds, int reach, List<Product> products) {
            this(assay, forwardSeeds, reverseSeeds, reach, products, Set.of());
        }

        /** The products that reads extended from both primers hold. */
        public List<Product> closed() {
            return products.stream().filter(Product::isClosed).toList();
        }

        /** The closed products that the sample's read pairs support. */
        public List<Product> called() {
            return products.stream().filter(Product::isReportable).toList();
        }

        /** The closed products that are not reported, for want of read-pair support. */
        public List<Product> withheld() {
            return products.stream().filter(product -> product.isClosed() && !product.isReportable()).toList();
        }

        /** This outcome with each closed product checked against the read pairs. */
        public Outcome withSupport(java.util.function.Function<Product, PairSupport.Verdict> check) {
            return new Outcome(assay, forwardSeeds, reverseSeeds, reach, products.stream()
                    .map(product -> product.isClosed() ? product.withSupport(check.apply(product)) : product)
                    .toList(), reported);
        }

        /**
         * The products to add to the report: those called that the sample does not report
         * already, give or take a length variant ({@link ReadLevelCaller#PRODUCT_SIZE_GAP}).
         */
        public List<Product> added() {
            return called().stream().filter(product -> !isReported(product)).toList();
        }

        /** Whether the sample already shows the product, give or take a length variant. */
        public boolean isReported(Product product) {
            for (int size : product.sizes()) {
                for (int known : reported) {
                    if (Math.abs(size - known) <= ReadLevelCaller.PRODUCT_SIZE_GAP) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private ReadExtensionCaller() {
    }

    /**
     * The assays to extend reads for. Whatever the mode, an assay needs a forward and a
     * reverse primer, each carried by a read.
     *
     * <p>{@link Mode#UNREPORTED}: the assays that the mini-assembly does not report and of
     * which no read holds a complete amplicon. An assay with a complete amplicon in some
     * reads is left to the read-level rules, including when too few reads hold it to
     * report it.</p>
     *
     * <p>{@link Mode#TARGET}: those, and the assays that declare a target size of which the
     * sample shows no product, on a contig or in a read, whatever other products it shows.
     * A product of the target size seen in too few reads to be reported also keeps the
     * assay out: that call stays with the read-level rules.</p>
     *
     * @param sample FASTQ sample with its accepted contig hits attached
     * @param reads  read-level evidence for the sample
     * @param sizes  product sizes declared in the primer file
     */
    public static List<String> candidates(Sample sample, Map<String, String> primers, AmpliconOptions options,
                                          ReadEvidence reads, Mode mode, ExpectedSizes sizes) {
        if (mode == Mode.OFF) {
            return List.of();
        }
        Map<String, Set<Integer>> known = knownSizes(sample, primers, options, reads);
        List<String> assays = new ArrayList<>();
        ConsolidatedReport.groupByAssay(primers.keySet()).forEach((assay, group) -> {
            if (group.forward.isEmpty() || group.reverse.isEmpty()
                    || reads.readsCarrying(group.forward) == 0 || reads.readsCarrying(group.reverse) == 0) {
                return;
            }
            Set<Integer> seen = known.getOrDefault(assay, Set.of());
            boolean unreported = seen.isEmpty();
            boolean targetMissing = mode == Mode.TARGET && sizes.smallestTarget(assay).isPresent()
                    && seen.stream().noneMatch(size -> sizes.label(assay, size) == ExpectedSizes.Label.TARGET);
            if (unreported || targetMissing) {
                assays.add(assay);
            }
        });
        return assays;
    }

    /**
     * Per assay, the amplicon sizes the sample already shows: on contigs, and in reads,
     * including a product in too few reads to be reported. An assay showing none is absent.
     * A product called by extension at one of these sizes is not added: it is reported
     * already, or its fate was decided by the read-level rules.
     */
    public static Map<String, Set<Integer>> knownSizes(Sample sample, Map<String, String> primers,
                                                       AmpliconOptions options, ReadEvidence reads) {
        Map<String, Set<Integer>> known = new TreeMap<>();
        ConsolidatedReport.reportedSizes(sample, primers, options).forEach((assay, sizes) ->
                known.computeIfAbsent(assay, ignored -> new TreeSet<>()).addAll(sizes));
        reads.calls().forEach((assay, products) -> products.forEach(product ->
                known.computeIfAbsent(assay, ignored -> new TreeSet<>()).addAll(product.sizes())));
        return known;
    }

    /** As the full {@code candidates} in the default mode, without declared sizes. */
    public static List<String> candidates(Sample sample, Map<String, String> primers, AmpliconOptions options,
                                          ReadEvidence reads) {
        return candidates(sample, primers, options, reads, Mode.DEFAULT, ExpectedSizes.NONE);
    }

    /**
     * Picks the seeds: for each assay and each of its two primers, up to
     * {@link #MAX_SEEDS_PER_PRIMER} of the reads carrying it, spread evenly over the
     * reads in their order in the input rather than taken from its start.
     *
     * @param assays  assays to seed, as given by {@link #candidates}
     * @param primers expanded primer IDs of the run
     */
    public static List<Seed> seeds(Collection<String> assays, Collection<String> primers, ReadEvidence reads) {
        Map<String, PrimerGroup> groups = ConsolidatedReport.groupByAssay(primers);
        List<Seed> seeds = new ArrayList<>();
        for (String assay : new TreeSet<>(assays)) {
            PrimerGroup group = groups.get(assay);
            if (group == null) {
                continue;
            }
            for (char role : new char[]{'F', 'R'}) {
                for (String read : spread(carrying(reads, role == 'F' ? group.forward : group.reverse),
                        MAX_SEEDS_PER_PRIMER)) {
                    seeds.add(new Seed("extended_read_" + seeds.size(), read, assay, role));
                }
            }
        }
        return seeds;
    }

    private static Set<String> carrying(ReadEvidence reads, List<String> oligoIds) {
        Set<String> ids = new HashSet<>();
        for (String oligo : oligoIds) {
            ids.addAll(reads.readsWithOligo().getOrDefault(oligo, Set.of()));
        }
        return ids;
    }

    /** At most {@code max} of {@code ids}, evenly spaced in read order. */
    static List<String> spread(Collection<String> ids, int max) {
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(Comparator.comparingLong(ReadExtensionCaller::readNumber).thenComparing(Comparator.naturalOrder()));
        if (sorted.size() <= max) {
            return sorted;
        }
        List<String> chosen = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            chosen.add(sorted.get((int) ((long) i * sorted.size() / max)));
        }
        return chosen;
    }

    /** The number ending a read ID such as {@code read_1203}; IDs without one sort last. */
    private static long readNumber(String id) {
        int underscore = id.lastIndexOf('_');
        try {
            return Long.parseLong(id.substring(underscore + 1));
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Calls products on the extended reads.
     *
     * @param extended pseudo-sample whose "contigs" are the extended reads, named by
     *                 {@link Seed#id}, with their accepted primer hits attached
     * @param lengths  extended read lengths by ID
     * @param seeds    the seeds the reads were extended from
     * @param reach    bases each seed was extended by at most, each way
     * @return one outcome per seeded assay, in assay order
     */
    public static Map<String, Outcome> evaluate(Sample extended, Map<String, Integer> lengths, List<Seed> seeds,
                                                Map<String, String> primers, AmpliconOptions options, int reach) {
        return evaluate(extended, lengths, seeds, primers, options, reach, Map.of());
    }

    /**
     * As {@link #evaluate(Sample, Map, List, Map, AmpliconOptions, int)}, knowing what the
     * sample already reports.
     *
     * @param reported per assay, the amplicon sizes the sample already shows
     *                 ({@link #knownSizes}); a product called at one of them is not added
     */
    public static Map<String, Outcome> evaluate(Sample extended, Map<String, Integer> lengths, List<Seed> seeds,
                                                Map<String, String> primers, AmpliconOptions options, int reach,
                                                Map<String, Set<Integer>> reported) {
        Map<String, Seed> seedById = new HashMap<>();
        Map<String, int[]> seedCounts = new TreeMap<>();
        for (Seed seed : seeds) {
            seedById.put(seed.id(), seed);
            seedCounts.computeIfAbsent(seed.assay(), ignored -> new int[2])[seed.role() == 'F' ? 0 : 1]++;
        }

        // A product must lie wholly on the extended read, primers included, as on a read.
        Sample contained = ReadLevelCaller.withContainedHits(extended, lengths);
        Map<String, List<List<String>>> rowsByAssay = new HashMap<>();
        for (List<String> row : ConsolidatedReport.rows(contained, primers, options)) {
            Seed seed = seedById.get(row.get(ConsolidatedReport.CONTIG_COLUMN));
            String assay = row.get(ConsolidatedReport.GENE_COLUMN);
            // Only the assay the read was seeded for: a seed can wander through another assay's locus.
            if (seed != null && seed.assay().equals(assay)) {
                rowsByAssay.computeIfAbsent(assay, ignored -> new ArrayList<>()).add(row);
            }
        }

        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        seedCounts.forEach((assay, counts) -> {
            List<Product> products = new ArrayList<>();
            for (List<List<String>> rows : ReadLevelCaller.splitBySize(rowsByAssay.getOrDefault(assay, List.of()))) {
                products.add(product(assay, rows, seedById, counts[0], counts[1]));
            }
            outcomes.put(assay, new Outcome(assay, counts[0], counts[1], reach, products,
                    reported.getOrDefault(assay, Set.of())));
        });
        return outcomes;
    }

    private static Product product(String assay, List<List<String>> rows, Map<String, Seed> seedById,
                                   int forwardSeeds, int reverseSeeds) {
        Set<String> forward = new HashSet<>();
        Set<String> reverse = new HashSet<>();
        // Each extended read counts once per size it holds the product at.
        Map<Integer, Set<String>> readsBySize = new TreeMap<>();
        for (List<String> row : rows) {
            String read = row.get(ConsolidatedReport.CONTIG_COLUMN);
            (seedById.get(read).role() == 'F' ? forward : reverse).add(read);
            readsBySize.computeIfAbsent(size(row), ignored -> new HashSet<>()).add(read);
        }
        int size = readsBySize.entrySet().stream()
                .max(Comparator.<Map.Entry<Integer, Set<String>>>comparingInt(entry -> entry.getValue().size())
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .orElseThrow().getKey();
        // Representative: of the reported size, fewest primer mismatches, then a stable read order.
        List<String> best = rows.stream()
                .filter(row -> size(row) == size)
                .min(Comparator.<List<String>>comparingInt(row ->
                                Integer.parseInt(row.get(ConsolidatedReport.FORWARD_MISMATCHES_COLUMN))
                                        + Integer.parseInt(row.get(ConsolidatedReport.REVERSE_MISMATCHES_COLUMN)))
                        .thenComparingLong(row -> readNumber(row.get(ConsolidatedReport.CONTIG_COLUMN)))
                        .thenComparing(row -> row.get(ConsolidatedReport.LOCATION_COLUMN)))
                .orElseThrow();
        return new Product(assay, size, readsBySize.keySet(), forward.size(), forwardSeeds, reverse.size(),
                reverseSeeds, best);
    }

    private static int size(List<String> row) {
        return Integer.parseInt(row.get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));
    }

    /**
     * The report row for a product called by extension: the representative extended
     * read's row, with the description saying where the call comes from.
     *
     * @param seedDescription original header of the read the representative was extended from; may be empty
     */
    public static List<String> reportRow(Product product, String seedDescription) {
        List<String> row = new ArrayList<>(product.row());
        StringBuilder description = new StringBuilder(ROW_PREFIX).append(": no read holds the product; ")
                .append(product.supportSummary()).append(", extended through the baited reads (k=").append(K)
                .append("), hold it");
        if (product.sizes().size() > 1) {
            description.append(" at ").append(product.sizeSummary()).append(" bp");
        }
        if (product.support().kind() != PairSupport.Kind.NOT_CHECKED) {
            description.append("; ").append(product.support().summary());
        }
        description.append("; the extended read is the best-supported path, not an assembled consensus");
        if (seedDescription != null && !seedDescription.isEmpty()) {
            description.append("; representative extended from read ").append(seedDescription);
        }
        row.set(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN, description.toString());
        return row;
    }
}
