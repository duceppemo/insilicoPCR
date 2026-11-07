package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ConsolidatedReport.PrimerGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flags FASTQ-mode negatives that the reads do not support.
 *
 * <p>In FASTQ mode amplicons are called on a Tadpole mini-assembly of the baited
 * reads, so an assay can come out negative even though its oligos were found in
 * the reads: the assembly may omit the target region, or break inside the
 * amplicon so the primers land on different contigs. Without a flag such a
 * result is indistinguishable from "target absent". For every assay that has
 * baited reads but no reported amplicon, this class says which of those cases
 * applies.</p>
 */
public final class FastqDiagnostics {

    /** Hits listed per primer role in a {@link Status#SPLIT_ACROSS_CONTIGS} detail. */
    private static final int MAX_LISTED_HITS = 3;

    public enum Status {
        /** Forward and reverse primers both baited reads, but at least one has no accepted contig hit. */
        NOT_ASSEMBLED("oligos found in reads but the target region is missing from the mini-assembly"),
        /** Forward and reverse primers both hit contigs, but never the same contig. */
        SPLIT_ACROSS_CONTIGS("forward and reverse primers are on different contigs of the mini-assembly"),
        /** Forward and reverse primers share a contig, yet no amplicon passed the orientation, size or probe rules. */
        NO_VALID_PAIR("primers share a contig but form no reportable amplicon (orientation, size limits or probe)"),
        /** Only some of the assay's primers baited reads; weaker evidence, possibly a true negative. */
        PARTIAL_BAIT("only some of the assay's primers were found in the reads");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** Strong statuses point at the assembly rather than the sample and are warned about loudly. */
        public boolean isWarning() {
            return this != PARTIAL_BAIT;
        }
    }

    /**
     * @param baitedReads reads matched by the assay's oligos in the first BBDuk pass,
     *                    as forward / reverse / probe totals
     */
    public record Finding(String sample, String assay, Status status, BaitedReads baitedReads, String detail) {
        public String message() {
            return "sample " + sample + ", assay " + assay + ": " + status.description()
                    + " (baited reads " + baitedReads + ")" + (detail.isEmpty() ? "" : "; " + detail);
        }
    }

    public record BaitedReads(long forward, long reverse, long probe) {
        public long total() {
            return forward + reverse + probe;
        }

        @Override
        public String toString() {
            return "F=" + forward + " R=" + reverse + " P=" + probe;
        }
    }

    private FastqDiagnostics() {
    }

