package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Calls amplicons directly on baited reads (FASTQ mode).
 *
 * <p>The mini-assembly can omit a target region or break inside an amplicon
 * (#7), yet single reads, or merged read pairs, are often long enough to hold
 * a complete qPCR amplicon. Each read is treated as a tiny contig and run through
 * the same hit filters and amplicon/probe rules as the assembly
 * ({@link Products#of}), so a read-level call means exactly what a
 * contig-level call means.</p>
 *
 * <p>An assay can give more than one product in a sample (the target, and another
 * place where both primers happen to land). Each product is called on its own
 * support, like bands on a gel: see {@link ProductSizes#GAP}.</p>
 */
public final class ReadLevelCaller {

    /**
     * Default minimum share of informative reads that must hold the amplicon for a
     * read-level call (command line: {@code --read-min-fraction}). A real target shows in most reads that carry a primer and are long enough,
     * though not all: a sequencing error in a primer or probe disqualifies a read under a
     * strict mismatch budget (68% on a 2x250 MiSeq run in #7). A few contaminating or
     * index-hopped reads show in few of them (18% in the same issue).
     */
    public static final double DEFAULT_MIN_SUPPORT_FRACTION = 0.3;

    /** Minimum reads holding the amplicon for a read-level call. */
    public static final int MIN_SUPPORTING_READS = 2;

    /** How the {@code Contig Description} of a read-level call starts. */
    public static final String ROW_PREFIX = "FASTQ read-level call";


    /**
     * The read-level products to report next to the assembly's own rows: those with
     * enough support that the assembly does not already report.
     *
     * @param products       an assay's products seen in reads
     * @param assembledSizes amplicon sizes the assembly reports for the assay; {@code null}
     *                       or empty when it reports none
     */
    public static List<ReadCall> missingFromAssembly(List<ReadCall> products, Set<Integer> assembledSizes) {
        return products.stream()
                .filter(ReadCall::isReportable)
                .filter(product -> assembledSizes == null || !product.matchesAny(assembledSizes))
                .toList();
    }

    /**
     * The report rows of {@code sample}'s products that its reads hold and its mini-assembly
     * does not report, by assay. A product the assembly reports is not repeated, but an assay
     * the assembly reports at another size only (both primers landing elsewhere) still gets
     * its read-level product.
     *
     * @param sample FASTQ sample with its accepted contig hits attached
     */
    public static List<ReportRow> reportRows(Sample sample, PrimerPanel primers, AmpliconOptions options,
                                             ReadEvidence reads) {
        Map<String, Set<Integer>> assembled = Products.sizes(sample, primers, options);
        List<ReportRow> rows = new ArrayList<>();
        new TreeMap<>(reads.calls()).forEach((assay, products) ->
                missingFromAssembly(products, assembled.get(assay)).stream()
                        .map(ReadLevelCaller::reportRow)
                        .forEach(rows::add));
        return rows;
    }

    /**
     * Bases next to a primer's 3' end, inside the product, compared to tell whether a
     * read carrying the primer comes from the product's locus.
     */
    static final int LOCUS_WINDOW = 30;

    /** Below this many comparable bases a read's locus cannot be judged; it counts as informative. */
    static final int MIN_LOCUS_WINDOW = 10;

    /**
     * Share of the compared bases that may differ (edit distance) from a supporting read
     * for a read to count as coming from the same locus. Generous on purpose: the reads
     * of a related organism at the homologous locus must still count, since they are
     * what tells a minor contaminant from the sample itself; a primer matching an
     * unrelated place in the genome differs in about half of the bases.
     */
    static final double MAX_LOCUS_DIVERGENCE = 0.2;

    /** Most frequent distinct flanks of the supporting reads that a read is compared to. */
    private static final int MAX_REFERENCE_WINDOWS = 20;

    /** Looks up the sequences of baited reads by read ID. */
    @FunctionalInterface
    public interface ReadSequences {
        /** No sequences: every read carrying a primer counts as coming from the product's locus. */
        ReadSequences NONE = readIds -> Map.of();

        /** @return sequence per read ID; reads that cannot be found are left out */
        Map<String, String> fetch(Set<String> readIds);
    }

    /**
     * One product of an assay, as the reads show it.
     *
     * @param supportingReads  distinct reads (mates of one unmerged pair count once) holding a
     *                         complete, reportable amplicon
     * @param informativeReads distinct reads (likewise) that would have shown the product had
     *                         every one of them carried it: they carry the assay's forward or
     *                         reverse primer at the product's locus and extend far enough to
     *                         hold the amplicon. The supporting reads are among them.
     * @param elsewhereReads   distinct reads (likewise) long enough too, but carrying the primer
     *                         at another place in the genome; they say nothing about this product
     * @param row              report row on the best-matching representative read
     * @param sizes            amplicon sizes observed across the supporting reads
     * @param minSupportFraction share of informative reads required to report the call
     */
    public record ReadCall(String assay, int supportingReads, int informativeReads, int elsewhereReads,
                           ReportRow row, Set<Integer> sizes, double minSupportFraction) {
        public ReadCall {
            sizes = Collections.unmodifiableSortedSet(new TreeSet<>(sizes));
        }

        public double supportFraction() {
            return informativeReads == 0 ? 0.0 : (double) supportingReads / informativeReads;
        }

        /** Whether the support is strong enough to report the product from reads. */
        public boolean isReportable() {
            return supportingReads >= MIN_SUPPORTING_READS && supportFraction() >= minSupportFraction;
        }

        /** Whether an amplicon of {@code size} bp is this product, give or take a length variant. */
        public boolean matches(int size) {
            return ProductSizes.sameProduct(sizes, size);
        }

        /** Whether any of {@code otherSizes} is this product. */
        public boolean matchesAny(Collection<Integer> otherSizes) {
            return otherSizes.stream().anyMatch(this::matches);
        }

        /** The reporting threshold, for messages. */
        public String thresholdSummary() {
            return "at least " + MIN_SUPPORTING_READS + " reads or read pairs and " + percent(minSupportFraction)
                    + " of informative reads";
        }

        public String supportSummary() {
            return supportingReads + " of " + informativeReads + " informative read(s) ("
                    + percent(supportFraction()) + ")";
        }

        /** The observed sizes, e.g. {@code 131,132}. */
        public String sizeSummary() {
            return sizes.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
    }

    /**
     * What the baited reads say about each assay.
     *
     * @param readsWithOligo   IDs of the reads holding the whole oligo with an accepted hit,
     *                         per expanded oligo ID (every oligo a read carries is counted)
     * @param calls            per assay with at least one read holding a complete amplicon,
     *                         its products by increasing size
     * @param checkableLength  per assay, the longest amplicon a read carrying one of
     *                         its primers could have held: from the primer's 5' end to
     *                         the read end in the primer's extension direction
     */
    public record ReadEvidence(Map<String, Set<String>> readsWithOligo, Map<String, List<ReadCall>> calls,
                               Map<String, Integer> checkableLength) {
        public ReadEvidence {
            Map<String, Set<String>> copy = new HashMap<>();
            readsWithOligo.forEach((oligo, ids) -> copy.put(oligo, Set.copyOf(ids)));
            readsWithOligo = Map.copyOf(copy);
            Map<String, List<ReadCall>> callsCopy = new HashMap<>();
            calls.forEach((assay, products) -> callsCopy.put(assay, List.copyOf(products)));
            calls = Map.copyOf(callsCopy);
            checkableLength = Map.copyOf(checkableLength);
        }

        /** The products of {@code assay} seen in reads; empty when no read holds a complete amplicon. */
        public List<ReadCall> products(String assay) {
            return calls.getOrDefault(assay, List.of());
        }

        /**
         * Distinct reads carrying any of {@code oligoIds}: a read matching two expanded
         * variants of a degenerate primer is counted once.
         */
        public long readsCarrying(Collection<String> oligoIds) {
            Set<String> reads = new HashSet<>();
            for (String oligo : oligoIds) {
                reads.addAll(readsWithOligo.getOrDefault(oligo, Set.of()));
            }
            return reads.size();
        }
    }

    private ReadLevelCaller() {
    }

    /**
     * @param fragments          read ID to fragment ID for unmerged mates of one pair, which
     *                           then count once as supporting or informative evidence; reads
     *                           not listed are their own fragment
     * @param minSupportFraction share of informative fragments (0 to 1) that must hold the
     *                           amplicon for the call to be reported
     * @param sequences          read sequences, used to tell reads at a product's locus from
     *                           reads carrying a primer elsewhere in the genome
     */
    public static ReadEvidence evaluate(String sampleName, Hits searched, Map<String, Integer> readLengths,
                                        Map<String, String> fragments, PrimerPanel primers,
                                        AmpliconOptions options, double minSupportFraction,
                                        ReadSequences sequences) {
        requireFraction(minSupportFraction);
        Hits reads = withContainedHits(searched, readLengths);
        Map<String, List<BlastResult>> hits = reads.byOligo();

        Map<String, Set<String>> readsWithOligo = new HashMap<>();
        hits.forEach((oligo, list) -> {
            Set<String> ids = new HashSet<>();
            list.forEach(hit -> ids.add(hit.queryID()));
            readsWithOligo.put(oligo, ids);
        });

        Map<String, List<ReportRow>> rowsByAssay = new TreeMap<>();
        for (ReportRow row : Products.of(sampleName, reads, primers, options)) {
            rowsByAssay.computeIfAbsent(row.assay(), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, PrimerId.Assay> groups = primers.assays();

        // Only reads carrying a primer of an assay with a product are compared by sequence.
        Set<String> wanted = new HashSet<>();
        for (String assay : rowsByAssay.keySet()) {
            pcrPrimerHits(reads, groups.get(assay)).forEach(hit -> wanted.add(hit.queryID()));
        }
        Map<String, String> readSequences = wanted.isEmpty() ? Map.of() : sequences.fetch(wanted);

        Map<String, List<ReadCall>> calls = new LinkedHashMap<>();
        rowsByAssay.forEach((assay, rows) -> {
            Evidence evidence = new Evidence(pcrPrimerHits(reads, groups.get(assay)), readLengths, fragments,
                    readSequences, minSupportFraction);
            List<ReadCall> products = new ArrayList<>();
            for (List<ReportRow> productRows : ProductSizes.splitBySize(rows)) {
                products.add(call(assay, productRows, evidence));
            }
            calls.put(assay, products);
        });

        Map<String, Integer> checkable = new HashMap<>();
        for (Map.Entry<String, PrimerId.Assay> entry : groups.entrySet()) {
            int longest = 0;
            for (BlastResult hit : pcrPrimerHits(reads, entry.getValue())) {
                longest = Math.max(longest, containableLength(hit, readLengths.get(hit.queryID())));
            }
            if (longest > 0) {
                checkable.put(entry.getKey(), longest);
            }
        }
        return new ReadEvidence(readsWithOligo, calls, checkable);
    }

    /** Everything about one assay's reads that calling a product needs. */
    private record Evidence(List<BlastResult> primerHits, Map<String, Integer> readLengths,
                            Map<String, String> fragments, Map<String, String> sequences,
                            double minSupportFraction) {
        String fragment(String read) {
            return fragments.getOrDefault(read, read);
        }
    }


    /**
     * Keeps only hits whose full primer lies inside the read. The hit filters tolerate a
     * few unaligned 5' primer bases (a 5'-proximal mismatch makes BLAST trim them); on a
     * read, those bases are more often simply beyond the read end, and the amplicon would
     * then be clipped and undersized. A read-level call must hold the whole product.
     */
    static Hits withContainedHits(Hits reads, Map<String, Integer> readLengths) {
        return reads.filtered(hit -> {
            Integer length = readLengths.get(hit.queryID());
            return length != null && primerStart(hit) >= 1 && primerEnd(hit) <= length;
        });
    }

    /** 1-based read position of the full primer's leftmost base. */
    private static int primerStart(BlastResult hit) {
        int leftUnaligned = hit.isPlusStrand() ? hit.fivePrimeUncovered() : hit.threePrimeUncovered();
        return hit.qstart() - leftUnaligned;
    }

    /** 1-based read position of the full primer's rightmost base. */
    private static int primerEnd(BlastResult hit) {
        int rightUnaligned = hit.isPlusStrand() ? hit.threePrimeUncovered() : hit.fivePrimeUncovered();
        return hit.qend() + rightUnaligned;
    }

    /** The hits of an assay's forward and reverse primers (not its probe). */
    private static List<BlastResult> pcrPrimerHits(Hits reads, PrimerId.Assay group) {
        if (group == null) {
            return List.of();
        }
        List<String> primers = new ArrayList<>(group.forward());
        primers.addAll(group.reverse());
        return reads.of(primers);
    }

    /** Rejects a support fraction outside 0 to 1. */
    public static double requireFraction(double fraction) {
        if (!(fraction >= 0.0 && fraction <= 1.0)) {
            throw new IllegalArgumentException("read support fraction must be between 0 and 1, got: " + fraction);
        }
        return fraction;
    }

    static String percent(double fraction) {
        return Math.round(100 * fraction) + "%";
    }

    private static ReadCall call(String assay, List<ReportRow> rows, Evidence evidence) {
        // Evidence is counted per fragment: two unmerged mates of one pair are one molecule.
        Set<String> supporting = new HashSet<>();
        Set<Integer> sizes = new TreeSet<>();
        Map<String, int[]> spans = new HashMap<>();
        for (ReportRow row : rows) {
            String read = row.contig();
            supporting.add(evidence.fragment(read));
            sizes.add(row.size());
            spans.putIfAbsent(read, new int[]{row.start(), row.end()});
        }
        int shortest = sizes.iterator().next();
        Map<PrimerId.Role, List<String>> flanks = productFlanks(spans, evidence, shortest);

        // Fragments that would have shown the product had they carried it: a read carries a
        // primer at the product's locus and reaches far enough from the primer's 5' end.
        // A read carrying the primer somewhere else in the genome says nothing about the
        // product, however many such places the primer matches. Supporting fragments are
        // informative by definition.
        Set<String> informative = new HashSet<>(supporting);
        Set<String> elsewhere = new HashSet<>();
        for (BlastResult hit : evidence.primerHits()) {
            String fragment = evidence.fragment(hit.queryID());
            if (informative.contains(fragment)
                    || containableLength(hit, evidence.readLengths().get(hit.queryID())) < shortest) {
                continue;
            }
            if (atProductLocus(hit, evidence.sequences().get(hit.queryID()), shortest, flanks.get(role(hit)))) {
                informative.add(fragment);
            } else {
                elsewhere.add(fragment);
            }
        }
        elsewhere.removeAll(informative);

        // Representative: fewest primer mismatches, then fewest probe mismatches (of the
        // variants of a degenerate probe that hit a read, the one that matches best), then
        // a stable order of reads and of probe variants.
        ReportRow best = rows.stream()
                .min(ReportRow.BEST_BOUND
                        .thenComparing(ReportRow::contig)
                        .thenComparing(ReportRow.VARIANT_ORDER))
                .orElseThrow();
        return new ReadCall(assay, supporting.size(), informative.size(), elsewhere.size(), best, sizes,
                evidence.minSupportFraction());
    }

    /**
     * The sequence next to each primer inside the product, as the supporting reads show
     * it: per primer role, the most frequent distinct flanks.
     *
     * @param spans amplicon start and end per supporting read
     */
    private static Map<PrimerId.Role, List<String>> productFlanks(Map<String, int[]> spans, Evidence evidence,
                                                              int shortest) {
        Map<PrimerId.Role, Map<String, Integer>> counts = new HashMap<>();
        for (BlastResult hit : evidence.primerHits()) {
            int[] span = spans.get(hit.queryID());
            String sequence = evidence.sequences().get(hit.queryID());
            if (span == null || sequence == null) {
                continue;
            }
            // The hit that bounds the amplicon, not another copy of the primer on the read.
            boolean bounds = hit.isPlusStrand() ? primerStart(hit) == span[0] : primerEnd(hit) == span[1];
            String flank = bounds ? flank(sequence, hit, flankLength(hit, shortest)) : null;
            if (flank != null) {
                counts.computeIfAbsent(role(hit), ignored -> new HashMap<>()).merge(flank, 1, Integer::sum);
            }
        }
        Map<PrimerId.Role, List<String>> flanks = new HashMap<>();
        counts.forEach((role, byFlank) -> flanks.put(role, byFlank.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_REFERENCE_WINDOWS)
                .map(Map.Entry::getKey)
                .toList()));
        return flanks;
    }

    /**
     * Whether the read carries the primer at the product's locus: the bases following
     * the primer resemble those of a supporting read. A read that cannot be judged (no
     * sequence, nothing to compare with, too few bases between the primers) counts as
     * being at the locus, which is the cautious side: it can only lower the support.
     */
    private static boolean atProductLocus(BlastResult hit, String sequence, int shortest, List<String> flanks) {
        if (sequence == null || flanks == null || flanks.isEmpty()) {
            return true;
        }
        String flank = flank(sequence, hit, flankLength(hit, shortest));
        if (flank == null) {
            return true;
        }
        for (String reference : flanks) {
            int length = Math.min(flank.length(), reference.length());
            if (editDistance(flank, reference, length) <= (int) (MAX_LOCUS_DIVERGENCE * length)) {
                return true;
            }
        }
        return false;
    }

    /** Bases compared after a primer: up to {@link #LOCUS_WINDOW}, within the shortest product. */
    private static int flankLength(BlastResult hit, int shortest) {
        return Math.min(LOCUS_WINDOW, shortest - hit.slen());
    }

    /**
     * The {@code length} bases following the primer's 3' end, read in the primer's
     * direction, or {@code null} when there are too few to judge by or the read ends first.
     */
    static String flank(String sequence, BlastResult hit, int length) {
        if (length < MIN_LOCUS_WINDOW) {
            return null;
        }
        if (hit.isPlusStrand()) {
            int from = primerEnd(hit);
            return from + length > sequence.length() ? null
                    : sequence.substring(from, from + length).toUpperCase(Locale.ROOT);
        }
        int to = primerStart(hit) - 1;
        return to - length < 0 || to > sequence.length() ? null
                : Dna.reverseComplement(sequence.substring(to - length, to).toUpperCase(Locale.ROOT));
    }


    /** Edit distance between the first {@code length} bases of two sequences. */
    static int editDistance(String a, String b, int length) {
        int[] previous = new int[length + 1];
        int[] current = new int[length + 1];
        for (int j = 0; j <= length; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= length; i++) {
            current[0] = i;
            for (int j = 1; j <= length; j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j], current[j - 1]) + 1);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[length];
    }

    /** The role of the primer a hit belongs to. */
    private static PrimerId.Role role(BlastResult hit) {
        PrimerId.Role role = PrimerId.roleOf(hit.subjectID());
        if (role == null) {
            throw new IllegalArgumentException("Not a primer ID of the <assay>-F/-R/-P convention: " + hit.subjectID());
        }
        return role;
    }

    /**
     * Longest amplicon starting at this primer's 5' end that fits in the read. Hits are
     * already restricted to primers fully inside the read ({@link #withContainedHits}).
     */
    static int containableLength(BlastResult hit, Integer readLength) {
        if (readLength == null) {
            return 0;
        }
        if (hit.isPlusStrand()) {
            return readLength - (hit.qstart() - hit.fivePrimeUncovered()) + 1;
        }
        return hit.qend() + hit.fivePrimeUncovered();
    }

    /**
     * The report row for a read-level call: the representative read's row, with the
     * description saying where the call comes from.
     */
    public static ReportRow reportRow(ReadCall call) {
        String original = call.row().contigDescription();
        return call.row().calledFrom(
                new ReportRow.Source.Reads(call.supportingReads(), call.informativeReads(), percent(call.supportFraction())),
                ROW_PREFIX + ": complete amplicon in " + call.supportSummary() + ", not in the mini-assembly"
                        + (original.isEmpty() ? "" : "; representative read " + original));
    }
}
