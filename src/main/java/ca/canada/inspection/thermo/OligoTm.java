/*
 * insilicoPCR: a Java port of Primer3's oligotm library (src/oligotm.c, Primer3 2.6.1).
 *
 * Original C code:
 * Copyright (c) 1996,1997,1998,1999,2000,2001,2004,2006,2007
 * Whitehead Institute for Biomedical Research, Steve Rozen
 * (http://purl.com/STEVEROZEN/), Andreas Untergasser and Helen Skaletsky
 * All rights reserved.
 *
 * Java port:
 * Copyright (C) 2026 His Majesty the King in Right of Canada, as represented by the
 * Canadian Food Inspection Agency.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. The original oligotm library is under the GNU
 * General Public License version 2 or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program (file LICENSE). If not, see <https://www.gnu.org/licenses/>.
 */
package ca.canada.inspection.thermo;

/**
 * Melting temperature of an oligonucleotide on its perfect complement, by nearest-neighbour
 * thermodynamics: a line-for-line port of Primer3's {@code oligotm.c}, which gives the same numbers as
 * Primer3's {@code oligotm} program.
 *
 * <p>Sequences are upper case, of {@code A C G T N}; any other character is an error
 * ({@link #ERROR}), as in Primer3. Concentrations are in mM, except the oligo's (nM).</p>
 */
public final class OligoTm {

    /** What Primer3 returns for a sequence or a setting it cannot use. */
    public static final double ERROR = -999999.9999;

    private static final double T_KELVIN = 273.15;

    /** Table of nearest-neighbour parameters. */
    public enum Method {
        /** Breslauer et al. 1986. */
        BRESLAUER,
        /** SantaLucia 1998, Primer3's default. */
        SANTALUCIA
    }

    /** Salt correction formula. */
    public enum SaltCorrection {
        /** Schildkraut and Lifson 1965. */
        SCHILDKRAUT,
        /** SantaLucia 1998, Primer3's default. */
        SANTALUCIA,
        /** Owczarzy et al. 2004 (monovalent) and 2008 (divalent). */
        OWCZARZY
    }

    /**
     * Reaction conditions, as Primer3 takes them.
     *
     * @param dnaNm         oligo concentration, nM (Primer3's {@code -d}, default 50)
     * @param monovalentMm  monovalent cations, mM ({@code -mv}, default 50)
     * @param divalentMm    divalent cations, mM ({@code -dv}, default 1.5)
     * @param dntpMm        dNTP, mM ({@code -n}, default 0.6)
     * @param dmsoPercent   DMSO, % ({@code -dm}, default 0)
     * @param dmsoFactor    Tm drop per % DMSO ({@code -df}, default 0.6)
     * @param formamideMolar formamide, mol/l ({@code -fm}, default 0)
     */
    public record Conditions(double dnaNm, double monovalentMm, double divalentMm, double dntpMm,
                             double dmsoPercent, double dmsoFactor, double formamideMolar) {
        /** Primer3's {@code oligotm} defaults. */
        public static final Conditions PRIMER3_DEFAULTS = new Conditions(50, 50, 1.5, 0.6, 0, 0.6, 0);
    }

    /**
     * @param tm    melting temperature, °C, or {@link #ERROR}
     * @param bound percent of the oligo bound at the annealing temperature, or {@link #ERROR} when no
     *              annealing temperature was given (or with the Owczarzy correction, which does not compute it)
     */
    public record Result(double tm, double bound) {
    }

    // Bases in table order.
    private static final String BASES = "ACGTN";

    // Breslauer KJ, Frank R, Bloecker H and Marky LA (1986) Proc Natl Acad Sci 83:3746-50, table 2.
    // Rows: previous base; columns: this base; order A C G T N.
    private static final int[][] S = {
            {240, 173, 208, 239, 215},
            {129, 266, 278, 208, 220},
            {135, 267, 266, 173, 210},
            {169, 135, 129, 240, 168},
            {168, 210, 220, 215, 203}};
    private static final int[][] H = {
            {91, 65, 78, 86, 80},
            {58, 110, 119, 78, 91},
            {56, 111, 110, 65, 85},
            {60, 56, 58, 91, 66},
            {66, 85, 91, 80, 80}};
    /** Delta G of disruption * 1000. */
    private static final int[][] G = {
            {1900, 1300, 1600, 1500, 1575},
            {1900, 3100, 3600, 1600, 2550},
            {1600, 3100, 3100, 1300, 2275},
            {900, 1600, 1900, 1900, 1575},
            {1575, 2275, 2550, 1575, 1994}};

