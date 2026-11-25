package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.thermo.Thal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts a primer or probe site by its melting temperature (since 2.0): the Tm of the oligo on the template under
 * it, computed as Primer3's {@code ntthal} does ({@link Thal}, any alignment), must reach the annealing temperature
 * less a margin. One rule for every oligo of every assay: it replaces the mismatch counts of 1.x ({@code -m},
 * {@code --probe-mismatches}, {@code --5p-truncation}). A primer must also pass the 3' rule ({@code --3p-window},
 * {@code --3p-penalty}): a stable duplex with a mismatched 3' end extends poorly, and Tm does not model that.
 *
 * <p>The template under an oligo is the site BLAST aligned, with the bases its alignment left out on either side
 * (as far as the sequence goes): {@code thal} finds its own alignment there, mismatches, bulges and dangling ends
 * included. Oligo bases past the end of a contig or read have no template: they lower the Tm, instead of counting
 * as free bases.</p>
 *
 * <p>An assay whose primer header gives {@code ta=} has its sites judged at that annealing temperature; the others at
 * {@code annealTemp}. The margin and the reaction are the run's.</p>
 *
 * @param annealTemp   annealing temperature of the reaction, °C ({@code --anneal-temp}), for the assays that give none
 * @param margin       how far below {@code annealTemp} a site's Tm may be and the oligo still anneal there, °C
 *                     ({@code --tm-margin})
 * @param monovalentMm monovalent cations, mM ({@code --na}; Primer3's {@code -mv})
 * @param divalentMm   Mg²⁺, mM ({@code --mg}; {@code -dv})
 * @param dntpMm       dNTP, mM ({@code --dntp}; {@code -n})
 * @param oligoNm      oligo concentration, nM ({@code --oligo-conc}; {@code -d})
 * @param assayTemps   the annealing temperatures the primer file declares ({@code ta=}), by assay
 */
public record TmGate(double annealTemp, double margin, double monovalentMm, double divalentMm, double dntpMm,
                     double oligoNm, AnnealTemps assayTemps) {

    public static final double DEFAULT_ANNEAL_TEMP = 60;
    public static final double DEFAULT_MARGIN = 5;
    public static final double DEFAULT_MONOVALENT_MM = 50;
    public static final double DEFAULT_DIVALENT_MM = 3;
    public static final double DEFAULT_DNTP_MM = 0.8;
    public static final double DEFAULT_OLIGO_NM = 250;

    /** The run's gate when nothing is set: every setting at its default. */
    public static final TmGate DEFAULT = at(DEFAULT_ANNEAL_TEMP);

    /** Sites whose Tm is kept: identical sites recur in every read over a locus. */
    private static final int CACHE_LIMIT = 200_000;
    private static final Map<String, Double> CACHE = new ConcurrentHashMap<>();

    public TmGate {
        if (!Double.isFinite(annealTemp) || annealTemp < 0 || annealTemp > 100) {
            throw new IllegalArgumentException("--anneal-temp must be 0 to 100 °C, got: " + annealTemp);
        }
        requireNonNegative(margin, "--tm-margin");
        requireNonNegative(monovalentMm, "--na");
        requireNonNegative(divalentMm, "--mg");
        requireNonNegative(dntpMm, "--dntp");
        if (!(oligoNm > 0) || !Double.isFinite(oligoNm)) {
            throw new IllegalArgumentException("--oligo-conc must be greater than 0 nM, got: " + oligoNm);
        }
        saltError(monovalentMm, divalentMm, dntpMm).ifPresent(error -> {
            throw new IllegalArgumentException(error);
        });
        assayTemps = assayTemps == null ? AnnealTemps.NONE : assayTemps;
    }

    /**
     * Why a reaction of these salts has no Tm, or empty: the Tm's salt correction takes the log of the monovalent
     * cations plus the Mg²⁺ the dNTP do not bind, and with neither every oligo would melt at -273 °C.
     */
    public static java.util.Optional<String> saltError(double monovalentMm, double divalentMm, double dntpMm) {
        if (monovalentMm > 0 || divalentMm > dntpMm) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("--na and --mg leave no cations for a Tm: give --na above 0, or --mg above"
                + " --dntp (the dNTP bind their own amount of Mg2+); got " + number(monovalentMm) + " mM Na+, "
                + number(divalentMm) + " mM Mg2+, " + number(dntpMm) + " mM dNTP");
    }

    /** Every assay at {@code annealTemp}. */
    public TmGate(double annealTemp, double margin, double monovalentMm, double divalentMm, double dntpMm,
                  double oligoNm) {
        this(annealTemp, margin, monovalentMm, divalentMm, dntpMm, oligoNm, AnnealTemps.NONE);
    }

    /** This gate, judging the sites of each assay of {@code temps} at its own annealing temperature. */
    public TmGate withAssayTemps(AnnealTemps temps) {
        return new TmGate(annealTemp, margin, monovalentMm, divalentMm, dntpMm, oligoNm, temps);
    }

    /** The annealing temperature the sites of {@code oligoId} are judged at, °C: its assay's {@code ta=}, or the run's. */
    public double annealTempOf(String oligoId) {
        if (assayTemps.isEmpty() || oligoId == null) {
            return annealTemp;
        }
        PrimerId parsed = PrimerId.parse(oligoId);
        Double own = assayTemps.of(parsed == null ? oligoId : parsed.assay());
        return own == null ? annealTemp : own;
    }

    /** The lowest Tm a site of {@code oligoId} may have, °C: its annealing temperature less the margin. */
    public double minimumTm(String oligoId) {
        return annealTempOf(oligoId) - margin;
    }

    /** The gate at {@code annealTemp}, every other setting at its default. */
    public static TmGate at(double annealTemp) {
        return new TmGate(annealTemp, DEFAULT_MARGIN, DEFAULT_MONOVALENT_MM, DEFAULT_DIVALENT_MM, DEFAULT_DNTP_MM,
                DEFAULT_OLIGO_NM);
    }

    /** The lowest Tm a site may have, °C, for the assays at the run's annealing temperature: it less the margin. */
    public double minimumTm() {
        return annealTemp - margin;
    }

    /**
     * Whether the oligo of {@code hit} anneals to its site on {@code template}: its Tm there reaches
     * {@link #minimumTm(String)}.
     */
    public boolean accepts(BlastResult hit, TrimmedEnds.Template template) {
        return siteTm(hit, template) >= minimumTm(hit.subjectID());
    }

    /**
     * The ID the primer file gives {@code id}: {@code X-F} for {@code X-F_3}, a sequence of degenerate {@code X-F}, when
     * {@code X-F_0} is one of {@code ids} and {@code X-F} is not.
     */
    static String writtenId(String id, java.util.Set<String> ids) {
        int underscore = id.lastIndexOf('_');
        if (underscore <= 0 || underscore == id.length() - 1
                || !id.substring(underscore + 1).chars().allMatch(Character::isDigit)) {
            return id;
        }
        String written = id.substring(0, underscore);
        return !ids.contains(written) && ids.contains(written + "_0") ? written : id;
    }

    /**
     * The Tm of {@code oligo} (upper case) on its perfect complement, °C, under these conditions. Not a ceiling: a
     * site with a mismatched or unpaired end base can melt a little higher, its dangling end stacking on the duplex.
     */
    public double perfectTm(String oligo) {
        return tm(oligo, Dna.reverseComplement(oligo));
    }

    /**
     * For the run's log: each oligo's Tm on its perfect complement. One below {@link #minimumTm} is unlikely to anneal
     * anywhere at this temperature. A degenerate oligo is one line, its variants' range: there can be thousands.
     */
    public java.util.List<String> oligoLines(PrimerPanel primers) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        boolean own = !assayTemps.isEmpty();
        lines.add("Oligo Tm on the perfect complement (a site's Tm must reach " + (own
                ? "its assay's annealing temperature less " + number(margin) + " °C"
                : number(minimumTm()) + " °C") + "):");
        Map<String, double[]> ranges = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        primers.sequences().forEach((id, sequence) -> {
            double tm = perfectTm(sequence.toUpperCase(java.util.Locale.ROOT));
            String oligo = writtenId(id, primers.sequences().keySet());
            ranges.merge(oligo, new double[]{tm, tm}, (a, b) -> new double[]{Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            counts.merge(oligo, 1, Integer::sum);
        });
        ranges.forEach((oligo, range) -> {
            double minimum = minimumTm(oligo);
            int count = counts.get(oligo);
            String tm = count == 1 ? String.format(java.util.Locale.ROOT, "%.1f °C", range[0])
                    : String.format(java.util.Locale.ROOT, "%.1f to %.1f °C (%d sequences)", range[0], range[1], count);
            String verdict = range[1] < minimum ? ": below the minimum, unlikely to anneal anywhere"
                    : range[0] < minimum ? ": some of its sequences below the minimum" : "";
            lines.add("  " + oligo + " " + tm + (own ? " (needs " + number(minimum) + ")" : "") + verdict);
        });
        return lines;
    }

    /**
     * The Tm of the oligo of {@code hit} on the template under it, °C: 0 when they form no duplex, and
     * {@link Double#NEGATIVE_INFINITY} when the oligo is unknown.
     *
     * @param template the sequence the hit lies on, or {@code null}: then only the bases BLAST aligned are known
     */
    public double siteTm(BlastResult hit, TrimmedEnds.Template template) {
        String oligo = hit.primerSeq();
        if (oligo == null || oligo.isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        return tm(oligo.toUpperCase(java.util.Locale.ROOT), strandUnder(hit, template));
    }

    /**
     * The Tm of {@code oligo} on {@code strand}, both 5' to 3', under these conditions. Of an oligo longer than
     * {@link Thal#MAX_ALIGN} (60) bases, its 3'-most 60 on the template under them: {@code thal} aligns no longer pair,
     * and the 3' end is what primes; 60 bases melt far above any annealing temperature.
     */
    double tm(String oligo, String strand) {
        if (oligo.length() > Thal.MAX_ALIGN && strand.length() > Thal.MAX_ALIGN) {
            int cut = oligo.length() - Thal.MAX_ALIGN;
            oligo = oligo.substring(cut);
            // The oligo's 5' end pairs with the strand's 3' end: drop as many bases there, keeping any indel slack.
            strand = strand.substring(0, Math.max(Thal.MAX_ALIGN, strand.length() - cut));
        }
        String key = oligo + '\u0001' + strand + '\u0001' + monovalentMm + '\u0001' + divalentMm + '\u0001' + dntpMm
                + '\u0001' + oligoNm;
        Double cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Thal.Result result = Thal.thal(oligo, strand, new Thal.Args(Thal.Type.ANY, Thal.MAX_LOOP, monovalentMm,
                divalentMm, dntpMm, oligoNm, Thal.TEMP_KELVIN));
        double tm = result.found() ? result.tm() : Math.min(0, result.tm());
        if (CACHE.size() >= CACHE_LIMIT) {
            CACHE.clear();
        }
        CACHE.put(key, tm);
        return tm;
    }

    /**
     * The template strand the oligo of {@code hit} anneals to, 5' to 3': the complement of its site, the bases BLAST
     * aligned and those it left out on either side that the sequence has.
     */
    static String strandUnder(BlastResult hit, TrimmedEnds.Template template) {
        boolean plus = hit.isPlusStrand();
        int leftOut = plus ? hit.fivePrimeUncovered() : hit.threePrimeUncovered();
        int rightOut = plus ? hit.threePrimeUncovered() : hit.fivePrimeUncovered();
        StringBuilder site = new StringBuilder();
        if (template != null) {
            int first = hit.qstart();
            while (first > hit.qstart() - leftOut && template.has(first - 1)) {
                first--;
            }
            for (int position = first; position < hit.qstart(); position++) {
                site.append(template.base(position));
            }
        }
        if (hit.qseq() != null) {
            for (int i = 0; i < hit.qseq().length(); i++) {
                char base = Character.toUpperCase(hit.qseq().charAt(i));
                if (base != '-') {
                    site.append(base == 'U' ? 'T' : base);
                }
            }
        } else if (template != null) {
            for (int position = hit.qstart(); position <= hit.qend() && template.has(position); position++) {
                site.append(template.base(position));
            }
        }
        if (template != null) {
            for (int position = hit.qend() + 1; position <= hit.qend() + rightOut && template.has(position);
                 position++) {
                site.append(template.base(position));
            }
        }
        // On the plus strand the oligo reads as the site: it anneals to the minus strand, the site's reverse
        // complement. On the minus strand it anneals to the plus strand, the site as read.
        return plus ? Dna.reverseComplement(site.toString()) : site.toString();
    }

    /** As the run's settings list it. */
    public String describe() {
        return String.format(java.util.Locale.ROOT, "site Tm at least %s °C (%s - %s); %s mM Na+, %s mM Mg2+,"
                        + " %s mM dNTP, %s nM oligo", number(minimumTm()), number(annealTemp), number(margin),
                number(monovalentMm), number(divalentMm), number(dntpMm), number(oligoNm))
                + (assayTemps.isEmpty() ? "" : "; assays at their own annealing temperature (ta=): "
                + assayTemps.summary() + " °C");
    }

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static void requireNonNegative(double value, String label) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be >= 0, got: " + value);
        }
    }
}
