package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Assembly input: assays with no product whose primers bind near the ends of two contigs,
 * or near both ends of one, each pointing off its end. A product needs both primers on one
 * contig, so one that spans a break in the assembly, or the end of a circular sequence such
 * as a plasmid, is not reported; these say where one could be, so that such a negative is
 * not taken for a true one.
 */
public final class SplitAcrossContigs {

    private SplitAcrossContigs() {
    }

    /**
     * The pair of primer hits that would give the smallest product if the sequence went on
     * past the contig ends.
     *
     * @param forwardToEnd bases of the contig past the forward primer's 3' end, in the
     *                     direction it extends
     * @param minimumSize  size of the product if nothing lay between the two contig ends
     * @param sameContig   both primers point off the two ends of one contig
     * @param probe        the assay has a probe, which cannot be checked across the break
     */
    public record Split(String sample, String assay, BlastResult forward, int forwardToEnd, BlastResult reverse,
                        int reverseToEnd, int minimumSize, boolean sameContig, boolean probe) {

        /** What the user is told, without a {@code Warning:} prefix. */
        public String message() {
            String where = sameContig
                    ? "near both ends of " + forward.queryID() + ": " + hit(forward, forwardToEnd) + ", and "
                    + hit(reverse, reverseToEnd) + ". If " + forward.queryID() + " is circular (a plasmid or a"
                    + " closed chromosome), a product of at least " + minimumSize + " bp spans its end"
                    : "near the ends of two contigs: " + hit(forward, forwardToEnd) + " on " + forward.queryID()
                    + ", and " + hit(reverse, reverseToEnd) + " on " + reverse.queryID() + ". Joined, they would"
                    + " give a product of at least " + minimumSize + " bp: the assembly may break inside it";
            return "sample " + sample + ", assay " + assay + ": no product, but the primers bind " + where
                    + (probe ? " (the probe cannot be checked across the break)" : "")
                    + ". Check with a more complete assembly, or with the reads.";
        }

        /** Report cell note. */
        public String note() {
            return sameContig
                    ? "No product, but the primers point off both ends of " + forward.queryID() + ": if it is"
                    + " circular, a product of at least " + minimumSize + " bp spans its end."
                    : "No product, but the primers point off the ends of " + forward.queryID() + " and "
                    + reverse.queryID() + ": joined, they would give a product of at least " + minimumSize + " bp.";
        }

        private static String hit(BlastResult hit, int toEnd) {
            return hit.subjectID() + " at " + hit.qstart() + "-" + hit.qend() + "(" + (hit.isPlusStrand() ? '+' : '-')
                    + "), " + toEnd + " bp from the end";
        }
    }

    /**
     * @param contigLengths the sample's sequence lengths by ID as BLAST reports it; read only
     *                      when an assay has no product but hits of both primers
     * @return one split per assay that could have one, in assay order
     */
    public static List<Split> find(Sample sample, Map<String, String> primers, AmpliconOptions options,
                                   Supplier<Map<String, Integer>> contigLengths) {
        Set<String> reported = ConsolidatedReport.reportedSizes(sample, primers, options).keySet();
        Map<String, List<BlastResult>> hits = sample.blastResults();
        Map<String, Integer> lengths = null;
        List<Split> splits = new ArrayList<>();
        for (Map.Entry<String, ConsolidatedReport.PrimerGroup> entry
                : ConsolidatedReport.groupByAssay(primers.keySet()).entrySet()) {
            String assay = entry.getKey();
            ConsolidatedReport.PrimerGroup group = entry.getValue();
            if (reported.contains(assay) || group.forward.isEmpty() || group.reverse.isEmpty()) {
                continue;
            }
            List<BlastResult> forward = hitsOf(hits, group.forward);
            List<BlastResult> reverse = hitsOf(hits, group.reverse);
            if (forward.isEmpty() || reverse.isEmpty()) {
                continue; // a primer that does not bind: an ordinary negative
            }
            if (lengths == null) {
                lengths = contigLengths.get();
            }
            Split best = null;
            for (BlastResult f : forward) {
                for (BlastResult r : reverse) {
                    Split split = split(sample.getName(), assay, f, r, lengths, !group.probe.isEmpty());
                    if (split != null && (best == null || split.minimumSize() < best.minimumSize())) {
                        best = split;
                    }
                }
            }
            if (best != null && (options.maxAmpliconLength() <= 0 || best.minimumSize() <= options.maxAmpliconLength())) {
                splits.add(best);
            }
        }
        return splits;
    }

    private static Split split(String sample, String assay, BlastResult forward, BlastResult reverse,
                               Map<String, Integer> lengths, boolean probe) {
        Integer forwardLength = lengths.get(forward.queryID());
        Integer reverseLength = lengths.get(reverse.queryID());
        if (forwardLength == null || reverseLength == null) {
            return null;
        }
        boolean sameContig = forward.queryID().equals(reverse.queryID());
        if (sameContig) {
            // Only primers pointing off opposite ends, each beyond the other: divergent on the contig.
            if (forward.isPlusStrand() == reverse.isPlusStrand()) {
                return null;
            }
            BlastResult right = forward.isPlusStrand() ? forward : reverse; // extends towards the end
            BlastResult left = forward.isPlusStrand() ? reverse : forward;  // extends towards the start
            if (right.qstart() <= left.qend()) {
                return null; // convergent: a product on the contig, or none for another reason
            }
        }
        return new Split(sample, assay, forward, toEnd(forward, forwardLength), reverse, toEnd(reverse, reverseLength),
                fromFivePrimeEnd(forward, forwardLength) + fromFivePrimeEnd(reverse, reverseLength), sameContig, probe);
    }

    /** Contig bases past the primer's 3' end, in the direction it extends. */
    private static int toEnd(BlastResult hit, int contigLength) {
        return hit.isPlusStrand() ? contigLength - hit.qend() : hit.qstart() - 1;
    }

    /** From the primer's 5' end, counted in full as in a product's size, to the contig end it points to. */
    private static int fromFivePrimeEnd(BlastResult hit, int contigLength) {
        int span = hit.isPlusStrand() ? contigLength - hit.qstart() + 1 : hit.qend();
        return span + hit.fivePrimeUncovered();
    }

    private static List<BlastResult> hitsOf(Map<String, List<BlastResult>> hits, List<String> oligoIds) {
        List<BlastResult> result = new ArrayList<>();
        for (String id : oligoIds) {
            result.addAll(hits.getOrDefault(id, List.of()));
        }
        return result;
    }
}