    // SantaLucia JR (1998) Proc Natl Acad Sci 95:1460-65.
    private static final int[][] DS = {
            {222, 224, 210, 204, 224},
            {227, 199, 272, 210, 272},
            {222, 244, 199, 224, 244},
            {213, 222, 227, 222, 227},
            {168, 210, 220, 215, 220}};
    private static final int[][] DH = {
            {79, 84, 78, 72, 72},
            {85, 80, 106, 78, 78},
            {82, 98, 80, 84, 80},
            {72, 82, 85, 79, 72},
            {72, 80, 78, 72, 72}};
    /** Delta G of disruption * 1000. */
    private static final int[][] DG = {
            {1000, 1440, 1280, 880, 880},
            {1450, 1840, 2170, 1280, 1450},
            {1300, 2240, 1840, 1440, 1300},
            {580, 1300, 1450, 1000, 580},
            {580, 1300, 1280, 880, 580}};

    private OligoTm() {
    }

    /** Tm with Primer3's defaults: SantaLucia 1998 parameters and salt correction. */
    public static double tm(String seq, Conditions conditions) {
        return oligotm(seq, conditions, Method.SANTALUCIA, SaltCorrection.SANTALUCIA, -10.0).tm();
    }

    /**
     * Primer3's {@code oligotm()}.
     *
     * @param annealingTemp annealing temperature, °C, for {@link Result#bound}; not computed at 0 or below
     */
    public static Result oligotm(String s, Conditions c, Method method, SaltCorrection saltCorrection,
                                 double annealingTemp) {
        double bound = ERROR;
        if (divalentToMonovalent(c.divalentMm(), c.dntpMm()) == ERROR) {
            return new Result(ERROR, bound);
        }
        int len = s.length() - 1;
        if (len < 0) {
            return new Result(ERROR, bound); // C reads before the string here; its DFA then fails on '\0'
        }
        int gcCount = 0;
        if (c.formamideMolar() != 0.0) {
            for (int i = 0; i < len + 1; i++) {
                char b = s.charAt(i);
                if (b == 'G' || b == 'C') {
                    gcCount++;
                }
            }
        }

        int dh = 0;
        int ds = 0;
        boolean sym = symmetry(s);
        if (method == Method.BRESLAUER) {
            ds = 108;
        } else {
            if (sym) {
                ds += 14;
            }
            // Terminal AT penalty.
            char first = s.charAt(0);
            if (first == 'A' || first == 'T') {
                ds += -41;
                dh += -23;
            } else if (first == 'C' || first == 'G') {
                ds += 28;
                dh += -1;
            }
            char last = s.charAt(len);
            if (last == 'T' || last == 'A') {
                ds += -41;
                dh += -23;
            } else if (last == 'C' || last == 'G') {
                ds += 28;
                dh += -1;
            }
        }

        // The finite-state machine of the C code: each base after the first adds its pair's values.
        int[][] tableH = method == Method.BRESLAUER ? H : DH;
        int[][] tableS = method == Method.BRESLAUER ? S : DS;
        int previous = BASES.indexOf(s.charAt(0));
        if (previous < 0) {
            return new Result(ERROR, bound);
        }
        for (int i = 1; i < s.length(); i++) {
            int current = BASES.indexOf(s.charAt(i));
            if (current < 0) {
                return new Result(ERROR, bound);
            }
            dh += tableH[previous][current];
            ds += tableS[previous][current];
            previous = current;
        }

        double deltaH = dh * -100.0; // in 100 cal/mol
        double deltaS = ds * -0.1;   // in 0.1 cal/K/mol
        double tm = 0;
        len = len + 1;
        double dnaNm = c.dnaNm();
        double kMm = c.monovalentMm();
        double ddG;
        double ka;
        double correction;

        switch (saltCorrection) {
            case SCHILDKRAUT -> {
                kMm = kMm + divalentToMonovalent(c.divalentMm(), c.dntpMm());
                correction = 16.6 * Math.log10(kMm / 1000.0);
                tm = deltaH / (deltaS + 1.987 * Math.log(dnaNm / 4000000000.0)) + correction - T_KELVIN;
                tm -= c.dmsoPercent() * c.dmsoFactor();
                tm += (0.453 * ((double) gcCount) / len - 2.88) * c.formamideMolar();
                if (annealingTemp > 0.0) {
                    ddG = deltaH - (annealingTemp - correction + T_KELVIN) * deltaS;
                    ka = Math.exp(-ddG / (1.987 * (annealingTemp - correction + T_KELVIN)));
                    bound = (1 / (1 + Math.sqrt(1 / ((dnaNm / 4000000000.0) * ka)))) * 100;
                }
            }
            case SANTALUCIA -> {
                kMm = kMm + divalentToMonovalent(c.divalentMm(), c.dntpMm());
                deltaS = deltaS + 0.368 * (len - 1) * Math.log(kMm / 1000.0);
                double strands = sym ? 1000000000.0 : 4000000000.0; // equation A (symmetrical) or B
                tm = deltaH / (deltaS + 1.987 * Math.log(dnaNm / strands)) - T_KELVIN;
                tm -= c.dmsoPercent() * c.dmsoFactor();
                tm += (0.453 * ((double) gcCount) / len - 2.88) * c.formamideMolar();
                if (annealingTemp > 0.0) {
                    ddG = deltaH - (annealingTemp + T_KELVIN) * deltaS;
                    ka = Math.exp(-ddG / (1.987 * (annealingTemp + T_KELVIN)));
                    bound = (1 / (1 + Math.sqrt(1 / ((dnaNm / strands) * ka)))) * 100;
                }
            }
            case OWCZARZY -> {
                double gcPercent = 0;
                for (int i = 0; i < s.length(); i++) {
                    char b = s.charAt(i);
                    if (b == 'C' || b == 'G') {
                        gcPercent++;
                    }
                }
                gcPercent = gcPercent / len;
                // Competition between magnesium and monovalent cations: Owczarzy et al. 2008, figure 9 and
                // equation 16. Below the crossover, the monovalent correction (Owczarzy et al. 2004, eq. 22).
                final double crossoverPoint = 0.22;
                double freeDivalent = c.dntpMm() >= c.divalentMm() ? 0.00000000001 // not log(0)
                        : (c.divalentMm() - c.dntpMm()) / 1000.0;
                double divMonovRatio = kMm == 0 ? 6.0 : Math.sqrt(freeDivalent) / (kMm / 1000);
                if (divMonovRatio < crossoverPoint) {
                    correction = (((4.29 * gcPercent) - 3.95) * Math.pow(10, -5) * Math.log(kMm / 1000.0))
                            + (9.40 * Math.pow(10, -6) * (Math.pow(Math.log(kMm / 1000.0), 2)));
                } else {
                    double b = -9.11 * Math.pow(10, -6);
                    double cc = 6.26 * Math.pow(10, -5);
                    double e = -4.82 * Math.pow(10, -4);
                    double f = 5.25 * Math.pow(10, -4);
                    double a = 3.92 * Math.pow(10, -5);
                    double d = 1.42 * Math.pow(10, -5);
                    double g = 8.31 * Math.pow(10, -5);
                    if (divMonovRatio < 6.0) {
                        a = 3.92 * Math.pow(10, -5) * (0.843 - (0.352 * Math.sqrt(kMm / 1000.0) * Math.log(kMm / 1000.0)));
                        d = 1.42 * Math.pow(10, -5) * (1.279 - 4.03 * Math.pow(10, -3) * Math.log(kMm / 1000.0)
                                - 8.03 * Math.pow(10, -3) * Math.pow(Math.log(kMm / 1000.0), 2));
                        g = 8.31 * Math.pow(10, -5) * (0.486 - 0.258 * Math.log(kMm / 1000.0)
                                + 5.25 * Math.pow(10, -3) * Math.pow(Math.log(kMm / 1000.0), 3));
                    }
                    // As in Primer3, 1/(2 * (len - 1)) is an integer division: 0 for any oligo of 2 bases or more,
                    // which leaves the length term of equation 16 out. Kept to give Primer3's numbers. For one
                    // base it divides by 0, undefined in C; Primer3's build gives 0 there too (gcc computes 1/x
                    // as (x + 1 < 3 ? x : 0)).
                    int lengthDivisor = 2 * (len - 1);
                    int lengthTerm = lengthDivisor == 0 ? 0 : 1 / lengthDivisor;
                    correction = a + (b * Math.log(freeDivalent))
                            + gcPercent * (cc + (d * Math.log(freeDivalent)))
                            + lengthTerm * (e + (f * Math.log(freeDivalent))
                            + g * (Math.pow(Math.log(freeDivalent), 2)));
                }
                double strands = sym ? 1000000000.0 : 4000000000.0;
                tm = 1 / ((1 / (deltaH / (deltaS + 1.9872 * Math.log(dnaNm / strands)))) + correction) - T_KELVIN;
                tm -= c.dmsoPercent() * c.dmsoFactor();
                tm += (0.453 * ((double) gcCount) / len - 2.88) * c.formamideMolar();
            }
        }
        return new Result(tm, bound);
    }

