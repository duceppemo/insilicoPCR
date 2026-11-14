package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconCaller.Amplicon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * The products of a sample: its accepted primer hits paired into amplicons ({@link AmpliconCaller}),
 * an assay with a probe in the primer file only where its probe binds within the amplicon. The same
 * rules for an assembly's contigs, a read-level search's reads and extended reads; the report writes
 * what this finds.
 */
public final class Products {

    private Products() {
    }

    /** The products of {@code sample}, in report order (by assay, then primer pair, then hit). */
    public static List<ReportRow> of(Sample sample, PrimerPanel primers, AmpliconOptions options) {
        return of(sample.getName(), sample, primers, options);
    }

    /** As {@link #of(Sample, PrimerPanel, AmpliconOptions)}, the rows naming the sample {@code sampleName}. */
    public static List<ReportRow> of(String sampleName, Sample sample, PrimerPanel primers, AmpliconOptions options) {
        List<ReportRow> rows = new ArrayList<>();
        // Probe requirement is decided per assay from the primer FILE, so standard PCR and qPCR
        // assays can be mixed in one run: an assay whose probe is defined but never hits stays
        // negative, while a probe-less assay is reported on its primer pair alone.
        find(rows::add, sampleName, sample, options, primers.assaysWithProbe());
        return rows;
    }

    /** The product sizes of {@code sample}, per assay; an assay without a product is absent. */
    public static Map<String, Set<Integer>> sizes(Sample sample, PrimerPanel primers, AmpliconOptions options) {
        Map<String, Set<Integer>> sizes = new TreeMap<>();
        for (ReportRow row : of(sample, primers, options)) {
            sizes.computeIfAbsent(row.assay(), ignored -> new TreeSet<>()).add(row.size());
        }
        return sizes;
    }

    private static void find(Consumer<ReportRow> rows, String sampleName, Sample sample,
                             AmpliconOptions options, Set<String> assaysWithProbe) {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        if (blastResults.isEmpty()) {
            return;
        }

        // Fetched once: both accessors copy, and a FASTQ sample searched at read level
        // has one "contig" per read.
        Map<String, String> contigs = sample.contigs();
        HitIndex hits = new HitIndex(blastResults);
        Map<String, PrimerId.Assay> assays = PrimerId.byAssay(blastResults.keySet());
        for (Map.Entry<String, PrimerId.Assay> assayEntry : assays.entrySet()) {
            String assayName = assayEntry.getKey();
            PrimerId.Assay group = assayEntry.getValue();
            if (!group.isComplete()) {
                continue;
            }
            boolean probeRequired = assaysWithProbe.contains(assayName);
            for (String forwardPrimer : group.forward()) {
                for (String reversePrimer : group.reverse()) {
                    pairs(rows, sampleName, contigs, hits, assayName, group,
                            forwardPrimer, reversePrimer, options, probeRequired);
                }
            }
        }
    }

    /**
     * Accepted hits of one sample, by oligo and by the contig they sit on. Primers only
     * pair, and probes only count, on one contig, so looking hits up per contig keeps the
     * work proportional to the hits rather than to every forward/reverse combination in
     * the sample (millions of reads each carry a hit or two in a read-level search).
     */
    private static final class HitIndex {
        private final Map<String, List<BlastResult>> byOligo;
        private final Map<String, Map<String, List<BlastResult>>> byOligoAndContig = new HashMap<>();

        HitIndex(Map<String, List<BlastResult>> byOligo) {
            this.byOligo = byOligo;
        }

        List<BlastResult> of(String oligoId) {
            return byOligo.getOrDefault(oligoId, List.of());
        }

        /** Hits of {@code oligoId} on {@code contig}, in the order BLAST reported them. */
        List<BlastResult> on(String oligoId, String contig) {
            return byOligoAndContig.computeIfAbsent(oligoId, id -> {
                Map<String, List<BlastResult>> byContig = new HashMap<>();
                for (BlastResult hit : of(id)) {
                    byContig.computeIfAbsent(hit.queryID(), ignored -> new ArrayList<>()).add(hit);
                }
                return byContig;
            }).getOrDefault(contig, List.of());
        }
    }

    private static void pairs(Consumer<ReportRow> rows, String sampleName, Map<String, String> contigs,
                                               HitIndex hits,
                                               String assayName, PrimerId.Assay group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options, boolean probeRequired) {
        for (BlastResult forwardHit : hits.of(forwardPrimer)) {
            for (BlastResult reverseHit : hits.on(reversePrimer, forwardHit.queryID())) {
                Amplicon amplicon = AmpliconCaller.call(forwardHit, reverseHit, options).orElse(null);
                if (amplicon == null) {
                    continue;
                }
                ReportRow row = new ReportRow(sampleName, assayName, amplicon.start(), amplicon.end(), amplicon.size(),
                        amplicon.contig(), contigs.getOrDefault(amplicon.contig(), ""), forwardPrimer, reversePrimer,
                        effectiveMismatches(forwardHit), effectiveMismatches(reverseHit),
                        forwardHit.threePrimeMismatches(options.threePrimeWindow()),
                        reverseHit.threePrimeMismatches(options.threePrimeWindow()), amplicon.forwardStrand(), null,
                        forwardHit.fivePrimeUnpairedBases(), reverseHit.fivePrimeUnpairedBases());
                if (probeRequired) {
                    probeRows(rows, hits, group, amplicon, row);
                } else {
                    rows.accept(row);
                }
            }
        }
    }

    /** Mismatches as counted against the -m budget: substitutions, indels, and trimmed 3' bases. */
    private static int effectiveMismatches(BlastResult hit) {
        return hit.mismatch() + hit.gaps() + hit.threePrimeUncovered();
    }

    /** One row per probe hit within the amplicon; none when the probe does not bind there. */
    private static void probeRows(Consumer<ReportRow> rows, HitIndex hits, PrimerId.Assay group, Amplicon amplicon,
                                       ReportRow pair) {
        for (String probeId : group.probe()) {
            for (BlastResult probeHit : hits.on(probeId, amplicon.contig())) {
                int probeStart = Math.min(probeHit.qstart(), probeHit.qend());
                int probeEnd = Math.max(probeHit.qstart(), probeHit.qend());
                // The probe must anneal within the amplified product.
                if (!amplicon.contains(probeStart, probeEnd)) {
                    continue;
                }
                rows.accept(pair.withProbe(new ReportRow.Probe(probeId, probeStart, probeEnd,
                        probeHit.mismatch() + probeHit.gaps())));
            }
        }
    }

}
