package ca.canada.inspection.pcr;

import ca.canada.inspection.util.Numbers;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The annealing temperature each assay of a primer file is run at, as its header declares it with {@code ta=<°C>}
 * after a primer's ID ({@code >BMpal-F size=265 ta=60}), for the Tm gate ({@link TmGate}, since 2.0): an assay that
 * declares one has its sites judged at it, the others at {@code --anneal-temp}. Assays sharing a reaction
 * ({@code mix=}) are at one temperature: one of them declaring it sets it for all.
 */
public final class AnnealTemps {
    /** The header word that gives an assay's annealing temperature. */
    static final String KEY = "ta";

    /** A primer file that declares none: every assay at {@code --anneal-temp}. */
    public static final AnnealTemps NONE = new AnnealTemps(Map.of());

    /** assay -> °C */
    private final Map<String, Double> byAssay;

    private AnnealTemps(Map<String, Double> byAssay) {
        this.byAssay = Collections.unmodifiableMap(new TreeMap<>(byAssay));
    }

    /** As the Tm gate is given them: °C by assay. */
    public static AnnealTemps of(Map<String, Double> byAssay) {
        byAssay.forEach((assay, temp) -> check(assay, temp));
        return byAssay.isEmpty() ? NONE : new AnnealTemps(byAssay);
    }

    /**
     * Reads {@code ta=} from the primers' header descriptions. An assay's temperature may be given on any of its
     * primers, and on several if they agree; an assay sharing a reaction ({@code mix=}) with one that declares it is
     * at the same temperature.
     *
     * @param descriptions header text following the primer ID, per primer ID as written in the file
     * @param mixGroups    the reactions the file declares
     * @throws IllegalArgumentException for a value that is not a temperature of 0 to 100 °C, an assay given two, or
     *                                  two assays of one reaction given different ones
     */
    public static AnnealTemps fromHeaders(Map<String, String> descriptions, MixGroups mixGroups) {
        // assay -> the primer that declared it, as written
        Map<String, String> declaredOn = new TreeMap<>();
        Map<String, Double> declared = new TreeMap<>();
        descriptions.forEach((primerId, description) -> {
            PrimerId parsed = PrimerId.parse(primerId);
            String assay = parsed == null ? primerId : parsed.assay();
            for (String word : description.trim().split("\\s+")) {
                int equals = word.indexOf('=');
                if (equals < 0 || !word.substring(0, equals).toLowerCase(Locale.ROOT).equals(KEY)) {
                    continue;
                }
                double temp = parse(primerId, word.substring(equals + 1));
                Double previous = declared.putIfAbsent(assay, temp);
                declaredOn.putIfAbsent(assay, primerId);
                if (previous != null && previous != temp) {
                    throw new IllegalArgumentException("Assay " + assay + " declares " + KEY + "= twice with different"
                            + " values: " + Numbers.plain(previous) + " on " + declaredOn.get(assay) + ", " + Numbers.plain(temp)
                            + " on " + primerId);
                }
            }
        });
        Map<String, Double> byAssay = new HashMap<>(declared);
        mixGroups.groups().forEach((group, assays) -> {
            String setBy = null;
            for (String assay : assays) {
                Double temp = declared.get(assay);
                if (temp == null) {
                    continue;
                }
                if (setBy != null && declared.get(setBy).doubleValue() != temp) {
                    throw new IllegalArgumentException("Assays " + setBy + " and " + assay + " share the reaction "
                            + group + " (mix=) but declare different annealing temperatures: " + KEY + "="
                            + Numbers.plain(declared.get(setBy)) + " and " + KEY + "=" + Numbers.plain(temp)
                            + " (a reaction anneals at one)");
                }
                setBy = assay;
            }
            if (setBy != null) {
                double temp = declared.get(setBy);
                assays.forEach(assay -> byAssay.put(assay, temp));
            }
        });
        return byAssay.isEmpty() ? NONE : new AnnealTemps(byAssay);
    }

    private static double parse(String primerId, String value) {
        double temp = Double.NaN;
        // Digits only: Java reads 60d, 60f and 0x3Cp0 as numbers too.
        if (value.matches("[0-9]+(\\.[0-9]*)?|\\.[0-9]+")) {
            temp = Double.parseDouble(value);
        }
        if (!Double.isFinite(temp) || temp < 0 || temp > 100) {
            throw new IllegalArgumentException("Primer " + primerId + ": cannot read the annealing temperature '"
                    + value + "' in '" + KEY + "=" + value + "'. Write it in °C, such as " + KEY + "=60");
        }
        return temp;
    }

    private static void check(String assay, Double temp) {
        if (temp == null || !Double.isFinite(temp) || temp < 0 || temp > 100) {
            throw new IllegalArgumentException("The annealing temperature of " + assay + " must be 0 to 100 °C, got: "
                    + temp);
        }
    }

    /** Whether no assay declares one. */
    public boolean isEmpty() {
        return byAssay.isEmpty();
    }

    /** The annealing temperature of {@code assay}, °C, or {@code null} when it declares none. */
    public Double of(String assay) {
        return byAssay.get(assay);
    }

    /** °C by assay, in name order. */
    public Map<String, Double> byAssay() {
        return byAssay;
    }

    /** {@code BMpal 60, NakhlaPal 63}: the assays and their temperatures, in name order. */
    public String summary() {
        List<String> parts = new java.util.ArrayList<>();
        byAssay.forEach((assay, temp) -> parts.add(assay + " " + Numbers.plain(temp)));
        return String.join(", ", parts);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AnnealTemps temps && temps.byAssay.equals(byAssay);
    }

    @Override
    public int hashCode() {
        return byAssay.hashCode();
    }

    @Override
    public String toString() {
        return "AnnealTemps[" + summary() + "]";
    }
}