    /** Primer3's {@code oligodg()}: delta G of disruption of {@code s}, kcal/mol, or {@link #ERROR}. */
    public static double oligodg(String s, Method method) {
        int[][] table = method == Method.BRESLAUER ? G : DG;
        int dg = 0;
        if (s.isEmpty()) {
            return ERROR;
        }
        char c = s.charAt(0);
        if (method != Method.BRESLAUER) {
            dg = -1960; // initial dG
            if (c == 'A' || c == 'T') {
                dg += -50; // terminal AT penalty
            }
        }
        int previous = BASES.indexOf(c);
        if (previous < 0) {
            return ERROR;
        }
        for (int i = 1; i < s.length(); i++) {
            int current = BASES.indexOf(s.charAt(i));
            if (current < 0) {
                return ERROR;
            }
            dg += table[previous][current];
            previous = current;
        }
        if (method != Method.BRESLAUER) {
            char lastBase = s.charAt(s.length() - 1);
            if (lastBase == 'A' || lastBase == 'T') {
                dg += -50; // terminal AT penalty
            }
            // The C code then tests the symmetry of the string from its last base on: one base, never
            // symmetrical, so its -430 symmetry correction is never added. Kept to give Primer3's numbers.
            if (symmetry(s.substring(s.length() - 1))) {
                dg += -430;
            }
        }
        return dg / 1000.0;
    }

