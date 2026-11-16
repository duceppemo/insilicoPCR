package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadCall;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
         * No read holds a complete amplicon (the product is longer than a read or merged
         * pair), but reads extended from both primers do; the assay is reported from them.
         */
        CALLED_BY_EXTENSION("product in no contig and in no read, called by extending reads"),
        /**
         * Some reads hold a complete amplicon, but too few of the reads that cover the
         * same locus and could have shown it: possibly contamination, index hopping or
         * a mixed sample. Not reported.
         */
        WEAK_READ_SUPPORT("complete amplicon in too few of the informative reads; not reported"),
        /** Both primers are in the reads, but no read holds a complete amplicon. */
        NOT_IN_READS("primers found in reads, but no read holds a complete amplicon"),
        /**
         * As {@link #NOT_IN_READS}, and extending the primer-carrying reads through the
         * baited reads did not join the two primers either ({@link ReadExtensionCaller}).
         */
        NOT_CLOSED_BY_EXTENSION("primers found in reads, but neither a read nor the extended reads hold a"
                + " complete amplicon"),
        /**
         * As {@link #NOT_CLOSED_BY_EXTENSION}, but the extended reads run into a tandem repeat
         * ((AT)n, (CAG)n, a VNTR) and loop in it: the extension, which follows the better-supported
         * branch, never finds the way out of one longer than a k-mer. Inconclusive: a product
         * through it is neither shown nor ruled out (before 0.15.3, a plain negative).
         */
        EXTENSION_IN_TANDEM_REPEAT("primers found in reads, but the reads extended from them run into a tandem"
                + " repeat they cannot cross; inconclusive"),
        /**
         * Extended reads hold a product, but the sample's read pairs do
         * not span it everywhere, or are too few to tell: the mark of a wrong turn through
         * a repeat. Not reported.
         */
        EXTENSION_NOT_SUPPORTED_BY_PAIRS("extended reads join the primers, but read pairs do not support the"
                + " product; not reported"),
        /**
         * Extended reads hold a product that passes through a repeat
         * longer than the read pairs, where a real product and a wrong turn look the same.
         * Not reported.
         */
        EXTENSION_THROUGH_UNRESOLVED_REPEAT("extended reads join the primers through a repeat longer than the"
                + " read pairs can bridge; not reported"),
        /** Only some of the assay's primers are in the reads. */
        PARTIAL_BAIT("only some of the assay's primers were found in the reads");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }

        /** The assay is reported from the reads: the report has its rows. */
        public boolean isCalled() {
            return this == CALLED_FROM_READS || this == CALLED_BY_EXTENSION;
        }

        /** The reads show a product that is not reported: too few reads, or read pairs that do not confirm it. */
        public boolean isWithheld() {
            return this == WEAK_READ_SUPPORT || this == EXTENSION_NOT_SUPPORTED_BY_PAIRS
                    || this == EXTENSION_THROUGH_UNRESOLVED_REPEAT;
        }
    }

    /** Why the mini-assembly did not report the assay. */
    public enum Assembly {
        /** A primer found in the reads has no accepted hit on any contig. */
        NOT_ASSEMBLED,
        /** Forward and reverse primers hit contigs, but never the same contig. */
        SPLIT_ACROSS_CONTIGS,
        /** Primers share a contig, yet no amplicon passed the orientation, size or probe rules. */
        NO_VALID_PAIR,
        /**
         * The assembly reports the assay, but only at another size: the product called
         * from reads is one it lacks.
         */
        OTHER_PRODUCT_ONLY
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
     * @return one finding per assay that the assembly did not report but whose oligos are in the
     *         reads, and per assay it reports at another size only than a product called from reads
     *         or by extending reads
     */
    public static List<Finding> assess(Sample sample, PrimerPanel primers, AmpliconOptions options,
                                       ReadEvidence reads, Map<String, Integer> contigLengths) {
        return assess(sample, primers, options, reads, contigLengths, Map.of());
    }

    /**
     * As {@link #assess(Sample, PrimerPanel, AmpliconOptions, ReadEvidence, Map)}, taking into account what
     * extending the reads showed.
     *
     * @param extension per assay the reads were extended for, the outcome; empty when the
     *                  read extension did not run for the sample. An assay that already shows
     *                  a product gets a finding from it only when the extension adds another
     */
    public static List<Finding> assess(Sample sample, PrimerPanel primers, AmpliconOptions options,
                                       ReadEvidence reads, Map<String, Integer> contigLengths,
                                       Map<String, ReadExtensionCaller.Outcome> extension) {
        Map<String, Set<Integer>> assembled = Products.sizes(sample, primers, options);
        Map<String, Set<String>> otherProducts = otherProducts(Products.of(sample, primers, options));
        List<Finding> findings = new ArrayList<>();

        for (Map.Entry<String, PrimerId.Assay> entry : primers.assays().entrySet()) {
            String assay = entry.getKey();
            PrimerId.Assay group = entry.getValue();
            ReadsWithOligo counts = new ReadsWithOligo(reads.readsCarrying(group.forward()),
                    reads.readsCarrying(group.reverse()), reads.readsCarrying(group.probe()));
            int checkable = reads.checkableLength().getOrDefault(assay, 0);
            List<ReadCall> products = byStrength(reads.products(assay));

            // What the extended reads show beyond what the sample already shows for the assay: the
            // products they add, and those the read pairs do not confirm. The status is decided from
            // these; the text only describes them.
            ReadExtensionCaller.Outcome outcome = extension.get(assay);
            List<ReadExtensionCaller.Product> added = outcome == null ? List.of() : outcome.added();
            List<ReadExtensionCaller.Product> unconfirmed = outcome == null ? List.of() : outcome.withheld().stream()
                    .filter(product -> !outcome.isReported(product)).toList();
            String extended = extended(added, unconfirmed);

            Set<Integer> assembledSizes = assembled.get(assay);
            if (assembledSizes != null) {
                // The assembly reports the assay: only a product it lacks needs explaining.
                List<ReadCall> missing = ReadLevelCaller.missingFromAssembly(products, assembledSizes);
                if (!missing.isEmpty() || !added.isEmpty() || !unconfirmed.isEmpty()) {
                    findings.add(new Finding(sample.getName(), assay,
                            !missing.isEmpty() ? Status.CALLED_FROM_READS
                                    : !added.isEmpty() ? Status.CALLED_BY_EXTENSION : withheldStatus(unconfirmed),
                            Assembly.OTHER_PRODUCT_ONLY, counts, checkable,
                            missing.isEmpty() ? extended : describe(missing) + (extended.isEmpty() ? "" : "; " + extended),
                            "reports " + join(assembledSizes) + " bp only"));
                }
                continue;
            }
            if (counts.total() == 0) {
                continue; // nothing in the reads: an ordinary negative
            }

            Status status;
            String readDetail;
            if (!products.isEmpty()) {
                // Reads hold a product; the extension can only have added one of another size.
                if (products.getFirst().isReportable() || added.isEmpty()) {
                    status = products.getFirst().isReportable() ? Status.CALLED_FROM_READS : Status.WEAK_READ_SUPPORT;
                    readDetail = describe(products) + (extended.isEmpty() ? "" : "; " + extended);
                } else {
                    status = Status.CALLED_BY_EXTENSION;
                    readDetail = extended + "; " + describe(products);
                }
            } else if (counts.forward() == 0 || counts.reverse() == 0) {
                status = Status.PARTIAL_BAIT;
                readDetail = counts.forward() == 0 && counts.reverse() == 0 ? "no read carries the forward or the"
                        + " reverse primer, only the probe"
                        : "no read carries the " + (counts.forward() == 0 ? "forward" : "reverse") + " primer";
            } else if (outcome == null) {
                status = Status.NOT_IN_READS;
                readDetail = "no complete amplicon in the reads; reads carrying the primers could hold amplicons up to "
                        + checkable + " bp, so longer amplicons cannot be ruled out";
            } else {
                // Nothing in the reads: all the extension holds is new to the sample.
                status = !added.isEmpty() ? Status.CALLED_BY_EXTENSION
                        : !unconfirmed.isEmpty() ? withheldStatus(unconfirmed)
                        : outcome.tandemRepeat() != null ? Status.EXTENSION_IN_TANDEM_REPEAT
                        : Status.NOT_CLOSED_BY_EXTENSION;
                readDetail = "no complete amplicon in the reads, which could hold amplicons up to " + checkable
                        + " bp; " + describe(outcome) + (status == Status.EXTENSION_IN_TANDEM_REPEAT
                        ? "; " + outcome.tandemRepeat() : "");
            }
            AssemblyFinding assembly = classifyAssembly(group, sample, contigLengths);
            // The report may still show the assay's primers in a product that is not its designed pair's: the
            // finding said "only the forward primer in the reads" beside a report row of the assay.
            Set<String> others = otherProducts.get(assay);
            String assemblyDetail = others == null ? assembly.detail()
                    : assembly.detail() + "; the report's " + String.join(", ", others) + " (not the designed pair)";
            findings.add(new Finding(sample.getName(), assay, status, assembly.kind(), counts, checkable,
                    readDetail, assemblyDetail));
        }
        return findings;
    }

    /** What the extended reads add and what read pairs do not confirm, as one text; empty for neither. */
    private static String extended(List<ReadExtensionCaller.Product> added,
                                   List<ReadExtensionCaller.Product> unconfirmed) {
        List<String> parts = new ArrayList<>();
        if (!added.isEmpty()) {
            parts.add(calledByExtension(added));
        }
        if (!unconfirmed.isEmpty()) {
            parts.add(withheldByPairs(unconfirmed));
        }
        return String.join("; ", parts);
    }

    /** Reportable products first, then by decreasing support. */
    private static List<ReadCall> byStrength(List<ReadCall> products) {
        return products.stream()
                .sorted(Comparator.comparing((ReadCall product) -> !product.isReportable())
                        .thenComparing(Comparator.comparingInt(ReadCall::supportingReads).reversed())
                        .thenComparing(ReadCall::sizeSummary))
                .toList();
    }

    /**
     * What the reads show, strongest product first, e.g. {@code complete amplicon in 16
     * of 20 informative read(s) (80%), size(s) 100 bp; 597 more read(s) carry a primer
     * elsewhere in the genome}.
     */
    private static String describe(List<ReadCall> products) {
        List<String> parts = new ArrayList<>();
        for (ReadCall product : products) {
            StringBuilder part = new StringBuilder(parts.isEmpty() ? "" : "also ")
                    .append("complete amplicon in ").append(product.supportSummary())
                    .append(", size(s) ").append(product.sizeSummary()).append(" bp");
            if (!product.isReportable()) {
                part.append(parts.isEmpty() ? "; a read-level call needs " + product.thresholdSummary()
                        : ", not reported");
            }
            if (product.elsewhereReads() > 0) {
                part.append("; ").append(product.elsewhereReads())
                        .append(" more read(s) carry a primer elsewhere in the genome");
            }
            parts.add(part.toString());
        }
        return String.join("; ", parts);
    }

    /**
     * What the extended reads show, e.g. {@code reads extended through the baited reads
     * hold a product of 1074 bp (142 of 150 forward-seeded and 145 of 150 reverse-seeded
     * reads)}, followed by any product that too few extended reads hold.
     */
    private static String describe(ReadExtensionCaller.Outcome outcome) {
        List<ReadExtensionCaller.Product> called = outcome.called();
        List<ReadExtensionCaller.Product> withheld = outcome.withheld();
        List<ReadExtensionCaller.Product> tooFew = outcome.products().stream()
                .filter(product -> !product.isClosed())
                .toList();
        List<String> clauses = new ArrayList<>();
        if (!called.isEmpty()) {
            clauses.add(calledByExtension(called));
        }
        if (!withheld.isEmpty()) {
            clauses.add(withheldByPairs(withheld));
        }
        if (clauses.isEmpty()) {
            clauses.add("extending " + outcome.forwardSeeds() + " forward-seeded and " + outcome.reverseSeeds()
                    + " reverse-seeded read(s) through the baited reads, by up to " + outcome.reach()
                    + " bases each way, gave no product held by at least "
                    + ReadExtensionCaller.MIN_CLOSING_READS_PER_SIDE + " extended reads seeded from one primer");
        }
        if (!tooFew.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (ReadExtensionCaller.Product product : tooFew) {
                parts.add(product.sizeSummary() + " bp in " + product.supportSummary());
            }
            clauses.add("too few to report: " + String.join(", ", parts));
        }
        return String.join("; ", clauses);
    }

    /** The status for products that extended reads hold and read pairs do not confirm. */
    private static Status withheldStatus(List<ReadExtensionCaller.Product> withheld) {
        // The best-held product decides when several were withheld for different reasons.
        ReadExtensionCaller.Product main = withheld.stream()
                .max(Comparator.comparingInt(product -> product.forwardClosing() + product.reverseClosing()))
                .orElseThrow();
        return main.support().kind() == PairSupport.Kind.UNRESOLVED_REPEAT
                ? Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT : Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS;
    }

    /**
     * E.g. {@code extended reads hold a product of 727 bp (44 of 52 forward-seeded and 37 of
     * 46 reverse-seeded reads), not reported: the 300-base window at 191-490 is held by 1
     * read pair, against 14 for the median window}.
     */
    private static String withheldByPairs(List<ReadExtensionCaller.Product> products) {
        List<String> parts = new ArrayList<>();
        for (ReadExtensionCaller.Product product : products) {
            parts.add("extended reads hold a product of " + product.sizeSummary() + " bp (" + product.supportSummary()
                    + "), not reported: " + product.support().summary());
        }
        return String.join("; ", parts);
    }

    /** E.g. {@code reads extended through the baited reads hold a product of 1074 bp (…)}. */
    private static String calledByExtension(List<ReadExtensionCaller.Product> products) {
        List<String> parts = new ArrayList<>();
        for (ReadExtensionCaller.Product product : products) {
            parts.add("a product of " + product.sizeSummary() + " bp (" + product.supportSummary() + ")");
        }
        return "reads extended through the baited reads hold " + String.join(" and ", parts);
    }

    private static String join(Set<Integer> sizes) {
        return sizes.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    /**
     * By assay, the products of {@code rows} its primers make that are not its designed pair's: of one of
     * them alone ("its 500 bp product is of one primer alone"), or with another assay of its reaction.
     */
    private static Map<String, Set<String>> otherProducts(List<ReportRow> rows) {
        Map<String, Set<String>> others = new java.util.TreeMap<>();
        for (ReportRow row : rows) {
            if (row.pairing() == ReportRow.Pairing.DESIGNED) {
                continue;
            }
            String text = row.pairing() == ReportRow.Pairing.ONE_PRIMER
                    ? row.size() + " bp product is of one primer alone"
                    : row.size() + " bp " + row.assay() + " product is of two assays";
            for (String primer : List.of(row.forwardPrimer(), row.reversePrimer())) {
                PrimerId id = PrimerId.parse(primer);
                if (id != null) {
                    others.computeIfAbsent(id.assay(), ignored -> new java.util.TreeSet<>()).add(text);
                }
            }
        }
        return others;
    }

    private record AssemblyFinding(Assembly kind, String detail) {
    }

    private static AssemblyFinding classifyAssembly(PrimerId.Assay group, Sample sample,
                                                    Map<String, Integer> contigLengths) {
        List<BlastResult> forwardHits = sample.hits().of(group.forward());
        List<BlastResult> reverseHits = sample.hits().of(group.reverse());

        if (forwardHits.isEmpty() || reverseHits.isEmpty()) {
            List<String> absent = new ArrayList<>();
            if (forwardHits.isEmpty()) {
                absent.add(String.join(",", group.forward()));
            }
            if (reverseHits.isEmpty()) {
                absent.add(String.join(",", group.reverse()));
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
                int toEnd = SplitAcrossContigs.toEnd(hit, length);
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

    private static Set<String> contigsOf(List<BlastResult> hits) {
        Set<String> contigs = new HashSet<>();
        for (BlastResult hit : hits) {
            contigs.add(hit.queryID());
        }
        return contigs;
    }
}