    /**
     * @param sample        FASTQ sample with its accepted BLAST hits attached
     * @param primers       expanded primer IDs and sequences of the run
     * @param baitedReads   reads per expanded oligo ID from the first BBDuk pass
     *                      (oligos without reads may be absent)
     * @param contigLengths assembly contig lengths keyed by contig ID as BLAST reports it
     * @return one finding per assay that baited reads but has no reported amplicon
     */
    public static List<Finding> assess(Sample sample, Map<String, String> primers, AmpliconOptions options,
                                       Map<String, Long> baitedReads, Map<String, Integer> contigLengths) {
        Set<String> reported = ConsolidatedReport.reportedAssays(sample, primers, options);
        Map<String, List<BlastResult>> hits = sample.blastResults();
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, PrimerGroup> entry : ConsolidatedReport.groupByAssay(primers.keySet()).entrySet()) {
            String assay = entry.getKey();
            PrimerGroup group = entry.getValue();
            if (group.forward.isEmpty() || group.reverse.isEmpty() || reported.contains(assay)) {
                continue;
            }
            BaitedReads reads = new BaitedReads(
                    sum(baitedReads, group.forward), sum(baitedReads, group.reverse), sum(baitedReads, group.probe));
            if (reads.total() == 0) {
                continue; // nothing in the reads: an ordinary negative
            }
            findings.add(classify(sample.getName(), assay, group, reads, hits, contigLengths));
        }
        return findings;
    }

    private static Finding classify(String sampleName, String assay, PrimerGroup group, BaitedReads reads,
                                    Map<String, List<BlastResult>> hits, Map<String, Integer> contigLengths) {
        List<BlastResult> forwardHits = hitsOf(hits, group.forward);
        List<BlastResult> reverseHits = hitsOf(hits, group.reverse);

        if (reads.forward() == 0 || reads.reverse() == 0) {
            String missing = reads.forward() == 0 ? "forward" : "reverse";
            return new Finding(sampleName, assay, Status.PARTIAL_BAIT, reads,
                    "no reads carry the " + missing + " primer");
        }
        if (forwardHits.isEmpty() || reverseHits.isEmpty()) {
            List<String> absent = new ArrayList<>();
            if (forwardHits.isEmpty()) {
                absent.add(String.join(",", group.forward));
            }
            if (reverseHits.isEmpty()) {
                absent.add(String.join(",", group.reverse));
            }
            return new Finding(sampleName, assay, Status.NOT_ASSEMBLED, reads,
                    "no accepted contig hit for " + String.join(" and ", absent));
        }

        Set<String> forwardContigs = contigsOf(forwardHits);
        Set<String> shared = contigsOf(reverseHits);
        shared.retainAll(forwardContigs);
        if (shared.isEmpty()) {
            return new Finding(sampleName, assay, Status.SPLIT_ACROSS_CONTIGS, reads,
                    "forward " + describe(forwardHits, contigLengths) + "; reverse " + describe(reverseHits, contigLengths));
        }
        return new Finding(sampleName, assay, Status.NO_VALID_PAIR, reads,
                "shared contig(s): " + String.join(", ", shared.stream().map(FastqDiagnostics::shortName).sorted().toList()));
    }

    /**
     * Lists hits as {@code primer contig:start-end(strand), N bp to contig end}, where the
     * distance is measured in the primer's extension direction: the side the amplicon
     * would lie on, and so where the assembly broke when the pair is split.
     */
    private static String describe(List<BlastResult> hits, Map<String, Integer> contigLengths) {
        List<String> parts = new ArrayList<>();
        for (BlastResult hit : hits.subList(0, Math.min(MAX_LISTED_HITS, hits.size()))) {
            StringBuilder part = new StringBuilder()
                    .append(hit.subjectID()).append(' ')
                    .append(shortName(hit.queryID())).append(':')
                    .append(hit.qstart()).append('-').append(hit.qend())
                    .append('(').append(hit.isPlusStrand() ? '+' : '-').append(')');
            Integer length = contigLengths.get(hit.queryID());
            if (length != null) {
                int toEnd = hit.isPlusStrand() ? length - hit.qend() : hit.qstart() - 1;
                part.append(", ").append(toEnd).append(" bp to contig end (contig ").append(length).append(" bp)");
            }
            parts.add(part.toString());
        }
        if (hits.size() > MAX_LISTED_HITS) {
            parts.add("+" + (hits.size() - MAX_LISTED_HITS) + " more");
        }
        return String.join(" | ", parts);
    }

    /** Tadpole names contigs {@code contig_6,len=1534,cov=26.3,...}; keep the {@code contig_6} part. */
    static String shortName(String contigId) {
        int comma = contigId.indexOf(',');
        return comma > 0 ? contigId.substring(0, comma) : contigId;
    }

    private static long sum(Map<String, Long> baitedReads, List<String> oligoIds) {
        return oligoIds.stream().mapToLong(id -> baitedReads.getOrDefault(id, 0L)).sum();
    }

    private static List<BlastResult> hitsOf(Map<String, List<BlastResult>> hits, List<String> oligoIds) {
        List<BlastResult> result = new ArrayList<>();
        for (String id : oligoIds) {
            result.addAll(hits.getOrDefault(id, List.of()));
        }
        return result;
    }

    private static Set<String> contigsOf(List<BlastResult> hits) {
        Set<String> contigs = new HashSet<>();
        for (BlastResult hit : hits) {
            contigs.add(hit.queryID());
        }
        return contigs;
    }
}
