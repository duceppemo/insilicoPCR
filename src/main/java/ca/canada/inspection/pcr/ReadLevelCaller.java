package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ConsolidatedReport.PrimerGroup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Calls amplicons directly on baited reads (FASTQ mode).
 *
 * <p>The mini-assembly can omit a target region or break inside an amplicon
 * (#7), yet single reads, or merged read pairs, are often long enough to hold
 * a complete qPCR amplicon. Each read is treated as a tiny contig and run through
 * the same hit filters and amplicon/probe rules as the assembly
 * ({@link ConsolidatedReport#rows}), so a read-level call means exactly what a
 * contig-level call means.</p>
 */
public final class ReadLevelCaller {

    /**
     * Default minimum share of informative reads that must hold the amplicon for a
     * read-level call (command line: {@code --read-min-fraction}). A real target shows in most reads that carry a primer and are long enough,
     * though not all: a sequencing error in a primer or probe disqualifies a read under a
     * strict mismatch budget (61% on a 2x250 MiSeq run in #7). A few contaminating or
     * index-hopped reads show in few of them (18% in the same issue).
     */
    public static final double DEFAULT_MIN_SUPPORT_FRACTION = 0.3;

    /** Minimum reads holding the amplicon for a read-level call. */
    public static final int MIN_SUPPORTING_READS = 2;

    /**
     * @param supportingReads  distinct reads holding a complete, reportable amplicon
     * @param informativeReads distinct reads carrying the assay's forward or reverse primer
     *                         that extend far enough to have held the amplicon
     * @param row              report row on the best-matching representative read
     * @param sizes            amplicon sizes observed across the supporting reads
     * @param minSupportFraction share of informative reads required to report the call
     */
    public record ReadCall(String assay, int supportingReads, int informativeReads, List<String> row,
                           Set<Integer> sizes, double minSupportFraction) {
        public double supportFraction() {
            return informativeReads == 0 ? 0.0 : (double) supportingReads / informativeReads;
        }

        /** Whether the support is strong enough to report the assay from reads. */
        public boolean isReportable() {
            return supportingReads >= MIN_SUPPORTING_READS && supportFraction() >= minSupportFraction;
        }

        /** The reporting threshold, for messages. */
        public String thresholdSummary() {
            return "at least " + MIN_SUPPORTING_READS + " reads and " + percent(minSupportFraction)
                    + " of informative reads";
        }

        public String supportSummary() {
            return supportingReads + " of " + informativeReads + " informative read(s) ("
                    + percent(supportFraction()) + ")";
        }
    }

    /**
     * What the baited reads say about each assay.
     *
     * @param readsPerOligo    distinct reads holding the whole oligo with an accepted hit,
     *                         per expanded oligo ID (every oligo a read carries is counted)
     * @param calls            assays with at least one read holding a complete amplicon
     * @param checkableLength  per assay, the longest amplicon a read carrying one of
     *                         its primers could have held: from the primer's 5' end to
     *                         the read end in the primer's extension direction
     */
    public record ReadEvidence(Map<String, Long> readsPerOligo, Map<String, ReadCall> calls,
                               Map<String, Integer> checkableLength) {
        public ReadEvidence {
            readsPerOligo = Map.copyOf(readsPerOligo);
            calls = Map.copyOf(calls);
            checkableLength = Map.copyOf(checkableLength);
        }
    }

    private ReadLevelCaller() {
    }

    /**
     * @param reads       pseudo-sample whose "contigs" are the baited reads: accepted
     *                    primer hits attached, read descriptions as contig descriptions
     * @param readLengths read lengths keyed by read ID
     */
    public static ReadEvidence evaluate(Sample reads, Map<String, Integer> readLengths,
                                        Map<String, String> primers, AmpliconOptions options) {
        return evaluate(reads, readLengths, primers, options, DEFAULT_MIN_SUPPORT_FRACTION);
    }

    /**
     * @param minSupportFraction share of informative reads (0 to 1) that must hold the
     *                           amplicon for the call to be reported
     */
    public static ReadEvidence evaluate(Sample reads, Map<String, Integer> readLengths,
                                        Map<String, String> primers, AmpliconOptions options,
                                        double minSupportFraction) {
        requireFraction(minSupportFraction);
        reads = withContainedHits(reads, readLengths);
        Map<String, List<BlastResult>> hits = reads.blastResults();

        Map<String, Long> readsPerOligo = new HashMap<>();
        hits.forEach((oligo, list) ->
                readsPerOligo.put(oligo, list.stream().map(BlastResult::queryID).distinct().count()));

        Map<String, List<List<String>>> rowsByAssay = new TreeMap<>();
        for (List<String> row : ConsolidatedReport.rows(reads, primers, options)) {
            rowsByAssay.computeIfAbsent(row.get(ConsolidatedReport.GENE_COLUMN), ignored -> new ArrayList<>()).add(row);
        }
        Map<String, PrimerGroup> groups = ConsolidatedReport.groupByAssay(primers.keySet());
        Map<String, ReadCall> calls = new LinkedHashMap<>();
        rowsByAssay.forEach((assay, rows) ->
                calls.put(assay, call(assay, rows, pcrPrimerHits(hits, groups.get(assay)), readLengths,
                        minSupportFraction)));

        Map<String, Integer> checkable = new HashMap<>();
        for (Map.Entry<String, PrimerGroup> entry : groups.entrySet()) {
            int longest = 0;
            for (BlastResult hit : pcrPrimerHits(hits, entry.getValue())) {
                longest = Math.max(longest, containableLength(hit, readLengths.get(hit.queryID())));
            }
            if (longest > 0) {
                checkable.put(entry.getKey(), longest);
            }
        }
        return new ReadEvidence(readsPerOligo, calls, checkable);
    }

    /**
     * Keeps only hits whose full primer lies inside the read. The hit filters tolerate a
     * few unaligned 5' primer bases (a 5'-proximal mismatch makes BLAST trim them); on a
     * read, those bases are more often simply beyond the read end, and the amplicon would
     * then be clipped and undersized. A read-level call must hold the whole product.
     */
    static Sample withContainedHits(Sample reads, Map<String, Integer> readLengths) {
        Sample contained = new Sample();
        contained.setName(reads.getName());
        contained.setFileType(reads.getFileType());
        reads.contigs().forEach(contained::addContig);
        reads.blastResults().forEach((oligo, list) -> {
            for (BlastResult hit : list) {
                Integer length = readLengths.get(hit.queryID());
                if (length != null && primerStart(hit) >= 1 && primerEnd(hit) <= length) {
                    contained.addBlastResult(oligo, hit);
                }
            }
        });
        return contained;
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

    private static List<BlastResult> pcrPrimerHits(Map<String, List<BlastResult>> hits, PrimerGroup group) {
        List<BlastResult> result = new ArrayList<>();
        if (group != null) {
            for (String primer : group.forward) {
                result.addAll(hits.getOrDefault(primer, List.of()));
            }
            for (String primer : group.reverse) {
                result.addAll(hits.getOrDefault(primer, List.of()));
            }
        }
        return result;
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

    private static ReadCall call(String assay, List<List<String>> rows, List<BlastResult> primerHits,
                                 Map<String, Integer> readLengths, double minSupportFraction) {
        Set<String> reads = new HashSet<>();
        Set<Integer> sizes = new TreeSet<>();
        for (List<String> row : rows) {
            reads.add(row.get(ConsolidatedReport.CONTIG_COLUMN));
            sizes.add(Integer.parseInt(row.get(ConsolidatedReport.AMPLICON_SIZE_COLUMN)));
        }
        // Reads that would have shown the amplicon had the target been there: they carry
        // a primer and reach far enough from its 5' end. Supporting reads are among them.
        int shortest = sizes.stream().mapToInt(Integer::intValue).min().orElseThrow();
        Set<String> informative = new HashSet<>(reads);
        for (BlastResult hit : primerHits) {
            if (containableLength(hit, readLengths.get(hit.queryID())) >= shortest) {
                informative.add(hit.queryID());
            }
        }
        // Representative: fewest primer mismatches, then a stable read order.
        List<String> best = rows.stream()
                .min(Comparator.<List<String>>comparingInt(row ->
                                Integer.parseInt(row.get(ConsolidatedReport.FORWARD_MISMATCHES_COLUMN))
                                        + Integer.parseInt(row.get(ConsolidatedReport.REVERSE_MISMATCHES_COLUMN)))
                        .thenComparing(row -> row.get(ConsolidatedReport.CONTIG_COLUMN)))
                .orElseThrow();
        return new ReadCall(assay, reads.size(), informative.size(), best, sizes, minSupportFraction);
    }

    /** Longest amplicon starting at this primer's 5' end that fits in the read. */
    static int containableLength(BlastResult hit, Integer readLength) {
        if (readLength == null) {
            return 0;
        }
        if (hit.isPlusStrand()) {
            int start = Math.max(1, hit.qstart() - hit.fivePrimeUncovered());
            return readLength - start + 1;
        }
        return Math.min(readLength, hit.qend() + hit.fivePrimeUncovered());
    }

    /**
     * The report row for a read-level call: the representative read's row, with the
     * description saying where the call comes from.
     */
    public static List<String> reportRow(ReadCall call) {
        List<String> row = new ArrayList<>(call.row());
        String original = row.get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN);
        row.set(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN,
                "FASTQ read-level call: complete amplicon in " + call.supportSummary() + ", "
                        + "not in the mini-assembly" + (original.isEmpty() ? "" : "; representative read " + original));
        return row;
    }
}
