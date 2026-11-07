package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ConsolidatedReport.PrimerGroup;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Explains FASTQ-mode assays that the mini-assembly did not report although their
 * oligos are in the baited reads (#7).
 *
 * <p>Amplicons are called on a Tadpole mini-assembly of the baited reads, which can
 * omit the target region or break inside the amplicon. For each assay the assembly
 * did not report but whose oligos are in the reads, this class records what the
 * assembly holds ({@link Assembly}) and what the reads themselves show
 * ({@link Status}), using the read-level search of {@link ReadLevelCaller}.</p>
 */
public final class FastqDiagnostics {

    /** Hits listed per primer role in a split-pair detail. */
    private static final int MAX_LISTED_HITS = 3;

    /** What the reads show for an assay the assembly did not report. */
    public enum Status {
        /** At least one read holds a complete amplicon; the assay is reported from reads. */
        CALLED_FROM_READS("not in the mini-assembly, called from reads"),
        /**
         * Some reads hold a complete amplicon, but too few of the reads that could have
         * shown it: possibly contamination, index hopping, a mixed sample, or a primer
         * site repeated elsewhere in the genome. Not reported.
         */
        WEAK_READ_SUPPORT("complete amplicon in a minority of informative reads; not reported"),
        /** Both primers are in the reads, but no read holds a complete amplicon. */
        NOT_IN_READS("primers found in reads, but no read holds a complete amplicon"),
        /** Only some of the assay's primers are in the reads. */
        PARTIAL_BAIT("only some of the assay's primers were found in the reads");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** Why the mini-assembly did not report the assay. */
    public enum Assembly {
        /** A primer found in the reads has no accepted hit on any contig. */
        NOT_ASSEMBLED,
        /** Forward and reverse primers hit contigs, but never the same contig. */
        SPLIT_ACROSS_CONTIGS,
        /** Primers share a contig, yet no amplicon passed the orientation, size or probe rules. */
        NO_VALID_PAIR
    }

    /**
     * @param readsWithOligo distinct baited reads carrying each primer role
     * @param checkableLength longest amplicon a read carrying one of the primers could hold
     *                        (0 when no read carries a primer)
     * @param readDetail      what the read search found
     * @param assemblyDetail  what the mini-assembly holds for the assay
     */
    public record Finding(String sample, String assay, Status status, Assembly assembly, ReadsWithOligo readsWithOligo,
                          int checkableLength, String readDetail, String assemblyDetail) {
        public String message() {
            return "sample " + sample + ", assay " + assay + ": " + status.description()
                    + " (" + readDetail + "; reads carrying F/R/P: " + readsWithOligo
                    + "; assembly: " + assembly + (assemblyDetail.isEmpty() ? "" : ", " + assemblyDetail) + ")";
        }
    }

    public record ReadsWithOligo(long forward, long reverse, long probe) {
        public long total() {
            return forward + reverse + probe;
        }

        @Override
        public String toString() {
            return forward + "/" + reverse + "/" + probe;
        }
    }

    private FastqDiagnostics() {
    }

    /**
     * @param sample        FASTQ sample with its accepted contig hits attached
     * @param primers       expanded primer IDs and sequences of the run
     * @param reads         read-level evidence for the sample
     * @param contigLengths assembly contig lengths keyed by contig ID as BLAST reports it
     * @return one finding per assay that the assembly did not report but whose oligos are in the reads
     */
    public static List<Finding> assess(Sample sample, Map<String, String> primers, AmpliconOptions options,
                                       ReadEvidence reads, Map<String, Integer> contigLengths) {
        Set<String> reported = ConsolidatedReport.reportedAssays(sample, primers, options);
        Map<String, List<BlastResult>> hits = sample.blastResults();
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, PrimerGroup> entry : ConsolidatedReport.groupByAssay(primers.keySet()).entrySet()) {
            String assay = entry.getKey();
            PrimerGroup group = entry.getValue();
            if (group.forward.isEmpty() || group.reverse.isEmpty() || reported.contains(assay)) {
                continue;
            }
            ReadsWithOligo counts = new ReadsWithOligo(sum(reads.readsPerOligo(), group.forward),
                    sum(reads.readsPerOligo(), group.reverse), sum(reads.readsPerOligo(), group.probe));
            if (counts.total() == 0) {
                continue; // nothing in the reads: an ordinary negative
            }
            int checkable = reads.checkableLength().getOrDefault(assay, 0);
            ReadCall call = reads.calls().get(assay);

            Status status;
            String readDetail;
            if (call != null) {
                status = call.isReportable() ? Status.CALLED_FROM_READS : Status.WEAK_READ_SUPPORT;
                readDetail = "complete amplicon in " + call.supportSummary() + ", size(s) "
                        + call.sizes().stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("") + " bp"
                        + (call.isReportable() ? "" : "; a read-level call needs at least "
                        + ReadLevelCaller.MIN_SUPPORTING_READS + " reads and "
                        + Math.round(100 * ReadLevelCaller.MIN_SUPPORT_FRACTION) + "% of informative reads");
            } else if (counts.forward() == 0 || counts.reverse() == 0) {
                status = Status.PARTIAL_BAIT;
                readDetail = "no read carries the " + (counts.forward() == 0 ? "forward" : "reverse") + " primer";
            } else {
                status = Status.NOT_IN_READS;
                readDetail = "no complete amplicon in the reads; reads carrying the primers could hold amplicons up to "
                        + checkable + " bp, so longer amplicons cannot be ruled out";
            }
            AssemblyFinding assembly = classifyAssembly(group, hits, contigLengths);
            findings.add(new Finding(sample.getName(), assay, status, assembly.kind(), counts, checkable,
                    readDetail, assembly.detail()));
        }
        return findings;
    }

    private record AssemblyFinding(Assembly kind, String detail) {
    }

    private static AssemblyFinding classifyAssembly(PrimerGroup group, Map<String, List<BlastResult>> hits,
                                                    Map<String, Integer> contigLengths) {
        List<BlastResult> forwardHits = hitsOf(hits, group.forward);
        List<BlastResult> reverseHits = hitsOf(hits, group.reverse);

        if (forwardHits.isEmpty() || reverseHits.isEmpty()) {
            List<String> absent = new ArrayList<>();
            if (forwardHits.isEmpty()) {
                absent.add(String.join(",", group.forward));
            }
            if (reverseHits.isEmpty()) {
                absent.add(String.join(",", group.reverse));
            }
            return new AssemblyFinding(Assembly.NOT_ASSEMBLED,
                    "no accepted contig hit for " + String.join(" and ", absent));
        }

        Set<String> shared = contigsOf(reverseHits);
        shared.retainAll(contigsOf(forwardHits));
        if (shared.isEmpty()) {
            return new AssemblyFinding(Assembly.SPLIT_ACROSS_CONTIGS,
                    "forward " + describe(forwardHits, contigLengths) + "; reverse " + describe(reverseHits, contigLengths));
        }
        return new AssemblyFinding(Assembly.NO_VALID_PAIR,
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

    private static long sum(Map<String, Long> counts, List<String> oligoIds) {
        return oligoIds.stream().mapToLong(id -> counts.getOrDefault(id, 0L)).sum();
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
