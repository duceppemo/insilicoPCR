package ca.canada.inspection.pcr;

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
 * @param siteTm    its Tm, °C; {@code NaN} for none, or a site that fails the 3' rule
 * @param neededTm  the Tm a site needs, °C ({@link TmGate#minimumTm}); {@code NaN} without the Tm rule
 */
public record NoProduct(String sample, String assay, String oligo, String reason, String site, double siteTm,
                        double neededTm) {

    /**
     * The assays of each sample that have no row in {@code rows} (products of the assay as designed or of one of its
     * primers; a product of two assays sharing a reaction is reported under both names joined, and does not count),
     * with why: samples by name, assays in the panel's order.
     */
    public static List<NoProduct> of(Map<String, Sample> samples, PrimerPanel primers, AmpliconOptions options,
                                     List<ReportRow> rows) {
        Set<List<String>> made = new HashSet<>();
        rows.forEach(row -> made.add(List.of(row.sample(), row.assay())));
        TmGate gate = options.tmGate();
        List<NoProduct> found = new ArrayList<>();
        for (Sample sample : new TreeMap<>(samples).values()) {
            Map<String, List<BlastResult>> accepted = sample.hits().byOligo();
            Map<String, NearMiss> misses = sample.hits().nearMisses();
            String where = sample.isFastq() ? " in the mini-assembly (the FASTQ diagnostics say what the reads show)" : "";
            primers.assays().forEach((assay, group) -> {
                if (made.contains(List.of(sample.getName(), assay))) {
                    return;
                }
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
                    found.add(withoutSite(sample.getName(), assay, role, misses, primers, gate, options, where));
                }
                if (everyOligoHasASite) {
                    found.add(new NoProduct(sample.getName(), assay, "", "every oligo has a site" + where
                            + ", but no forward and reverse site face each other on one sequence"
                            + (options.maxAmpliconLength() > 0 ? " within " + options.maxAmpliconLength() + " bp"
                            + " (--max-length)" : "") + (group.hasProbe() ? " with a probe site between them" : ""),
                            "", Double.NaN, needed(gate)));
                }
            });
        }
        return found;
    }

    private static NoProduct withoutSite(String sample, String assay, List<String> variants, Map<String, NearMiss> misses,
                                         PrimerPanel primers, TmGate gate, AmpliconOptions options, String where) {
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
            double perfect = gate.perfectTm(primers.sequence(closest).toUpperCase(Locale.ROOT));
            if (perfect < gate.minimumTm()) {
                return new NoProduct(sample, assay, closest, String.format(Locale.ROOT, "its Tm on a perfect match is"
                        + " %.1f °C, below the %s °C a site needs: it anneals nowhere at this temperature (lower"
                        + " --anneal-temp or raise --tm-margin)", perfect, number(gate.minimumTm())),
                        best == null ? "" : site(best.hit()), best == null ? Double.NaN : best.tm(), gate.minimumTm());
            }
        }
        if (best == null) {
            return new NoProduct(sample, assay, closest, "no site: BLAST found no match" + where, "", Double.NaN,
                    needed(gate));
        }
        BlastResult hit = best.hit();
        String binding = binding(hit);
        String reason = best.failsThreePrimeRule()
                ? "every site fails the 3' rule" + where + "; the best has a 3' penalty of " + best.threePrimePenalty()
                + ", more than " + options.maxThreePrimePenalty() + " (--3p-penalty)" + binding
                : String.format(Locale.ROOT, "no site reaches %s °C%s; the best melts at %.1f °C%s",
                number(gate.minimumTm()), where, best.tm(), binding);
        return new NoProduct(sample, assay, closest, reason, site(hit), best.tm(), needed(gate));
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

    private static double needed(TmGate gate) {
        return gate == null ? Double.NaN : gate.minimumTm();
    }

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
