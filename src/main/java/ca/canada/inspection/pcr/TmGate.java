package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.thermo.Thal;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accepts a primer or probe site by its melting temperature (since 2.0): the Tm of the oligo on the template under it,
 * computed as Primer3's {@code ntthal} does ({@link Thal}, any alignment), must reach the annealing temperature less
 * a margin. With the gate on, it decides in place of the counts of {@code -m}, {@code --probe-mismatches} and
 * {@code --5p-truncation}; a primer must still pass the 3' rule ({@code --3p-window}, {@code --3p-penalty}): a
 * stable duplex with a mismatched 3' end extends poorly, and Tm does not model that.
 *
 * <p>The template under an oligo is the site BLAST aligned, with the bases its alignment left out on either side
 * (as far as the sequence goes): {@code thal} finds its own alignment there, mismatches, bulges and dangling ends
 * included. Oligo bases past the end of a contig or read have no template: they lower the Tm, instead of counting
 * as free bases.</p>
 *
 * @param annealTemp   annealing temperature of the reaction, °C ({@code --anneal-temp})
 * @param margin       how far below {@code annealTemp} a site's Tm may be and the site still prime, °C
 *                     ({@code --tm-margin})
 * @param monovalentMm monovalent cations, mM ({@code --na}; Primer3's {@code -mv})
 * @param divalentMm   Mg²⁺, mM ({@code --mg}; {@code -dv})
 * @param dntpMm       dNTP, mM ({@code --dntp}; {@code -n})
 * @param oligoNm      oligo concentration, nM ({@code --oligo-conc}; {@code -d})
 * @param assayTemps   the annealing temperatures the primer file declares ({@code ta=}): an assay's sites are
 *                     judged at its own, the others' at {@code annealTemp}
 */
public record TmGate(double annealTemp, double margin, double monovalentMm, double divalentMm, double dntpMm,
                     double oligoNm, AnnealTemps assayTemps) {

    public static final double DEFAULT_MARGIN = 5;
    public static final double DEFAULT_MONOVALENT_MM = 50;
    public static final double DEFAULT_DIVALENT_MM = 3;
    public static final double DEFAULT_DNTP_MM = 0.8;
    public static final double DEFAULT_OLIGO_NM = 250;

    /** Sites whose Tm is kept: identical sites recur in every read over a locus. */
    private static final int CACHE_LIMIT = 200_000;
    private static final Map<String, Double> CACHE = new ConcurrentHashMap<>();

    public TmGate {
        assayTemps = assayTemps == null ? AnnealTemps.NONE : assayTemps;
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
    }

    /** Every assay at {@code annealTemp}. */
    public TmGate(double annealTemp, double margin, double monovalentMm, double divalentMm, double dntpMm,
                  double oligoNm) {
        this(annealTemp, margin, monovalentMm, divalentMm, dntpMm, oligoNm, AnnealTemps.NONE);
    }

    /** This gate, the assays of {@code temps} at their own annealing temperature ({@code ta=}). */
    public TmGate withAssayTemps(AnnealTemps temps) {
        return new TmGate(annealTemp, margin, monovalentMm, divalentMm, dntpMm, oligoNm, temps);
    }

    /** The gate at {@code annealTemp}, every other setting at its default. */
    public static TmGate at(double annealTemp) {
        return new TmGate(annealTemp, DEFAULT_MARGIN, DEFAULT_MONOVALENT_MM, DEFAULT_DIVALENT_MM, DEFAULT_DNTP_MM,
                DEFAULT_OLIGO_NM);
    }

    /** The lowest Tm a site may have at {@code --anneal-temp}, °C. */
    public double minimumTm() {
        return annealTemp - margin;
    }

    /** The lowest Tm a site of {@code oligoId} may have, °C: at its assay's {@code ta=}, or {@link #minimumTm()}. */
    public double minimumTm(String oligoId) {
        return annealTempOf(oligoId) - margin;
    }

    /** The annealing temperature of the assay of {@code oligoId}, °C: its {@code ta=}, or {@code --anneal-temp}. */
    public double annealTempOf(String oligoId) {
        if (assayTemps.isEmpty() || oligoId == null) {
            return annealTemp;
        }
        PrimerId parsed = PrimerId.parse(oligoId);
        Double own = assayTemps.of(parsed == null ? oligoId : parsed.assay());
        return own == null ? annealTemp : own;
    }

    /** Whether the site of {@code hit} on {@code template} melts at {@link #minimumTm(String)} or above. */
    public boolean accepts(BlastResult hit, TrimmedEnds.Template template) {
        return siteTm(hit, template) >= minimumTm(hit.subjectID());
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

    /** The Tm of {@code oligo} on {@code strand}, both 5' to 3', under these conditions. */
    double tm(String oligo, String strand) {
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
        return String.format(java.util.Locale.ROOT, "Tm at least %s °C (%s - %s); %s mM Na+, %s mM Mg2+, %s mM dNTP,"
                        + " %s nM oligo", number(minimumTm()), number(annealTemp), number(margin), number(monovalentMm),
                number(divalentMm), number(dntpMm), number(oligoNm))
                + (assayTemps.isEmpty() ? "" : "; at their own annealing temperature (ta=): " + assayTemps.summary());
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