    /** Primer3's {@code end_oligodg()}: {@link #oligodg} of the last {@code len} bases of {@code s}. */
    public static double endOligodg(String s, int len, Method method) {
        int x = s.length();
        return x < len ? oligodg(s, method) : oligodg(s.substring(x - len), method);
    }

    /**
     * Primer3's {@code seqtm()}: {@link #oligotm} up to {@code nnMaxLen} bases, {@link #longSeqTm} beyond.
     */
    public static Result seqtm(String seq, Conditions c, int nnMaxLen, Method method,
                               SaltCorrection saltCorrection, double annealingTemp) {
        if (seq.length() > nnMaxLen) {
            return longSeqTm(seq, 0, seq.length(), c);
        }
        return oligotm(seq, c, method, saltCorrection, annealingTemp);
    }

    /** Primer3's {@code long_seq_tm()}: the G+C formula for long sequences. */
    public static Result longSeqTm(String s, int start, int len, Conditions c) {
        if (divalentToMonovalent(c.divalentMm(), c.dntpMm()) == ERROR) {
            return new Result(ERROR, ERROR);
        }
        double saltConc = c.monovalentMm() + divalentToMonovalent(c.divalentMm(), c.dntpMm());
        if (start + len > s.length() || start < 0 || len <= 0) {
            return new Result(ERROR, ERROR);
        }
        int gcCount = 0;
        for (int i = start; i < start + len; i++) {
            char b = s.charAt(i);
            if (b == 'G' || b == 'C') {
                gcCount++;
            }
        }
        double tm = 81.5 - c.dmsoPercent() * c.dmsoFactor()
                + (0.453 * ((double) gcCount) / len - 2.88) * c.formamideMolar()
                + (16.6 * Math.log10(saltConc / 1000.0))
                + (41.0 * (((double) gcCount) / len))
                - (600.0 / len);
        return new Result(tm, ERROR);
    }

    /** Primer3's {@code symmetry()}: whether {@code seq} is its own reverse complement. */
    public static boolean symmetry(String seq) {
        int seqLen = seq.length();
        if (seqLen % 2 == 1) {
            return false;
        }
        int mp = seqLen / 2;
        for (int i = 0; i < mp; i++) {
            char s = seq.charAt(i);
            char e = seq.charAt(seqLen - 1 - i);
            if ((s == 'A' && e != 'T') || (s == 'T' && e != 'A') || (e == 'A' && s != 'T') || (e == 'T' && s != 'A')) {
                return false;
            }
            if ((s == 'C' && e != 'G') || (s == 'G' && e != 'C') || (e == 'C' && s != 'G') || (e == 'G' && s != 'C')) {
                return false;
            }
        }
        return true;
    }

    /** Primer3's {@code divalent_to_monovalent()}: divalent cations as monovalent equivalents (von Ahsen 2001). */
    public static double divalentToMonovalent(double divalent, double dntp) {
        if (divalent == 0) {
            dntp = 0;
        }
        if (divalent < 0 || dntp < 0) {
            return ERROR;
        }
        if (divalent < dntp) {
            divalent = dntp; // according to theory, Tm does not depend on divalent cations then
        }
        return 120 * (Math.sqrt(divalent - dntp));
    }
}
