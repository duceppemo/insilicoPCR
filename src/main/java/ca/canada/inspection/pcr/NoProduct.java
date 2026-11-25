package ca.canada.inspection.pcr;

import ca.canada.inspection.util.Numbers;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.NearMiss;
import ca.canada.inspection.model.Sample;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Why an assay made no product in a sample (since 2.0): one oligo without a site the rules accept, its best site and
 * the rule that site fails; or, every oligo with a site, that none pair into a product. The rules are the same for
 * every assay ({@link TmGate}, the 3' rule): an assay that fails them in silico is said to, with the reason, and the
 * user may then lower the annealing temperature or raise the margin.
 *
 * @param sample    the sample
 * @param assay     the assay
 * @param oligo     the oligo without an accepted site (of a degenerate oligo, the variant that came closest), or
 *                  empty when every oligo has one
 * @param reason    why, in words
 * @param site      the oligo's best rejected site, {@code contig:start-end(strand)}; empty for none
 * @param siteTm    its Tm, °C; one that would round to {@code neededTm} or above is cut to 0.1 °C below it (49.98
 *                  is 49.9 when 50 is needed); {@code NaN} for none, or a site that fails the 3' rule
 * @param neededTm  the Tm a site of the assay needs, °C ({@link TmGate#minimumTm(String)}); {@code NaN} without the
 *                  Tm rule
 */
public record NoProduct(String sample, String assay, String oligo, String reason, String site, double siteTm,
                        double neededTm) {

    /** How the reason of an assay whose product is reported under another assay of its reaction starts. */
    private static final String REPORTED_UNDER = "its product is reported under ";

    /**
     * Whether the assay made its product, reported under another assay of its reaction ({@code mix=}): listed to
     * say where it is, not for a product missing.
     */
    public boolean reportedUnderAnother() {
        return reason.startsWith(REPORTED_UNDER);
    }

    /**
     * The assays of each sample without a product as designed in {@code rows}, with why: samples by name, assays in
     * the panel's order. A product of one of the assay's primers alone, or of two assays sharing a reaction, is not
     * the assay's, and is noted. An assay whose product is reported under another assay of its reaction
     * ({@code mix=}, both making it at the same place) is listed with that assay's name.
     */
    public static List<NoProduct> of(Map<String, Sample> samples, PrimerPanel primers, AmpliconOptions options,
                                     List<ReportRow> rows) {
        Set<List<String>> made = new HashSet<>();
        Set<List<String>> onePrimer = new HashSet<>();
        rows.forEach(row -> (row.pairing() == ReportRow.Pairing.DESIGNED ? made : onePrimer)
                .add(List.of(row.sample(), row.assay())));
        TmGate gate = options.tmGate();
        List<NoProduct> found = new ArrayList<>();
        for (Sample sample : new TreeMap<>(samples).values()) {
            Map<String, List<BlastResult>> accepted = sample.hits().byOligo();
            Map<String, NearMiss> misses = sample.hits().nearMisses();
            String where = sample.isFastq() ? " in the mini-assembly (the FASTQ diagnostics say what the reads show)" : "";
            Map<String, String> reportedUnder = Products.reportedUnder(sample, primers, options);
            primers.assays().forEach((assay, group) -> {
                List<String> key = List.of(sample.getName(), assay);
                if (made.contains(key)) {
                    return;
                }
                String under = reportedUnder.get(assay);
                if (under != null) {
                    found.add(new NoProduct(sample.getName(), assay, "", REPORTED_UNDER + under
                            + ": the two assays share a reaction (mix=) and make it at the same place", "", Double.NaN,
                            needed(gate, group.forward().getFirst())));
                    return;
                }
                String alone = onePrimer.contains(key) ? "; the report has a product of one of its primers alone" : "";
                List<List<String>> roles = new ArrayList<>(List.of(group.forward(), group.reverse()));
                if (group.hasProbe()) {
                    roles.add(group.probe());
                }
                boolean everyOligoHasASite = true;
                for (List<String> role : roles) {
                    if (role.stream().anyMatch(id -> !accepted.getOrDefault(id, List.of()).isEmpty())) {
                        continue;
                    }
                    everyOligoHasASite = false;
                    found.add(withoutSite(sample.getName(), assay, role, misses, primers, gate, options, where, alone));
                }
                if (everyOligoHasASite) {
                    found.add(new NoProduct(sample.getName(), assay, "", "every oligo has a site" + where
                            + ", but no forward and reverse site face each other on one sequence" + lengths(options)
                            + (group.hasProbe() ? " with a probe site between them" : "") + alone,
                            "", Double.NaN, needed(gate, group.forward().getFirst())));
                }
            });
        }
        return found;
    }

    /** {@code  within 5000 bp (--max-length)}: the product sizes the run allows; empty for any. */
    private static String lengths(AmpliconOptions options) {
        int min = options.minAmpliconLength();
        int max = options.maxAmpliconLength();
        if (min > 0 && max > 0) {
            return " " + min + " to " + max + " bp apart (--min-length, --max-length)";
        }
        if (min > 0) {
            return " at least " + min + " bp apart (--min-length)";
        }
        return max > 0 ? " within " + max + " bp (--max-length)" : "";
    }

    private static NoProduct withoutSite(String sample, String assay, List<String> variants, Map<String, NearMiss> misses,
                                         PrimerPanel primers, TmGate gate, AmpliconOptions options, String where,
                                         String alone) {
        String closest = variants.getFirst();
        NearMiss best = null;
        for (String id : variants) {
            NearMiss miss = misses.get(id);
            if (miss != null && (best == null || miss.closerThan(best))) {
                best = miss;
                closest = id;
            }
        }
        if (gate != null) {
            // Of a degenerate oligo, the variant that melts highest: "below on a perfect match" only when all are.
            String highest = variants.getFirst();
            double perfect = Double.NEGATIVE_INFINITY;
            for (String id : variants) {
                double tm = gate.perfectTm(primers.sequence(id).toUpperCase(Locale.ROOT));
                if (tm > perfect) {
                    perfect = tm;
                    highest = id;
                }
            }
            double minimum = gate.minimumTm(highest);
            if (perfect < minimum) {
                return new NoProduct(sample, assay, best == null ? highest : closest, String.format(Locale.ROOT,
                        "no site reaches %s °C%s, and its Tm on a perfect match is %.1f °C: it is unlikely to anneal"
                                + " at this temperature (lower the annealing temperature, --anneal-temp or the assay's"
                                + " ta=, or raise --tm-margin)%s", Numbers.plain(minimum), where, below(perfect, minimum),
                        alone),
                        best == null ? "" : site(best.hit()), best == null ? Double.NaN : below(best.tm(), minimum), minimum);
            }
        }
        if (best == null) {
            return new NoProduct(sample, assay, closest, "no site: BLAST found no match" + where + alone, "", Double.NaN,
                    needed(gate, closest));
        }
        BlastResult hit = best.hit();
        String binding = binding(hit);
        String reason = best.failsThreePrimeRule()
                ? "every site fails the 3' rule" + where + "; the best has a 3' penalty of " + best.threePrimePenalty()
                + ", more than " + options.maxThreePrimePenalty() + " (--3p-penalty)" + binding
                : String.format(Locale.ROOT, "no site reaches %s °C%s; the best melts at %.1f °C%s",
                Numbers.plain(gate.minimumTm(closest)), where, below(best.tm(), gate.minimumTm(closest)), binding);
        return new NoProduct(sample, assay, closest, reason + alone, site(hit), below(best.tm(), needed(gate, closest)),
                needed(gate, closest));
    }

    /** {@code  (2 mismatches, 5 unpaired 5' bases)}: how the site differs from the oligo; empty for none. */
    private static String binding(BlastResult hit) {
        List<String> parts = new ArrayList<>();
        int mismatches = hit.alignedMismatches();
        if (mismatches > 0) {
            parts.add(mismatches + (mismatches == 1 ? " mismatch or indel" : " mismatches and indels"));
        }
        int unpaired = PrimerId.isProbe(hit.subjectID()) ? 0 : hit.fivePrimeUnpairedBases();
        if (unpaired > 0) {
            parts.add(unpaired + " unpaired 5' base" + (unpaired == 1 ? "" : "s"));
        }
        int threePrimeOut = hit.threePrimeUncovered();
        if (threePrimeOut > 0) {
            parts.add(threePrimeOut + " 3' base" + (threePrimeOut == 1 ? "" : "s") + " not aligned");
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    /** {@code contig_7:101-122(+)}. */
    static String site(BlastResult hit) {
        return hit.queryID() + ":" + hit.qstart() + "-" + hit.qend() + "(" + (hit.isPlusStrand() ? "+" : "-") + ")";
    }

    /**
     * The Tm of a site below {@code minimum}, as the report shows it (to 0.1 °C): rounded down when rounding to the
     * nearest would reach the minimum it failed (49.98 °C is 49.9 when 50 is needed, not 50.0).
     */
    static double below(double tm, double minimum) {
        if (!Double.isFinite(tm) || !(tm < minimum) || Math.round(tm * 10) / 10.0 < minimum) {
            return tm;
        }
        return Math.floor(tm * 10) / 10;
    }

    private static double needed(TmGate gate, String oligoId) {
        return gate == null ? Double.NaN : gate.minimumTm(oligoId);
    }
}
