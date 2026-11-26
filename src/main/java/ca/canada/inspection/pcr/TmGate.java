package ca.canada.inspection.pcr;

import ca.canada.inspection.util.Numbers;

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

    // Before DEFAULT: its constructor reads them.
    /** The annealing temperatures {@link #isAnnealTemp} takes, °C. */
    public static final double MIN_ANNEAL_TEMP = 0;
    public static final double MAX_ANNEAL_TEMP = 100;
    /** {@link #MIN_ANNEAL_TEMP} to {@link #MAX_ANNEAL_TEMP}, as messages say it. */
    public static final String ANNEAL_TEMP_RANGE = Numbers.plain(MIN_ANNEAL_TEMP) + " to " + Numbers.plain(MAX_ANNEAL_TEMP)
            + " °C";

    /** The run's gate when nothing is set: every setting at its default. */
    public static final TmGate DEFAULT = at(DEFAULT_ANNEAL_TEMP);

    /**
     * Site Tms kept, in two generations of up to this many each: identical sites recur in every read over a locus.
     * A full young generation becomes the old one, and the old one is dropped; a site found in the old one moves back
     * to the young. The sites a search keeps meeting stay, and no more than twice this many are held, between runs
     * too.
     */
    static final int CACHE_GENERATION = 100_000;

    private record Generations(Map<Object, Double> young, Map<Object, Double> old) {
    }

    /** What a site's Tm depends on: hashed as numbers, not formatted as text, on every lookup. */
    private record CacheKey(String oligo, String strand, double monovalentMm, double divalentMm, double dntpMm,
                            double oligoNm) {
    }

    private static volatile Generations cache = new Generations(new ConcurrentHashMap<>(), Map.of());

    public TmGate {
        if (!isAnnealTemp(annealTemp)) {
            throw new IllegalArgumentException("--anneal-temp must be " + ANNEAL_TEMP_RANGE + ", got: " + Numbers.shown(annealTemp));
        }
        Numbers.requireNonNegative(margin, "--tm-margin");
        Numbers.requireNonNegative(monovalentMm, "--na");
        Numbers.requireNonNegative(divalentMm, "--mg");
        Numbers.requireNonNegative(dntpMm, "--dntp");
        if (!(oligoNm > 0) || !Double.isFinite(oligoNm)) {
            throw new IllegalArgumentException("--oligo-conc must be greater than 0 nM, got: " + Numbers.shown(oligoNm));
        }
        saltError(monovalentMm, divalentMm, dntpMm).ifPresent(error -> {
            throw new IllegalArgumentException(error);
        });
        assayTemps = assayTemps == null ? AnnealTemps.NONE : assayTemps;
        // A site without a duplex melts at 0 °C here (tm()): a minimum of 0 or below would accept every hit.
        if (!(annealTemp - margin > 0)) {
            throw new IllegalArgumentException("--tm-margin must be below --anneal-temp, so that a site needs a Tm"
                    + " above 0 °C; got " + Numbers.plain(margin) + " and " + Numbers.plain(annealTemp) + " °C");
        }
        for (Map.Entry<String, Double> own : assayTemps.byAssay().entrySet()) {
            if (!(own.getValue() - margin > 0)) {
                throw new IllegalArgumentException("--tm-margin must be below the annealing temperature of every"
                        + " assay, so that a site needs a Tm above 0 °C; got " + Numbers.plain(margin) + " °C, and "
                        + own.getKey() + " has ta=" + Numbers.plain(own.getValue()));
            }
        }
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
                + " --dntp (the dNTP bind their own amount of Mg2+); got " + Numbers.plain(monovalentMm) + " mM Na+, "
                + Numbers.plain(divalentMm) + " mM Mg2+, " + Numbers.plain(dntpMm) + " mM dNTP");
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
        Double own = assayTemps.ofOligo(oligoId);
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
     * Whether a site of oligo {@code oligoId} that melts at {@code tm} °C is accepted: {@code tm} reaches
     * {@link #minimumTm(String)}. The one test of a site's Tm, wherever it is computed.
     */
    public boolean accepts(String oligoId, double tm) {
        return tm >= minimumTm(oligoId);
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
                ? "its assay's annealing temperature less " + Numbers.plain(margin) + " °C"
                : Numbers.plain(minimumTm()) + " °C") + "):");
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
            lines.add("  " + oligo + " " + tm + (own ? " (needs " + Numbers.plain(minimum) + ")" : "") + verdict);
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
        return siteTm(hit, strandUnder(hit, template));
    }

    /**
     * The Tm of the oligo of {@code hit} on {@code strand} ({@link #strandUnder}), °C: the one place a site's Tm is
     * worked out. {@link Double#NEGATIVE_INFINITY} when the oligo is unknown, or the strand is ({@code null}).
     */
    public double siteTm(BlastResult hit, String strand) {
        String oligo = hit.primerSeq();
        if (oligo == null || oligo.isEmpty() || strand == null) {
            return Double.NEGATIVE_INFINITY;
        }
        return tm(oligo.toUpperCase(java.util.Locale.ROOT), strand);
    }

    /**
     * The Tm of {@code oligo} on {@code strand}, both 5' to 3', under these conditions. Of an oligo longer than
     * {@link Thal#MAX_ALIGN} (60) bases, its 3'-most 60 on the whole strand: {@code thal} aligns no longer pair, and
     * the 3' end is what primes; 60 bases melt far above any annealing temperature. The strand is kept whole, as
     * {@code thal} takes 60 bases against a longer one: cutting it by as many bases would cut the wrong ones when the
     * oligo runs past a sequence end, and leave the bases under its 3' end unpaired.
     */
    double tm(String oligo, String strand) {
        if (oligo.length() > Thal.MAX_ALIGN && strand.length() > Thal.MAX_ALIGN) {
            oligo = oligo.substring(oligo.length() - Thal.MAX_ALIGN);
        }
        CacheKey key = new CacheKey(oligo, strand, monovalentMm, divalentMm, dntpMm, oligoNm);
        Double cached = cached(key);
        if (cached != null) {
            return cached;
        }
        Thal.Result result = Thal.thal(oligo, strand, new Thal.Args(Thal.Type.ANY, Thal.MAX_LOOP, monovalentMm,
                divalentMm, dntpMm, oligoNm, Thal.TEMP_KELVIN));
        double tm = result.found() ? result.tm() : Math.min(0, result.tm());
        remember(key, tm);
        return tm;
    }

    /** The Tm kept under {@code key}, moved to the young generation when found in the old; {@code null} for none. */
    static Double cached(Object key) {
        Generations generations = cache;
        Double tm = generations.young().get(key);
        if (tm == null) {
            tm = generations.old().get(key);
            if (tm != null) {
                remember(key, tm);
            }
        }
        return tm;
    }

    /** Keeps {@code tm} under {@code key} in the young generation, which becomes the old one once full. */
    static void remember(Object key, double tm) {
        Generations generations = cache;
        generations.young().put(key, tm);
        if (generations.young().size() >= CACHE_GENERATION) {
            synchronized (TmGate.class) {
                if (cache == generations) {
                    cache = new Generations(new ConcurrentHashMap<>(), generations.young());
                }
            }
        }
    }

    /** How many site Tms are kept, both generations: for tests. */
    static int cachedTms() {
        Generations generations = cache;
        return generations.young().size() + generations.old().size();
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
                        + " %s mM dNTP, %s nM oligo", Numbers.plain(minimumTm()), Numbers.plain(annealTemp), Numbers.plain(margin),
                Numbers.plain(monovalentMm), Numbers.plain(divalentMm), Numbers.plain(dntpMm), Numbers.plain(oligoNm))
                + (assayTemps.isEmpty() ? "" : "; assays at their own annealing temperature (ta=): "
                + assayTemps.summary() + " °C");
    }

    /**
     * Whether {@code temp} can be an annealing temperature, °C: 0 to 100, as {@code --anneal-temp}, the window and
     * {@code ta=} take it.
     */
    public static boolean isAnnealTemp(double temp) {
        return Double.isFinite(temp) && temp >= MIN_ANNEAL_TEMP && temp <= MAX_ANNEAL_TEMP;
    }

}
