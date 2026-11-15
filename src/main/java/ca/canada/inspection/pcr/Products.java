package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Hits;
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
        return of(sample.getName(), sample.hits(), primers, options);
    }

    /**
     * The products {@code hits} hold, the rows naming the sample {@code sampleName}: a sample's
     * contigs, or the reads a FASTQ-mode search looks at. Worked out once per name, primer panel
     * and options while the hits stay as they are (the report, the FASTQ steps and the
     * diagnostics all ask); the list cannot be changed.
     */
    public static List<ReportRow> of(String sampleName, Hits hits, PrimerPanel primers, AmpliconOptions options) {
        return hits.derived(new Key(sampleName, primers, options), () -> {
            List<ReportRow> rows = new ArrayList<>();
            // Probe requirement is decided per assay from the primer FILE, so standard PCR and qPCR
            // assays can be mixed in one run: an assay whose probe is defined but never hits stays
            // negative, while a probe-less assay is reported on its primer pair alone.
            find(rows::add, sampleName, hits, options, primers);
            return List.copyOf(rows);
        });
    }

    /** What a sample's products depend on besides its hits; the panel by identity, one per run. */
    private record Key(String sampleName, PrimerPanel primers, AmpliconOptions options) {
    }

    /** The product sizes of {@code sample}, per assay; an assay without a product is absent. */
    public static Map<String, Set<Integer>> sizes(Sample sample, PrimerPanel primers, AmpliconOptions options) {
        Map<String, Set<Integer>> sizes = new TreeMap<>();
        for (ReportRow row : of(sample, primers, options)) {
            sizes.computeIfAbsent(row.assay(), ignored -> new TreeSet<>()).add(row.size());
        }
        return sizes;
    }

    /**
     * The rows of a run's report, in its order: each sample's products, then its rows from
     * {@code extraRows} (the products FASTQ mode calls on reads rather than contigs), samples
     * in alphabetical order. What the report file, the HTML report and the gel show.
     */
    public static List<ReportRow> ofRun(Map<String, Sample> samples, PrimerPanel primers, AmpliconOptions options,
                                        Map<String, List<ReportRow>> extraRows) {
        List<ReportRow> rows = new ArrayList<>();
        for (Sample sample : new TreeMap<>(samples).values()) {
            rows.addAll(of(sample, primers, options));
            rows.addAll(extraRows.getOrDefault(sample.getName(), List.of()));
        }
        return List.copyOf(rows);
    }

    private static void find(Consumer<ReportRow> rows, String sampleName, Hits sample,
                             AmpliconOptions options, PrimerPanel primers) {
        Map<String, List<BlastResult>> blastResults = sample.byOligo();
        if (blastResults.isEmpty()) {
            return;
        }

        // Fetched once: both accessors copy, and a FASTQ sample searched at read level
        // has one "contig" per read.
        Map<String, String> contigs = sample.descriptions();
        HitIndex hits = new HitIndex(blastResults);
        // The panel's assays (by name) and their oligos (in primer-file order, the variants of a
        // degenerate oligo as expanded): the report lists products in this order on every run,
        // whatever order the hits are held in.
        Set<String> assaysWithProbe = primers.assaysWithProbe();
        for (Map.Entry<String, PrimerId.Assay> assayEntry : primers.completeAssays().entrySet()) {
            String assayName = assayEntry.getKey();
            PrimerId.Assay group = assayEntry.getValue();
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
                ReportRow row = new ReportRow(sampleName, assayName,
                        new ReportRow.Site(amplicon.start(), amplicon.end(), amplicon.size(), amplicon.contig(),
                                contigs.getOrDefault(amplicon.contig(), ""), amplicon.forwardStrand()),
                        binding(forwardPrimer, forwardHit, options), binding(reversePrimer, reverseHit, options),
                        null, ReportRow.Source.ASSEMBLY);
                if (probeRequired) {
                    probeRows(rows, hits, group, amplicon, row);
                } else {
                    rows.accept(row);
                }
            }
        }
    }

    private static ReportRow.Binding binding(String primer, BlastResult hit, AmpliconOptions options) {
        return new ReportRow.Binding(primer, hit.primerMismatches(), hit.threePrimeMismatches(options.threePrimeWindow()),
                hit.fivePrimeUnpairedBases());
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
                        probeHit.alignedMismatches())));
            }
        }
    }

}
