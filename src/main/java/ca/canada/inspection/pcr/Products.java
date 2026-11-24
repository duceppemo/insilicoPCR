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
    private record Key(String sampleName, PrimerPanel primers, AmpliconOptions options)
            implements Hits.Key<List<ReportRow>> {
    }

    /**
     * The sizes of the products {@code sample}'s assays make as designed (a forward with a reverse primer),
     * per assay; an assay without one is absent. A product of one primer alone, or of two assays, is no
     * sign of the assay's own product: the FASTQ steps and the split check ask about that one.
     */
    public static Map<String, Set<Integer>> sizes(Sample sample, PrimerPanel primers, AmpliconOptions options) {
        Map<String, Set<Integer>> sizes = new TreeMap<>();
        designed(sample, primers, options).forEach((assay, rows) ->
                rows.forEach(row -> sizes.computeIfAbsent(assay, ignored -> new TreeSet<>()).add(row.size())));
        return sizes;
    }

    /**
     * The rows of the products {@code sample}'s assays make as designed, per assay, as {@link #sizes} counts
     * them: for what each primer site shows besides the size.
     */
    public static Map<String, List<ReportRow>> designed(Sample sample, PrimerPanel primers, AmpliconOptions options) {
        Map<String, List<ReportRow>> designed = new TreeMap<>();
        for (ReportRow row : of(sample, primers, options)) {
            if (row.pairing() == ReportRow.Pairing.DESIGNED) {
                designed.computeIfAbsent(row.assay(), ignored -> new ArrayList<>()).add(row);
            }
        }
        return designed;
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
        HitIndex hits = new HitIndex(blastResults, sample.circles());
        // The panel's assays (by name) and their oligos (in primer-file order, the variants of a
        // degenerate oligo as expanded): the report lists products in this order on every run,
        // whatever order the hits are held in.
        Set<String> assaysWithProbe = primers.assaysWithProbe();
        List<ReportRow> found = new ArrayList<>();
        for (Map.Entry<String, PrimerId.Assay> assayEntry : primers.assays().entrySet()) {
            String assayName = assayEntry.getKey();
            PrimerId.Assay group = assayEntry.getValue();
            boolean probeRequired = assaysWithProbe.contains(assayName);
            List<ReportRow> assayRows = new ArrayList<>();
            // The oligos with a hit only: a pair of 256-variant primers made 65,536 pairs a sample to look at.
            List<String> forward = group.forward().stream().filter(hits::has).toList();
            List<String> reverse = group.reverse().stream().filter(hits::has).toList();
            for (String forwardPrimer : forward) {
                for (String reversePrimer : reverse) {
                    pairs(assayRows::add, sampleName, contigs, hits, assayName, group,
                            forwardPrimer, reversePrimer, options, probeRequired, false);
                }
            }
            // One primer alone, on both strands where a sequence repeats inverted: a product of the tube
            // as well, reported under its assay (ReportRow#onePrimer marks it).
            for (List<String> role : List.of(forward, reverse)) {
                for (String plusStrand : role) {
                    for (String minusStrand : role) {
                        pairs(assayRows::add, sampleName, contigs, hits, assayName, group,
                                plusStrand, minusStrand, options, probeRequired, true);
                    }
                }
            }
            found.addAll(bestPerProduct(assayRows));
        }
        crossAssayProducts(found::add, sampleName, contigs, hits, options, primers);
        oncePerReaction(found, primers.mixGroups()).forEach(rows);
    }

    /**
     * {@code rows} with each product of a multiplex reported once. Two assays of one reaction may repeat a
     * shared primer under each one's ID: the one molecule it makes was reported under each assay and again
     * as a product of the two. At one place (contig, start, end, size) in one reaction, the row kept is
     * the assay's designed product if one is, else a product of one primer, else of two assays; between
     * equals, the first assay by name. Rows of assays run on their own are all kept: each is its own tube.
     */
    private static List<ReportRow> oncePerReaction(List<ReportRow> rows, MixGroups mix) {
        if (mix.isEmpty()) {
            return rows;
        }
        java.util.Comparator<ReportRow> preference = java.util.Comparator.comparing(ReportRow::pairing)
                .thenComparing(ReportRow::assay);
        Map<List<Object>, ReportRow> kept = new HashMap<>();
        for (ReportRow row : rows) {
            List<Object> place = place(row, mix);
            if (place != null) {
                kept.merge(place, row, (one, other) -> preference.compare(other, one) < 0 ? other : one);
            }
        }
        List<ReportRow> once = new ArrayList<>();
        for (ReportRow row : rows) {
            List<Object> place = place(row, mix);
            if (place == null || kept.get(place) == row) {
                once.add(row);
            }
        }
        return once;
    }

    /** Where {@code row}'s product is in its reaction, or {@code null} for an assay run on its own. */
    private static List<Object> place(ReportRow row, MixGroups mix) {
        PrimerId primer = PrimerId.parse(row.forwardPrimer());
        String reaction = primer == null ? null : mix.groupOf(primer.assay());
        return reaction == null ? null : List.of(reaction, row.contig(), row.start(), row.end(), row.size());
    }

    /**
     * Products of the primers of two assays that share a reaction ({@code mix=} in the primer file),
     * reported under {@link MixGroups#crossAssayName}: any primer of one, either role, with any of the
     * other. Assays run on their own make none.
     */
    private static void crossAssayProducts(Consumer<ReportRow> rows, String sampleName, Map<String, String> contigs,
                                           HitIndex hits, AmpliconOptions options, PrimerPanel primers) {
        MixGroups mix = primers.mixGroups();
        Map<String, List<ReportRow>> byName = new java.util.LinkedHashMap<>();
        primers.assays().forEach((first, firstGroup) -> primers.assays().forEach((second, secondGroup) -> {
            if (!mix.shareAReaction(first, second)) {
                return;
            }
            String name = MixGroups.crossAssayName(first, second);
            List<ReportRow> named = byName.computeIfAbsent(name, ignored -> new ArrayList<>());
            for (String plusStrand : primersOf(firstGroup, hits)) {
                for (String minusStrand : primersOf(secondGroup, hits)) {
                    // No probe asked for: a product two assays make is in the tube whichever probe sees it.
                    pairs(named::add, sampleName, contigs, hits, name, firstGroup, plusStrand, minusStrand,
                            options, false, true);
                }
            }
        }));
        byName.values().forEach(named -> bestPerProduct(named).forEach(rows));
    }

    /** The primers of an assay, forward then reverse: not its probe, which primes nothing. */
    private static List<String> primersOf(PrimerId.Assay group, HitIndex hits) {
        List<String> primers = new ArrayList<>(group.forward());
        primers.addAll(group.reverse());
        primers.removeIf(primer -> !hits.has(primer));
        return primers;
    }

    /**
     * One row per product: of the rows that differ only by which primers or probe (variants
     * of a degenerate oligo, or several oligos of one role) made the same amplicon, the one
     * that binds best ({@link ReportRow#BEST_BOUND}, then {@link ReportRow#VARIANT_ORDER}), as
     * a call from reads shows. Products keep the order they were found in.
     */
    private static List<ReportRow> bestPerProduct(List<ReportRow> rows) {
        Map<List<Object>, ReportRow> best = new java.util.LinkedHashMap<>();
        java.util.Comparator<ReportRow> order = ReportRow.BEST_BOUND.thenComparing(ReportRow.VARIANT_ORDER)
                .thenComparing(ReportRow.PROBE_FIRST);
        for (ReportRow row : rows) {
            // The size too: the start is clamped to 1 at a contig start, where primers of different
            // lengths make products of different sizes at one location.
            best.merge(List.of(row.contig(), row.start(), row.end(), row.strand(), row.size()), row,
                    (kept, other) -> order.compare(other, kept) < 0 ? other : kept);
        }
        return List.copyOf(best.values());
    }

    /**
     * Accepted hits of one sample, by oligo and by the contig they sit on. Primers only
     * pair, and probes only count, on one contig, so looking hits up per contig keeps the
     * work proportional to the hits rather than to every forward/reverse combination in
     * the sample (millions of reads each carry a hit or two in a read-level search).
     */
    private static final class HitIndex {
        private final Map<String, List<BlastResult>> byOligo;
        private final Map<String, Map<String, OnContig>> byOligoAndContig = new HashMap<>();
        private final Map<String, Integer> circles;

        HitIndex(Map<String, List<BlastResult>> byOligo, Map<String, Integer> circles) {
            this.byOligo = byOligo;
            this.circles = circles;
        }

        /** The length of {@code contig} as a circle, or 0 for a linear sequence. */
        int circle(String contig) {
            return circles.getOrDefault(contig, 0);
        }

        List<BlastResult> of(String oligoId) {
            return byOligo.getOrDefault(oligoId, List.of());
        }

        /** Whether {@code oligoId} has a hit at all: of a degenerate primer's variants, most have none. */
        boolean has(String oligoId) {
            return !of(oligoId).isEmpty();
        }

        /** Hits of {@code oligoId} on {@code contig}, in the order BLAST reported them. */
        List<BlastResult> on(String oligoId, String contig) {
            OnContig hits = onContig(oligoId, contig);
            return hits == null ? List.of() : hits.inOrder();
        }

        /**
         * Hits of {@code oligoId} on {@code contig} starting from {@code from} to {@code to}, in the order
         * BLAST reported them: a primer pairs only with hits within a product's length of it, and on a
         * chromosome with tens of thousands of hits, comparing each with every other took seconds a sample.
         */
        List<BlastResult> near(String oligoId, String contig, long from, long to) {
            OnContig hits = onContig(oligoId, contig);
            return hits == null ? List.of() : hits.between(from, to);
        }

        private OnContig onContig(String oligoId, String contig) {
            return byOligoAndContig.computeIfAbsent(oligoId, id -> {
                Map<String, List<BlastResult>> byContig = new HashMap<>();
                for (BlastResult hit : of(id)) {
                    byContig.computeIfAbsent(hit.queryID(), ignored -> new ArrayList<>()).add(hit);
                }
                Map<String, OnContig> indexed = new HashMap<>();
                byContig.forEach((name, list) -> indexed.put(name, new OnContig(list)));
                return indexed;
            }).get(contig);
        }
    }

    /** One oligo's hits on one contig: in BLAST's order, and by start for the hits near a place. */
    private static final class OnContig {
        private final List<BlastResult> inOrder;
        /** Indices into {@link #inOrder}, by start. */
        private final int[] byStart;

        OnContig(List<BlastResult> inOrder) {
            this.inOrder = List.copyOf(inOrder);
            this.byStart = java.util.stream.IntStream.range(0, inOrder.size()).boxed()
                    .sorted(java.util.Comparator.comparingInt(i -> inOrder.get(i).qstart()))
                    .mapToInt(Integer::intValue).toArray();
        }

        List<BlastResult> inOrder() {
            return inOrder;
        }

        List<BlastResult> between(long from, long to) {
            int low = 0;
            int high = byStart.length;
            while (low < high) { // the first hit starting at from or after
                int middle = (low + high) >>> 1;
                if (inOrder.get(byStart[middle]).qstart() < from) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            List<Integer> found = new ArrayList<>();
            for (int i = low; i < byStart.length && inOrder.get(byStart[i]).qstart() <= to; i++) {
                found.add(byStart[i]);
            }
            found.sort(null); // back in BLAST's order: the report lists products in it
            List<BlastResult> near = new ArrayList<>(found.size());
            found.forEach(index -> near.add(inOrder.get(index)));
            return near;
        }
    }

    /** How far beyond the longest product a partner's start may lie: its length and 5' bases left unaligned. */
    private static final int PARTNER_SLACK = 1_000;

    private static void pairs(Consumer<ReportRow> rows, String sampleName, Map<String, String> contigs,
                                               HitIndex hits,
                                               String assayName, PrimerId.Assay group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options, boolean probeRequired,
                                               boolean plusStrandFirst) {
        for (BlastResult forwardHit : hits.of(forwardPrimer)) {
            // Two primers of one role, or of two assays: each product is found once, from the primer on
            // its plus strand, not again with the two swapped.
            if (plusStrandFirst && !forwardHit.isPlusStrand()) {
                continue;
            }
            int longest = options.maxAmpliconLength();
            int circle = hits.circle(forwardHit.queryID());
            // On a circle, a partner may be at the other end, across the origin.
            List<BlastResult> partners = longest > 0 && circle == 0
                    ? hits.near(reversePrimer, forwardHit.queryID(), (long) forwardHit.qstart() - longest - PARTNER_SLACK,
                    (long) forwardHit.qstart() + longest + PARTNER_SLACK)
                    : hits.on(reversePrimer, forwardHit.queryID());
            for (BlastResult reverseHit : partners) {
                Amplicon amplicon = AmpliconCaller.call(forwardHit, reverseHit, options, circle).orElse(null);
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
                // A probe across a circle's origin ends on the circle's start, as the product does; one whose 5'
                // bases BLAST left unaligned lie before the origin starts at the circle's end (it read -2).
                int circle = amplicon.circle();
                int shownStart = ca.canada.inspection.util.Circles.onCircle(probeStart, circle);
                int shownEnd = ca.canada.inspection.util.Circles.onCircle(probeEnd, circle);
                rows.accept(pair.withProbe(new ReportRow.Probe(probeId, shownStart, shownEnd,
                        probeHit.alignedMismatches(), probeEnd - probeStart + 1)));
            }
        }
    }

}
