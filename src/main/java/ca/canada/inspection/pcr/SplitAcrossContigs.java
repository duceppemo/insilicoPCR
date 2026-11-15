package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;

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
    public static List<Split> find(Sample sample, PrimerPanel primers, AmpliconOptions options,
                                   Supplier<Map<String, Integer>> contigLengths) {
        Set<String> reported = Products.sizes(sample, primers, options).keySet();
        Map<String, Integer> lengths = null;
        List<Split> splits = new ArrayList<>();
        for (Map.Entry<String, PrimerId.Assay> entry : primers.completeAssays().entrySet()) {
            String assay = entry.getKey();
            PrimerId.Assay group = entry.getValue();
            if (reported.contains(assay)) {
                continue;
            }
            List<BlastResult> forward = sample.hits().of(group.forward());
            List<BlastResult> reverse = sample.hits().of(group.reverse());
            if (forward.isEmpty() || reverse.isEmpty()) {
                continue; // a primer that does not bind: an ordinary negative
            }
            if (lengths == null) {
                lengths = contigLengths.get();
            }
            Split best = null;
            for (BlastResult f : forward) {
                for (BlastResult r : reverse) {
                    Split split = split(sample.getName(), assay, f, r, lengths, group.hasProbe());
                    if (split != null && (best == null || split.minimumSize() < best.minimumSize())) {
                        best = split;
                    }
                }
            }
            if (best != null && best.minimumSize() <= reach(options)) {
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
        // Summed as a long: two chromosomes of over a gigabase overflowed an int into a negative size.
        long minimumSize = (long) forward.toQueryEnd(forwardLength) + reverse.toQueryEnd(reverseLength);
        if (minimumSize > Integer.MAX_VALUE) {
            return null; // longer than any product looked for
        }
        return new Split(sample, assay, forward, toEnd(forward, forwardLength), reverse, toEnd(reverse, reverseLength),
                (int) minimumSize, sameContig, probe);
    }

    /**
     * The longest product a split may hide: the maximum product length, or without one
     * ({@code -l 0}) the reach of the read extension, which looks for products longer than a
     * read no further. Without a limit, any two primers pointing off two contig ends, megabases
     * from them, were a split.
     */
    static int reach(AmpliconOptions options) {
        return options.maxAmpliconLength() > 0 ? options.maxAmpliconLength() : ReadExtensionCaller.DEFAULT_REACH;
    }

    /** Contig bases past the primer's 3' end, in the direction it extends (also in the FASTQ diagnostics). */
    static int toEnd(BlastResult hit, int contigLength) {
        return hit.isPlusStrand() ? contigLength - hit.qend() : hit.qstart() - 1;
    }

}
