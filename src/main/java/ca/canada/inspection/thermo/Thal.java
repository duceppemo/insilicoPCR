/*
 * insilicoPCR: a Java port of Primer3's thal library (src/thal.c, Primer3 2.6.1), the thermodynamic
 * alignment behind Primer3's ntthal program.
 *
 * Original C code:
 * Copyright (c) 1996,1997,1998,1999,2000,2001,2004,2006,2007,2009,2010,2011,2012
 * Whitehead Institute for Biomedical Research, Steve Rozen
 * (http://purl.com/STEVEROZEN/), and Helen Skaletsky
 * All rights reserved.
 *
 * Java port:
 * Copyright (C) 2026 His Majesty the King in Right of Canada, as represented by the
 * Canadian Food Inspection Agency.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. The original thal library is under the GNU
 * General Public License version 2 or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details. You should have received a copy of the GNU General
 * Public License along with this program (file LICENSE). If not, see <https://www.gnu.org/licenses/>.
 */
package ca.canada.inspection.thermo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Thermodynamic alignment of two oligonucleotides (a dimer, such as a primer on its template site) or of one
 * with itself (a hairpin): the most stable structure by nearest-neighbour thermodynamics, with mismatches,
 * bulges, internal loops and dangling ends, and its melting temperature. A line-for-line port of Primer3's
 * {@code thal.c}, which gives the same dS, dH, dG and Tm as Primer3's {@code ntthal} program.
 *
 * <p>Thread-safe: each call works on its own tables, and the parameters ({@link ThalParameters}) are
 * immutable.</p>
 *
 * <p>Where Primer3 behaves oddly, the port does the same, with a comment, so that the numbers stay Primer3's.</p>
 */
public final class Thal {

    /** {@code THAL_MAX_ALIGN}: at least one of the two sequences must be this long or shorter. */
    public static final int MAX_ALIGN = 60;
    /** {@code THAL_MAX_SEQ}: the longest sequence. */
    public static final int MAX_SEQ = 10000;
    /** {@code MAX_LOOP}: the longest loop the tables hold. */
    public static final int MAX_LOOP = 30;
    /** {@code ABSOLUTE_ZERO}, as Primer3 has it: 0 °C in kelvin. */
    public static final double ABSOLUTE_ZERO = 273.15;
    /** 37 °C in kelvin, Primer3's {@code TEMP_KELVIN}. */
    public static final double TEMP_KELVIN = 310.15;

    private static final int MIN_HRPN_LOOP = 3;
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double R = 1.9872;
    /** Internal loop entropy asymmetry correction: -0.3 kcal/mol at 37 °C. */
    private static final double ILAS = (-300 / 310.15);
    private static final double ILAH = 0.0;
    private static final double MIN_ENTROPY_CUTOFF = -2500.0;
    private static final double MIN_ENTROPY = -3224.0;
    /** Structures with a higher dG are considered unstable. */
    private static final double G2 = 0.0;
    private static final double SMALL_NON_ZERO = 0.000001;

    /** {@code BPI}: 1 for a Watson-Crick pair. */
    private static final int[] BPI = {
            0, 0, 0, 1, 0,
            0, 0, 1, 0, 0,
            0, 1, 0, 0, 0,
            1, 0, 0, 0, 0,
            0, 0, 0, 0, 0};

    /** {@code thal_alignment_type}. */
    public enum Type {
        /** Any alignment of the two sequences (Primer3's default). */
        ANY,
        /** Anchored at the 3' end of the first sequence. */
        END1,
        /** Anchored at the 3' end of the second sequence. */
        END2,
        /** One sequence folded on itself. */
        HAIRPIN
    }

    /**
     * {@code thal_args}.
     *
     * @param type              alignment type
     * @param maxLoop           longest loop considered, 0 to {@link #MAX_LOOP}
     * @param monovalentMm      monovalent cations, mM ({@code ntthal -mv}, default 50)
     * @param divalentMm        divalent cations, mM ({@code -dv}, default 0)
     * @param dntpMm            dNTP, mM ({@code -n}; {@code thal}'s default 0.8 counts only with divalent cations)
     * @param dnaNm             oligo concentration, nM ({@code -d}, default 50)
     * @param temperatureKelvin temperature for dG, and for the choice of hairpin structures ({@code -t} + 273.15)
     */
    public record Args(Type type, int maxLoop, double monovalentMm, double divalentMm, double dntpMm, double dnaNm,
                       double temperatureKelvin) {
        /** {@code set_thal_default_args()}: what {@code ntthal} uses without options. */
        public static final Args DEFAULTS = new Args(Type.ANY, MAX_LOOP, 50, 0.0, 0.8, 50, TEMP_KELVIN);

        public Args {
            if (maxLoop < 0 || maxLoop > MAX_LOOP) {
                throw new IllegalArgumentException("maxLoop must be 0 to " + MAX_LOOP + ": " + maxLoop);
            }
        }

        /** These arguments with another alignment type. */
        public Args withType(Type newType) {
            return new Args(newType, maxLoop, monovalentMm, divalentMm, dntpMm, dnaNm, temperatureKelvin);
        }
    }

    /**
     * {@code thal_results}, with the values {@code ntthal} prints.
     *
     * @param tm        melting temperature, °C; 0 when no structure is found, and {@link Double#NEGATIVE_INFINITY}
     *                  ({@code THAL_ERROR_SCORE}) on an error
     * @param dS        entropy, cal/K/mol, salt correction included; NaN without a structure
     * @param dH        enthalpy, cal/mol; NaN without a structure
     * @param dG        free energy at {@link Args#temperatureKelvin()}, cal/mol; NaN without a structure
     * @param alignEnd1 {@code align_end_1}: for a dimer, the position in the first sequence (1-based) of the
     *                  structure's last pair; -1 without a structure
     * @param alignEnd2 {@code align_end_2}: likewise in the second sequence, numbered from its 3' end
     * @param structure {@code ntthal}'s drawing of the structure, without the {@code SEQ}/{@code STR} labels:
     *                  4 lines for a dimer, 2 for a hairpin; empty without a structure
     * @param message   Primer3's message, empty when there is none
     */
    public record Result(double tm, double dS, double dH, double dG, int alignEnd1, int alignEnd2,
                         List<String> structure, String message) {
        public Result {
            structure = List.copyOf(structure);
        }

        /** Whether a structure was found. */
        public boolean found() {
            return !structure.isEmpty();
        }

        static Result error(String message) {
            return new Result(Double.NEGATIVE_INFINITY, Double.NaN, Double.NaN, Double.NaN, -1, -1, List.of(), message);
        }

        static Result none(String message, int alignEnd1, int alignEnd2) {
            return new Result(0.0, Double.NaN, Double.NaN, Double.NaN, alignEnd1, alignEnd2, List.of(), message);
        }
    }

    private Thal() {
    }

    /** {@link #thal(String, String, Args, ThalParameters)} with Primer3's default parameters. */
    public static Result thal(String oligo1, String oligo2, Args args) {
        return thal(oligo1, oligo2, args, ThalParameters.defaults());
    }

    /**
     * Primer3's {@code thal()}: the most stable structure of {@code oligo1} with {@code oligo2} (both 5'->3'),
     * or of {@code oligo1} alone for a {@link Type#HAIRPIN} ({@code ntthal} passes it twice).
     */
    public static Result thal(String oligo1, String oligo2, Args args, ThalParameters parameters) {
        int lenF = oligo1.length();
        int lenR = oligo2.length();
        if (lenF > MAX_ALIGN && lenR > MAX_ALIGN) {
            return Result.error("Both sequences longer than " + MAX_ALIGN + " for thermodynamic alignment");
        }
        if (lenF > MAX_SEQ) {
            return Result.error("Target sequence length > maximum allowed (" + MAX_SEQ
                    + ") in thermodynamic alignment (1)");
        }
        if (lenR > MAX_SEQ) {
            return Result.error("Target sequence length > maximum allowed (" + MAX_SEQ
                    + ") in thermodynamic alignment (2)");
        }
        if (lenF == 0) {
            return Result.none("Empty first sequence", -1, -1);
        }
        if (lenR == 0) {
            return Result.none("Empty second sequence", -1, -1);
        }
        return new Alignment(parameters, args).run(oligo1, oligo2);
    }

    /** {@code str2int()}: A 0, C 1, G 2, T 3 (and, as in Primer3, the digits 0 to 3 as themselves); else 4. */
    static int str2int(char c) {
        return switch (c) {
            case 'A', '0' -> 0;
            case 'C', '1' -> 1;
            case 'G', '2' -> 2;
            case 'T', '3' -> 3;
            default -> 4;
        };
    }

    /** C's {@code toupper()} in the "C" locale. */
    private static char toUpper(char c) {
        return c >= 'a' && c <= 'z' ? (char) (c - 'a' + 'A') : c;
    }

    /** {@code symmetry_thermo()}: whether {@code seq} is its own reverse complement (case ignored). */
    static boolean symmetryThermo(String seq) {
        int seqLen = seq.length();
        if (seqLen % 2 == 1) {
            return false;
        }
        int mp = seqLen / 2;
        for (int i = 0; i < mp; i++) {
            char s = toUpper(seq.charAt(i));
            char e = toUpper(seq.charAt(seqLen - 1 - i));
            if ((s == 'A' && e != 'T') || (s == 'T' && e != 'A') || (e == 'A' && s != 'T') || (e == 'T' && s != 'A')) {
                return false;
            }
            if ((s == 'C' && e != 'G') || (s == 'G' && e != 'C') || (e == 'C' && s != 'G') || (e == 'G' && s != 'C')) {
                return false;
            }
        }
        return true;
    }

    /** {@code saltCorrectS()}: the salt correction of the entropy, per phosphate pair (SantaLucia). */
    static double saltCorrectS(double mv, double dv, double dntp) {
        if (dv <= 0) {
            dntp = dv;
        }
        return 0.368 * ((Math.log((mv + 120 * (Math.sqrt(Math.max(0.0, dv - dntp)))) / 1000)));
    }

    /** {@code equal()}: both finite and within 1e-5. */
    private static boolean equal(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b)) {
            return false;
        }
        return Math.abs(a - b) < 1e-5;
    }

    private static boolean isPositive(double x) {
        return x > 0;
    }

    /** {@code max5()}: which of the five is the largest, ties going to the later one. */
    private static int max5(double a, double b, double c, double d, double e) {
        if (a > b && a > c && a > d && a > e) {
            return 1;
        } else if (b > c && b > d && b > e) {
            return 2;
        } else if (c > d && c > e) {
            return 3;
        } else if (d > e) {
            return 4;
        } else {
            return 5;
        }
    }

    /** The state of one call of {@code thal()}: the C code keeps it in static variables. */
    private static final class Alignment {
        private final ThalParameters p;
        private final Args a;
        private final int maxLoop;

        private double dplxInitH;
        private double dplxInitS;
        private double saltCorrection;
        private double rc;
        private int len1;
        private int len2;
        private int len3;
        private char[] oligo1;
        private char[] oligo2;
        private int[] numSeq1;
        private int[] numSeq2;
        private double[] enthalpyDPT;
        private double[] entropyDPT;
        private double[] send5;
        private double[] hend5;

        Alignment(ThalParameters p, Args a) {
            this.p = p;
            this.a = a;
            this.maxLoop = a.maxLoop();
        }

        // EnthalpyDPT(i, j) and EntropyDPT(i, j). For a hairpin, len3 is the length less one while the table has
        // len1 * len2 cells, so that (i, len2) and (i + 1, 1) share a cell, as in Primer3; only the first is used.
        private double eH(int i, int j) {
            return enthalpyDPT[j + ((i - 1) * len3) - 1];
        }

        private double eS(int i, int j) {
            return entropyDPT[j + ((i - 1) * len3) - 1];
        }

        private void setH(int i, int j, double v) {
            enthalpyDPT[j + ((i - 1) * len3) - 1] = v;
        }

        private void setS(int i, int j, double v) {
            entropyDPT[j + ((i - 1) * len3) - 1] = v;
        }

        private int bpIndx(int x, int y) {
            return BPI[x * 5 + y];
        }

        private double atPenaltyS(int x, int y) {
            return p.atpS[x * 5 + y];
        }

        private double atPenaltyH(int x, int y) {
            return p.atpH[x * 5 + y];
        }

        Result run(String oligoF, String oligoR) {
            Type type = a.type();
            String first = type != Type.END2 ? oligoF : oligoR;
            String second = type != Type.END2 ? oligoR : oligoF;
            if (type == Type.HAIRPIN) {
                len2 = second.length();
                len3 = len2 - 1;
                dplxInitH = 0.0;
                dplxInitS = -0.00000000001;
                rc = 0;
                oligo2 = second.toCharArray();
            } else {
                dplxInitH = 200;
                dplxInitS = -5.7;
                if (symmetryThermo(first) && symmetryThermo(second)) {
                    rc = R * Math.log(a.dnaNm() / 1000000000.0);
                } else {
                    rc = R * Math.log(a.dnaNm() / 4000000000.0);
                }
                // The second sequence goes into the table 3'->5'.
                oligo2 = new StringBuilder(second).reverse().toString().toCharArray();
            }
            oligo1 = first.toCharArray();
            len1 = oligo1.length;
            len2 = oligo2.length;
            saltCorrection = saltCorrectS(a.monovalentMm(), a.divalentMm(), a.dntpMm());
            for (int i = 0; i < len1; i++) {
                oligo1[i] = toUpper(oligo1[i]);
            }
            for (int i = 0; i < len2; i++) {
                oligo2[i] = toUpper(oligo2[i]);
            }
            numSeq1 = new int[len1 + 2];
            numSeq2 = new int[len2 + 2];
            for (int i = 1; i <= len1; ++i) {
                numSeq1[i] = str2int(oligo1[i - 1]);
            }
            for (int i = 1; i <= len2; ++i) {
                numSeq2[i] = str2int(oligo2[i - 1]);
            }
            numSeq1[0] = numSeq1[len1 + 1] = numSeq2[0] = numSeq2[len2 + 1] = 4; // marked as N
            return type == Type.HAIRPIN ? hairpin() : dimer();
        }

        private Result hairpin() {
            send5 = new double[len1 + 1];
            hend5 = new double[len1 + 1];
            enthalpyDPT = new double[len1 * len2];
            entropyDPT = new double[len1 * len2];
            initMatrix2();
            fillMatrix2();
            calcTerminalBp(a.temperatureKelvin());
            double mh = hend5[len1];
            double ms = send5[len1];
            // align_end_1 and align_end_2 are (int) mh and (int) ms in Primer3. An infinite value cast to int
            // is undefined in C; x86-64 gives INT_MIN.
            int alignEnd1 = Double.isFinite(mh) ? (int) mh : Integer.MIN_VALUE;
            int alignEnd2 = Double.isFinite(ms) ? (int) ms : Integer.MIN_VALUE;
            if (!Double.isFinite(mh)) {
                // "No secondary structure could be calculated"; the temperature is then 0.
                return Result.none("", alignEnd1, alignEnd2);
            }
            int[] bp = new int[len1];
            tracebacku(bp);
            return drawHairpin(bp, mh, ms, alignEnd1, alignEnd2);
        }

        private Result dimer() {
            len3 = len2;
            enthalpyDPT = new double[len1 * len2];
            entropyDPT = new double[len1 * len2];
            initMatrix();
            fillMatrix();
            double[] sh = new double[2];
            int bestI = 0;
            int bestJ = 0;
            double g1;
            double bestG = INF;
            if (a.type() == Type.ANY) {
                for (int i = 1; i <= len1; i++) {
                    for (int j = 1; j <= len2; j++) {
                        rsh(i, j, sh);
                        sh[0] = sh[0] + SMALL_NON_ZERO; // added in Primer3 "for compiler optimization -O2 vs -O0"
                        sh[1] = sh[1] + SMALL_NON_ZERO;
                        g1 = (eH(i, j) + sh[1] + dplxInitH) - TEMP_KELVIN * (eS(i, j) + sh[0] + dplxInitS);
                        if (g1 < bestG) {
                            bestG = g1;
                            bestI = i;
                            bestJ = j;
                        }
                    }
                }
            }
            if (a.type() == Type.END1 || a.type() == Type.END2) {
                bestI = len1;
                bestJ = 0;
                int i = len1;
                bestG = INF;
                for (int j = 1; j <= len2; ++j) {
                    rsh(i, j, sh);
                    sh[0] = sh[0] + SMALL_NON_ZERO;
                    sh[1] = sh[1] + SMALL_NON_ZERO;
                    g1 = (eH(i, j) + sh[1] + dplxInitH) - TEMP_KELVIN * (eS(i, j) + sh[0] + dplxInitS);
                    if (g1 < bestG) {
                        bestG = g1;
                        bestJ = j;
                    }
                }
            }
            if (!Double.isFinite(bestG)) {
                bestI = bestJ = 1;
            }
            rsh(bestI, bestJ, sh);
            double dH = eH(bestI, bestJ) + sh[1] + dplxInitH;
            double dS = eS(bestI, bestJ) + sh[0] + dplxInitS;
            if (!Double.isFinite(eH(bestI, bestJ))) {
                return Result.none("", -1, -1);
            }
            int[] ps1 = new int[len1];
            int[] ps2 = new int[len2];
            traceback(bestI, bestJ, ps1, ps2);
            return drawDimer(ps1, ps2, dH, dS, bestI, bestJ);
        }

        private void initMatrix() {
            for (int i = 1; i <= len1; ++i) {
                for (int j = 1; j <= len2; ++j) {
                    if (bpIndx(numSeq1[i], numSeq2[j]) == 0) {
                        setH(i, j, INF);
                        setS(i, j, -1.0);
                    } else {
                        setH(i, j, 0.0);
                        setS(i, j, MIN_ENTROPY);
                    }
                }
            }
        }

        private void initMatrix2() {
            for (int i = 1; i <= len1; ++i) {
                for (int j = i; j <= len2; ++j) {
                    if (j - i < MIN_HRPN_LOOP + 1 || (bpIndx(numSeq1[i], numSeq1[j]) == 0)) {
                        setH(i, j, INF);
                        setS(i, j, -1.0);
                    } else {
                        setH(i, j, 0.0);
                        setS(i, j, MIN_ENTROPY);
                    }
                }
            }
        }

        private void fillMatrix() {
            double[] sh = new double[2];
            for (int i = 1; i <= len1; ++i) {
                for (int j = 1; j <= len2; ++j) {
                    if (Double.isFinite(eH(i, j))) {
                        sh[0] = -1.0;
                        sh[1] = INF;
                        lsh(i, j, sh);
                        if (Double.isFinite(sh[1])) {
                            setS(i, j, sh[0]);
                            setH(i, j, sh[1]);
                        }
                        if (i > 1 && j > 1) {
                            maxTM(i, j);
                            for (int d = 3; d <= maxLoop + 2; d++) {
                                int ii = i - 1;
                                int jj = -ii - d + (j + i);
                                if (jj < 1) {
                                    ii -= Math.abs(jj - 1);
                                    jj = 1;
                                }
                                for (; ii > 0 && jj < j; --ii, ++jj) {
                                    if (Double.isFinite(eH(ii, jj))) {
                                        sh[0] = -1.0;
                                        sh[1] = INF;
                                        calcBulgeInternal(ii, jj, i, j, sh, 0);
                                        if (sh[0] < MIN_ENTROPY_CUTOFF) {
                                            // to not give dH any value if dS is unreasonable
                                            sh[0] = MIN_ENTROPY;
                                            sh[1] = 0.0;
                                        }
                                        if (Double.isFinite(sh[1])) {
                                            setH(i, j, sh[1]);
                                            setS(i, j, sh[0]);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        private void fillMatrix2() {
            double[] sh = new double[2];
            for (int j = 2; j <= len2; ++j) {
                for (int i = j - MIN_HRPN_LOOP - 1; i >= 1; --i) {
                    if (Double.isFinite(eH(i, j))) {
                        sh[0] = -1.0;
                        sh[1] = INF;
                        maxTM2(i, j);
                        cbi(i, j, sh, 0);
                        sh[0] = -1.0;
                        sh[1] = INF;
                        calcHairpin(i, j, sh, 0);
                        if (Double.isFinite(sh[1])) {
                            if (sh[0] < MIN_ENTROPY_CUTOFF) {
                                sh[0] = MIN_ENTROPY;
                                sh[1] = 0.0;
                            }
                            setS(i, j, sh[0]);
                            setH(i, j, sh[1]);
                        }
                    }
                }
            }
        }

        private void maxTM(int i, int j) {
            double t0;
            double t1;
            double s0 = eS(i, j);
            double h0 = eH(i, j);
            double s1;
            double h1;
            double[] sh = new double[2];
            rsh(i, j, sh);
            t0 = (h0 + dplxInitH + sh[1]) / (s0 + dplxInitS + sh[0] + rc);
            if (Double.isFinite(eH(i - 1, j - 1)) && Double.isFinite(hs(i - 1, j - 1, 1))) {
                s1 = (eS(i - 1, j - 1) + ss(i - 1, j - 1, 1));
                h1 = (eH(i - 1, j - 1) + hs(i - 1, j - 1, 1));
                t1 = (h1 + dplxInitH + sh[1]) / (s1 + dplxInitS + sh[0] + rc);
            } else {
                s1 = -1.0;
                h1 = INF;
                t1 = (h1 + dplxInitH) / (s1 + dplxInitS + rc);
            }
            if (s1 < MIN_ENTROPY_CUTOFF) {
                s1 = MIN_ENTROPY;
                h1 = 0.0;
            }
            if (s0 < MIN_ENTROPY_CUTOFF) {
                s0 = MIN_ENTROPY;
                h0 = 0.0;
            }
            if (t1 > t0) {
                setS(i, j, s1);
                setH(i, j, h1);
            } else if (t0 >= t1) {
                setS(i, j, s0);
                setH(i, j, h0);
            }
        }

        private void maxTM2(int i, int j) {
            double s0 = eS(i, j);
            double h0 = eH(i, j);
            double s1;
            double h1;
            double t0 = (h0 + dplxInitH) / (s0 + dplxInitS + rc);
            if (Double.isFinite(eH(i, j))) {
                s1 = (eS(i + 1, j - 1) + ss(i, j, 2));
                h1 = (eH(i + 1, j - 1) + hs(i, j, 2));
            } else {
                s1 = -1.0;
                h1 = INF;
            }
            double t1 = (h1 + dplxInitH) / (s1 + dplxInitS + rc);
            if (s1 < MIN_ENTROPY_CUTOFF) {
                s1 = MIN_ENTROPY;
                h1 = 0.0;
            }
            if (s0 < MIN_ENTROPY_CUTOFF) {
                s0 = MIN_ENTROPY;
                h0 = 0.0;
            }
            if (t1 > t0) {
                setS(i, j, s1);
                setH(i, j, h1);
            } else {
                setS(i, j, s0);
                setH(i, j, h0);
            }
        }

        /** {@code LSH()}: the left (5') end of a duplex closed by i-j. */
        private void lsh(int i, int j, double[] entropyEnthalpy) {
            double s1;
            double h1;
            double t1 = -INF;
            double g1;
            double s2;
            double h2;
            double t2;
            double g2;
            if (bpIndx(numSeq1[i], numSeq2[j]) == 0) {
                // Primer3 writes the table here and leaves entropyEnthalpy as it was.
                setS(i, j, -1.0);
                setH(i, j, INF);
                return;
            }
            int a1 = numSeq1[i];
            int a0 = numSeq1[i - 1];
            int b1 = numSeq2[j];
            int b0 = numSeq2[j - 1];
            s1 = atPenaltyS(a1, b1) + p.tstack2Entropies[ThalParameters.idx4(b1, b0, a1, a0)];
            h1 = atPenaltyH(a1, b1) + p.tstack2Enthalpies[ThalParameters.idx4(b1, b0, a1, a0)];
            g1 = h1 - TEMP_KELVIN * s1;
            if (!Double.isFinite(h1) || g1 > 0) {
                h1 = INF;
                s1 = -1.0;
                g1 = 1.0;
            }
            double d3H = p.dangleEnthalpies3[ThalParameters.idx3(b1, b0, a1)];
            double d5H = p.dangleEnthalpies5[ThalParameters.idx3(b1, a1, a0)];
            boolean danglesPossible = bpIndx(a0, b0) != 1;
            if (danglesPossible && Double.isFinite(d3H) && Double.isFinite(d5H)) {
                // two dangling ends at the same end of the duplex
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies3[ThalParameters.idx3(b1, b0, a1)]
                        + p.dangleEntropies5[ThalParameters.idx3(b1, a1, a0)];
                h2 = atPenaltyH(a1, b1) + d3H + d5H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            } else if (danglesPossible && Double.isFinite(d3H)) {
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies3[ThalParameters.idx3(b1, b0, a1)];
                h2 = atPenaltyH(a1, b1) + d3H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            } else if (danglesPossible && Double.isFinite(d5H)) {
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies5[ThalParameters.idx3(b1, a1, a0)];
                h2 = atPenaltyH(a1, b1) + d5H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            }
            s2 = atPenaltyS(a1, b1);
            h2 = atPenaltyH(a1, b1);
            t2 = (h2 + dplxInitH) / (s2 + dplxInitS + rc);
            if (Double.isFinite(h1)) {
                if (t1 < t2) {
                    entropyEnthalpy[0] = s2;
                    entropyEnthalpy[1] = h2;
                } else {
                    entropyEnthalpy[0] = s1;
                    entropyEnthalpy[1] = h1;
                }
            } else {
                entropyEnthalpy[0] = s2;
                entropyEnthalpy[1] = h2;
            }
        }

        /**
         * The block repeated in {@code LSH()} and {@code RSH()} for each kind of dangling end: keeps the terminal
         * mismatch (s1, h1, t1) or takes the dangling end (s2, h2), whichever melts higher with a negative dG.
         *
         * @return {s, h, t}
         */
        private double[] chooseEnd(double s1, double h1, double t1, double g1, double s2, double h2) {
            double g2 = h2 - TEMP_KELVIN * s2;
            if (!Double.isFinite(h2) || g2 > 0) {
                h2 = INF;
                s2 = -1.0;
                g2 = 1.0;
            }
            double t2 = (h2 + dplxInitH) / (s2 + dplxInitS + rc);
            if (Double.isFinite(h1) && g1 < 0) {
                t1 = (h1 + dplxInitH) / (s1 + dplxInitS + rc);
                if (t1 < t2 && g2 < 0) {
                    s1 = s2;
                    h1 = h2;
                    t1 = t2;
                }
            } else if (g2 < 0) {
                s1 = s2;
                h1 = h2;
                t1 = t2;
            }
            return new double[]{s1, h1, t1};
        }

        /** {@code RSH()}: the right (3') end of a duplex closed by i-j. */
        private void rsh(int i, int j, double[] entropyEnthalpy) {
            double s1;
            double h1;
            double t1 = -INF;
            double g1;
            double s2;
            double h2;
            double t2;
            if (bpIndx(numSeq1[i], numSeq2[j]) == 0) {
                entropyEnthalpy[0] = -1.0;
                entropyEnthalpy[1] = INF;
                return;
            }
            int a1 = numSeq1[i];
            int a2 = numSeq1[i + 1];
            int b1 = numSeq2[j];
            int b2 = numSeq2[j + 1];
            s1 = atPenaltyS(a1, b1) + p.tstack2Entropies[ThalParameters.idx4(a1, a2, b1, b2)];
            h1 = atPenaltyH(a1, b1) + p.tstack2Enthalpies[ThalParameters.idx4(a1, a2, b1, b2)];
            g1 = h1 - TEMP_KELVIN * s1;
            if (!Double.isFinite(h1) || g1 > 0) {
                h1 = INF;
                s1 = -1.0;
                g1 = 1.0;
            }
            double d3H = p.dangleEnthalpies3[ThalParameters.idx3(a1, a2, b1)];
            double d5H = p.dangleEnthalpies5[ThalParameters.idx3(a1, b1, b2)];
            boolean danglesPossible = bpIndx(a2, b2) == 0;
            if (danglesPossible && Double.isFinite(d3H) && Double.isFinite(d5H)) {
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies3[ThalParameters.idx3(a1, a2, b1)]
                        + p.dangleEntropies5[ThalParameters.idx3(a1, b1, b2)];
                h2 = atPenaltyH(a1, b1) + d3H + d5H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            } else if (danglesPossible && Double.isFinite(d3H)) {
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies3[ThalParameters.idx3(a1, a2, b1)];
                h2 = atPenaltyH(a1, b1) + d3H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            } else if (danglesPossible && Double.isFinite(d5H)) {
                s2 = atPenaltyS(a1, b1) + p.dangleEntropies5[ThalParameters.idx3(a1, b1, b2)];
                h2 = atPenaltyH(a1, b1) + d5H;
                double[] st = chooseEnd(s1, h1, t1, g1, s2, h2);
                s1 = st[0];
                h1 = st[1];
                t1 = st[2];
            }
            s2 = atPenaltyS(a1, b1);
            h2 = atPenaltyH(a1, b1);
            t2 = (h2 + dplxInitH) / (s2 + dplxInitS + rc);
            if (Double.isFinite(h1)) {
                if (t1 < t2) {
                    entropyEnthalpy[0] = s2;
                    entropyEnthalpy[1] = h2;
                } else {
                    entropyEnthalpy[0] = s1;
                    entropyEnthalpy[1] = h1;
                }
            } else {
                entropyEnthalpy[0] = s2;
                entropyEnthalpy[1] = h2;
            }
        }

        /** {@code Ss()}: stack entropy; k = 2 for a hairpin. */
        private double ss(int i, int j, int k) {
            if (k == 2) {
                if (i >= j) {
                    return -1.0;
                }
                if (i == len1 || j == len2 + 1) {
                    return -1.0;
                }
                if (i > len1) {
                    i -= len1;
                }
                if (j > len2) {
                    j -= len2;
                }
                return p.stackEntropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])];
            }
            return p.stackEntropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])];
        }

        /** {@code Hs()}: stack enthalpy; k = 2 for a hairpin. */
        private double hs(int i, int j, int k) {
            if (k == 2) {
                if (i >= j) {
                    return INF;
                }
                if (i == len1 || j == len2 + 1) {
                    return INF;
                }
                if (i > len1) {
                    i -= len1;
                }
                if (j > len2) {
                    j -= len2;
                }
                double h = p.stackEnthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])];
                return Double.isFinite(h) ? h : INF;
            }
            return p.stackEnthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])];
        }

        /** {@code CBI()}: bulges, internal loops and stacks closing a hairpin pair i-j. */
        private void cbi(int i, int j, double[] entropyEnthalpy, int traceback) {
            for (int d = j - i - 3; d >= MIN_HRPN_LOOP + 1 && d >= j - i - 2 - maxLoop; --d) {
                for (int ii = i + 1; ii < j - d && ii <= len1; ++ii) {
                    int jj = d + ii;
                    if (traceback == 0) {
                        entropyEnthalpy[0] = -1.0;
                        entropyEnthalpy[1] = INF;
                    }
                    if (Double.isFinite(eH(ii, jj)) && Double.isFinite(eH(i, j))) {
                        calcBulgeInternal2(i, j, ii, jj, entropyEnthalpy, traceback);
                        if (Double.isFinite(entropyEnthalpy[1])) {
                            if (entropyEnthalpy[0] < MIN_ENTROPY_CUTOFF) {
                                entropyEnthalpy[0] = MIN_ENTROPY;
                                entropyEnthalpy[1] = 0.0;
                            }
                            if (traceback == 0) {
                                setH(i, j, entropyEnthalpy[1]);
                                setS(i, j, entropyEnthalpy[0]);
                            }
                        }
                    }
                }
            }
        }

        /** {@code calc_hairpin()}: a hairpin loop closed by i-j. */
        private void calcHairpin(int i, int j, double[] entropyEnthalpy, int traceback) {
            int loopSize = j - i - 1;
            double[] sh = {-1.0, INF};
            if (loopSize < MIN_HRPN_LOOP) {
                entropyEnthalpy[0] = -1.0;
                entropyEnthalpy[1] = INF;
                return;
            }
            if (i <= len1 && len2 < j) {
                entropyEnthalpy[0] = -1.0;
                entropyEnthalpy[1] = INF;
                return;
            } else if (i > len2) {
                i -= len1;
                j -= len2;
            }
            if (loopSize <= 30) {
                entropyEnthalpy[1] = p.hairpinLoopEnthalpies[loopSize - 1];
                entropyEnthalpy[0] = p.hairpinLoopEntropies[loopSize - 1];
            } else {
                entropyEnthalpy[1] = p.hairpinLoopEnthalpies[29];
                entropyEnthalpy[0] = p.hairpinLoopEntropies[29];
            }
            if (loopSize > 3) { // for loops of 4 bases or more, terminal mismatches count
                int k = ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq1[j], numSeq1[j - 1]);
                entropyEnthalpy[1] += p.tstack2Enthalpies[k];
                entropyEnthalpy[0] += p.tstack2Entropies[k];
            } else if (loopSize == 3) { // for loops of 3, the AT penalty
                entropyEnthalpy[1] += atPenaltyH(numSeq1[i], numSeq1[j]);
                entropyEnthalpy[0] += atPenaltyS(numSeq1[i], numSeq1[j]);
            }
            if (loopSize == 3) { // triloop bonus
                long key = ThalParameters.loopKey(numSeq1, i, 5);
                Double h = p.triloopEnthalpies.get(key);
                if (h != null) {
                    entropyEnthalpy[1] += h;
                }
                Double s = p.triloopEntropies.get(key);
                if (s != null) {
                    entropyEnthalpy[0] += s;
                }
            } else if (loopSize == 4) { // tetraloop bonus
                long key = ThalParameters.loopKey(numSeq1, i, 6);
                Double h = p.tetraloopEnthalpies.get(key);
                if (h != null) {
                    entropyEnthalpy[1] += h;
                }
                Double s = p.tetraloopEntropies.get(key);
                if (s != null) {
                    entropyEnthalpy[0] += s;
                }
            }
            if (!Double.isFinite(entropyEnthalpy[1])) {
                entropyEnthalpy[1] = INF;
                entropyEnthalpy[0] = -1.0;
            }
            if (isPositive(entropyEnthalpy[1]) && isPositive(entropyEnthalpy[0])
                    && (!isPositive(eH(i, j)) || !isPositive(eS(i, j)))) {
                entropyEnthalpy[1] = INF;
                entropyEnthalpy[0] = -1.0;
            }
            rsh(i, j, sh);
            double g1 = entropyEnthalpy[1] + sh[1] - TEMP_KELVIN * (entropyEnthalpy[0] + sh[0]);
            double g2 = eH(i, j) + sh[1] - TEMP_KELVIN * (eS(i, j) + sh[0]);
            if (g2 < g1 && traceback == 0) {
                entropyEnthalpy[0] = eS(i, j);
                entropyEnthalpy[1] = eH(i, j);
            }
        }

        /** {@code calc_bulge_internal()}: a bulge or internal loop between pairs i-j and ii-jj of a dimer. */
        private void calcBulgeInternal(int i, int j, int ii, int jj, double[] entropyEnthalpy, int traceback) {
            double[] sh = {-1.0, INF};
            double s = -1.0;
            double h = INF;
            double g1;
            double g2;
            int loopSize1 = ii - i - 1;
            int loopSize2 = jj - j - 1;
            // (Primer3 also works out N and N_loop here, and uses neither.)
            int loopSize = loopSize1 + loopSize2 - 1;
            if ((loopSize1 == 0 && loopSize2 > 0) || (loopSize2 == 0 && loopSize1 > 0)) { // only bulges
                if (loopSize2 == 1 || loopSize1 == 1) { // a bulge of one: the intervening pair's stack counts
                    if ((loopSize2 == 1 && loopSize1 == 0) || (loopSize2 == 0 && loopSize1 == 1)) {
                        int k = ThalParameters.idx4(numSeq1[i], numSeq1[ii], numSeq2[j], numSeq2[jj]);
                        h = p.bulgeLoopEnthalpies[loopSize] + p.stackEnthalpies[k];
                        s = p.bulgeLoopEntropies[loopSize] + p.stackEntropies[k];
                    }
                    // "or" here, where the other cases have "and": as in Primer3
                    if (isPositive(h) || isPositive(s)) {
                        h = INF;
                        s = -1.0;
                    }
                    h += eH(i, j);
                    s += eS(i, j);
                    if (!Double.isFinite(h)) {
                        h = INF;
                        s = -1.0;
                    }
                    rsh(ii, jj, sh);
                    g1 = h + sh[1] - TEMP_KELVIN * (s + sh[0]);
                    g2 = eH(ii, jj) + sh[1] - TEMP_KELVIN * ((eS(ii, jj) + sh[0]));
                    if ((g1 < g2) || (traceback == 1)) {
                        entropyEnthalpy[0] = s;
                        entropyEnthalpy[1] = h;
                    }
                } else { // no Jacobson-Stockmayer extrapolation: bulges of up to 30
                    h = p.bulgeLoopEnthalpies[loopSize] + atPenaltyH(numSeq1[i], numSeq2[j])
                            + atPenaltyH(numSeq1[ii], numSeq2[jj]);
                    h += eH(i, j);
                    s = p.bulgeLoopEntropies[loopSize] + atPenaltyS(numSeq1[i], numSeq2[j])
                            + atPenaltyS(numSeq1[ii], numSeq2[jj]);
                    s += eS(i, j);
                    if (!Double.isFinite(h)) {
                        h = INF;
                        s = -1.0;
                    }
                    if (isPositive(h) && isPositive(s)) {
                        h = INF;
                        s = -1.0;
                    }
                    rsh(ii, jj, sh);
                    g1 = h + sh[1] - TEMP_KELVIN * (s + sh[0]);
                    g2 = eH(ii, jj) + sh[1] - TEMP_KELVIN * (eS(ii, jj) + sh[0]);
                    if (g1 < g2 || (traceback == 1)) {
                        entropyEnthalpy[0] = s;
                        entropyEnthalpy[1] = h;
                    }
                }
            } else if (loopSize1 == 1 && loopSize2 == 1) {
                s = p.stackint2Entropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])]
                        + p.stackint2Entropies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj - 1], numSeq1[ii],
                        numSeq1[ii - 1])];
                s += eS(i, j);
                h = p.stackint2Enthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])]
                        + p.stackint2Enthalpies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj - 1], numSeq1[ii],
                        numSeq1[ii - 1])];
                h += eH(i, j);
                if (!Double.isFinite(h)) {
                    h = INF;
                    s = -1.0;
                }
                if (isPositive(h) && isPositive(s)) {
                    h = INF;
                    s = -1.0;
                }
                rsh(ii, jj, sh);
                g1 = h + sh[1] - TEMP_KELVIN * (s + sh[0]);
                g2 = eH(ii, jj) + sh[1] - TEMP_KELVIN * (eS(ii, jj) + sh[0]);
                if ((g1 < g2) || traceback == 1) {
                    entropyEnthalpy[0] = s;
                    entropyEnthalpy[1] = h;
                }
            } else { // only internal loops
                h = p.interiorLoopEnthalpies[loopSize]
                        + p.tstackEnthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])]
                        + p.tstackEnthalpies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj - 1], numSeq1[ii],
                        numSeq1[ii - 1])]
                        + (ILAH * Math.abs(loopSize1 - loopSize2));
                h += eH(i, j);
                s = p.interiorLoopEntropies[loopSize]
                        + p.tstackEntropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j + 1])]
                        + p.tstackEntropies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj - 1], numSeq1[ii],
                        numSeq1[ii - 1])]
                        + (ILAS * Math.abs(loopSize1 - loopSize2));
                s += eS(i, j);
                if (!Double.isFinite(h)) {
                    h = INF;
                    s = -1.0;
                }
                if (isPositive(h) && isPositive(s)) {
                    h = INF;
                    s = -1.0;
                }
                rsh(ii, jj, sh);
                g1 = h + sh[1] - TEMP_KELVIN * (s + sh[0]);
                g2 = eH(ii, jj) + sh[1] - TEMP_KELVIN * (eS(ii, jj) + sh[0]);
                if ((g1 < g2) || (traceback == 1)) {
                    entropyEnthalpy[0] = s;
                    entropyEnthalpy[1] = h;
                }
            }
        }

        /** {@code calc_bulge_internal2()}: a bulge or internal loop between pairs i-j and ii-jj of a hairpin. */
        private void calcBulgeInternal2(int i, int j, int ii, int jj, double[] entropyEnthalpy, int traceback) {
            double t1;
            double t2;
            double s = MIN_ENTROPY;
            double h = 0.0;
            int loopSize1 = ii - i - 1;
            int loopSize2 = j - jj - 1;
            if (loopSize1 + loopSize2 > maxLoop) {
                entropyEnthalpy[0] = -1.0;
                entropyEnthalpy[1] = INF;
                return;
            }
            int loopSize = loopSize1 + loopSize2 - 1; // for the index only
            boolean tb = traceback != 0;
            if ((loopSize1 == 0 && loopSize2 > 0) || (loopSize2 == 0 && loopSize1 > 0)) { // only bulges
                if (loopSize2 == 1 || loopSize1 == 1) {
                    if ((loopSize2 == 1 && loopSize1 == 0) || (loopSize2 == 0 && loopSize1 == 1)) {
                        int k = ThalParameters.idx4(numSeq1[i], numSeq1[ii], numSeq2[j], numSeq2[jj]);
                        h = p.bulgeLoopEnthalpies[loopSize] + p.stackEnthalpies[k];
                        s = p.bulgeLoopEntropies[loopSize] + p.stackEntropies[k];
                    }
                    if (traceback != 1) {
                        h += eH(ii, jj);
                        s += eS(ii, jj);
                    }
                    if (!Double.isFinite(h)) {
                        h = INF;
                        s = -1.0;
                    }
                    t1 = (h + dplxInitH) / ((s + dplxInitS) + rc);
                    t2 = (eH(i, j) + dplxInitH) / ((eS(i, j)) + dplxInitS + rc);
                    if ((t1 > t2) || ((tb && t1 >= t2) || traceback == 1)) {
                        entropyEnthalpy[0] = s;
                        entropyEnthalpy[1] = h;
                    }
                } else {
                    h = p.bulgeLoopEnthalpies[loopSize] + atPenaltyH(numSeq1[i], numSeq2[j])
                            + atPenaltyH(numSeq1[ii], numSeq2[jj]);
                    if (traceback != 1) {
                        h += eH(ii, jj);
                    }
                    s = p.bulgeLoopEntropies[loopSize] + atPenaltyS(numSeq1[i], numSeq2[j])
                            + atPenaltyS(numSeq1[ii], numSeq2[jj]);
                    if (traceback != 1) {
                        s += eS(ii, jj);
                    }
                    if (!Double.isFinite(h)) {
                        h = INF;
                        s = -1.0;
                    }
                    t1 = (h + dplxInitH) / ((s + dplxInitS) + rc);
                    t2 = (eH(i, j) + dplxInitH) / (eS(i, j) + dplxInitS + rc);
                    if ((t1 > t2) || ((tb && t1 >= t2) || (traceback == 1))) {
                        entropyEnthalpy[0] = s;
                        entropyEnthalpy[1] = h;
                    }
                }
            } else if (loopSize1 == 1 && loopSize2 == 1) { // mismatch nearest-neighbour parameters
                s = p.stackint2Entropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])]
                        + p.stackint2Entropies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj + 1], numSeq1[ii],
                        numSeq1[ii - 1])];
                if (traceback != 1) {
                    s += eS(ii, jj);
                }
                h = p.stackint2Enthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])]
                        + p.stackint2Enthalpies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj + 1], numSeq1[ii],
                        numSeq1[ii - 1])];
                if (traceback != 1) {
                    h += eH(ii, jj);
                }
                if (!Double.isFinite(h)) {
                    h = INF;
                    s = -1.0;
                }
                t1 = (h + dplxInitH) / ((s + dplxInitS) + rc);
                t2 = (eH(i, j) + dplxInitH) / (eS(i, j) + dplxInitS + rc);
                // DBL_EQ(T1, T2) == 2 in Primer3: T1 - T2 >= 1e-6, not |T1 - T2| (a one-sided test, kept).
                if (!(t1 - t2 < SMALL_NON_ZERO) || tb) {
                    if ((t1 > t2) || ((tb && t1 >= t2) || traceback == 1)) {
                        entropyEnthalpy[0] = s;
                        entropyEnthalpy[1] = h;
                    }
                }
            } else { // only internal loops
                h = p.interiorLoopEnthalpies[loopSize]
                        + p.tstackEnthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])]
                        + p.tstackEnthalpies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj + 1], numSeq1[ii],
                        numSeq1[ii - 1])]
                        + (ILAH * Math.abs(loopSize1 - loopSize2));
                if (traceback != 1) {
                    h += eH(ii, jj);
                }
                s = p.interiorLoopEntropies[loopSize]
                        + p.tstackEntropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq2[j], numSeq2[j - 1])]
                        + p.tstackEntropies[ThalParameters.idx4(numSeq2[jj], numSeq2[jj + 1], numSeq1[ii],
                        numSeq1[ii - 1])]
                        + (ILAS * Math.abs(loopSize1 - loopSize2));
                if (traceback != 1) {
                    s += eS(ii, jj);
                }
                if (!Double.isFinite(h)) {
                    h = INF;
                    s = -1.0;
                }
                t1 = (h + dplxInitH) / ((s + dplxInitS) + rc);
                t2 = (eH(i, j) + dplxInitH) / ((eS(i, j)) + dplxInitS + rc);
                if ((t1 > t2) || ((tb && t1 >= t2) || (traceback == 1))) {
                    entropyEnthalpy[0] = s;
                    entropyEnthalpy[1] = h;
                }
            }
        }

        /** {@code calc_terminal_bp()}: the exterior loop of a hairpin, base by base from the 5' end. */
        private void calcTerminalBp(double temp) {
            send5[0] = send5[1] = -1.0;
            hend5[0] = hend5[1] = INF;
            for (int i = 2; i <= len1; i++) {
                send5[i] = MIN_ENTROPY;
                hend5[i] = 0;
            }
            for (int i = 2; i <= len1; ++i) {
                // Primer3 calls each END5_x() twice (for H, then for S); they read only what is already set.
                double[] e1 = end5(1, i);
                double[] e2 = end5(2, i);
                double[] e3 = end5(3, i);
                double[] e4 = end5(4, i);
                double t1 = (hend5[i - 1] + dplxInitH) / (send5[i - 1] + dplxInitS + rc);
                double t2 = (e1[0] + dplxInitH) / (e1[1] + dplxInitS + rc);
                double t3 = (e2[0] + dplxInitH) / (e2[1] + dplxInitS + rc);
                double t4 = (e3[0] + dplxInitH) / (e3[1] + dplxInitS + rc);
                double t5 = (e4[0] + dplxInitH) / (e4[1] + dplxInitS + rc);
                int max = max5(t1, t2, t3, t4, t5);
                double[] e = switch (max) {
                    case 2 -> e1;
                    case 3 -> e2;
                    case 4 -> e3;
                    case 5 -> e4;
                    default -> null;
                };
                if (e == null) {
                    send5[i] = send5[i - 1];
                    hend5[i] = hend5[i - 1];
                } else {
                    double g = e[0] - (temp * (e[1]));
                    if (g < G2) {
                        send5[i] = e[1];
                        hend5[i] = e[0];
                    } else {
                        send5[i] = send5[i - 1];
                        hend5[i] = hend5[i - 1];
                    }
                }
            }
        }

        /**
         * {@code END5_1()} to {@code END5_4()}: the best way to close the exterior loop at base i: 1 with the
         * pair (k+1, i); 2 with a 5' dangling base; 3 with a 3' dangling base; 4 with a terminal mismatch.
         *
         * @return {H, S}
         */
        private double[] end5(int kind, int i) {
            double maxTm = -INF;
            double hMax = INF;
            double sMax = -1.0;
            int last = switch (kind) {
                case 1 -> i - MIN_HRPN_LOOP - 2;
                case 2, 3 -> i - MIN_HRPN_LOOP - 3;
                default -> i - MIN_HRPN_LOOP - 4;
            };
            double t2 = (0 + dplxInitH) / (0 + dplxInitS + rc);
            for (int k = 0; k <= last; ++k) {
                double t1 = (hend5[k] + dplxInitH) / (send5[k] + dplxInitS + rc);
                double h;
                double s;
                double hBase = t1 >= t2 ? hend5[k] : 0;
                double sBase = t1 >= t2 ? send5[k] : 0;
                switch (kind) {
                    case 1 -> {
                        h = hBase + atPenaltyH(numSeq1[k + 1], numSeq1[i]) + eH(k + 1, i);
                        s = sBase + atPenaltyS(numSeq1[k + 1], numSeq1[i]) + eS(k + 1, i);
                    }
                    case 2 -> {
                        h = hBase + atPenaltyH(numSeq1[k + 2], numSeq1[i]) + hd5(i, k + 2) + eH(k + 2, i);
                        s = sBase + atPenaltyS(numSeq1[k + 2], numSeq1[i]) + sd5(i, k + 2) + eS(k + 2, i);
                    }
                    case 3 -> {
                        h = hBase + atPenaltyH(numSeq1[k + 1], numSeq1[i - 1]) + hd3(i - 1, k + 1) + eH(k + 1, i - 1);
                        s = sBase + atPenaltyS(numSeq1[k + 1], numSeq1[i - 1]) + sd3(i - 1, k + 1) + eS(k + 1, i - 1);
                    }
                    default -> {
                        h = hBase + atPenaltyH(numSeq1[k + 2], numSeq1[i - 1]) + htstack(i - 1, k + 2)
                                + eH(k + 2, i - 1);
                        s = sBase + atPenaltyS(numSeq1[k + 2], numSeq1[i - 1]) + ststack(i - 1, k + 2)
                                + eS(k + 2, i - 1);
                    }
                }
                if (!Double.isFinite(h) || h > 0 || s > 0) { // H and S must be negative
                    h = INF;
                    s = -1.0;
                }
                t1 = (h + dplxInitH) / (s + dplxInitS + rc);
                if (maxTm < t1) {
                    if (s > MIN_ENTROPY_CUTOFF) {
                        hMax = h;
                        sMax = s;
                        maxTm = t1;
                    }
                }
            }
            return new double[]{hMax, sMax};
        }

        private double sd5(int i, int j) {
            return p.dangleEntropies5[ThalParameters.idx3(numSeq1[i], numSeq1[j], numSeq1[j - 1])];
        }

        private double hd5(int i, int j) {
            return p.dangleEnthalpies5[ThalParameters.idx3(numSeq1[i], numSeq1[j], numSeq1[j - 1])];
        }

        private double sd3(int i, int j) {
            return p.dangleEntropies3[ThalParameters.idx3(numSeq1[i], numSeq1[i + 1], numSeq1[j])];
        }

        private double hd3(int i, int j) {
            return p.dangleEnthalpies3[ThalParameters.idx3(numSeq1[i], numSeq1[i + 1], numSeq1[j])];
        }

        private double ststack(int i, int j) {
            return p.tstack2Entropies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq1[j], numSeq1[j - 1])];
        }

        private double htstack(int i, int j) {
            return p.tstack2Enthalpies[ThalParameters.idx4(numSeq1[i], numSeq1[i + 1], numSeq1[j], numSeq1[j - 1])];
        }

        /** {@code traceback()}: the pairs of the dimer's structure, from its last pair (i, j) back to its first. */
        private void traceback(int i, int j, int[] ps1, int[] ps2) {
            double[] sh = new double[2];
            ps1[i - 1] = j;
            ps2[j - 1] = i;
            while (true) {
                sh[0] = -1.0;
                sh[1] = INF;
                lsh(i, j, sh);
                if (equal(eS(i, j), sh[0]) && equal(eH(i, j), sh[1])) {
                    break;
                }
                boolean done = false;
                if (i > 1 && j > 1 && equal(eS(i, j), ss(i - 1, j - 1, 1) + eS(i - 1, j - 1))
                        && equal(eH(i, j), hs(i - 1, j - 1, 1) + eH(i - 1, j - 1))) {
                    i = i - 1;
                    j = j - 1;
                    ps1[i - 1] = j;
                    ps2[j - 1] = i;
                    done = true;
                }
                for (int d = 3; !done && d <= maxLoop + 2; ++d) {
                    int ii = i - 1;
                    int jj = -ii - d + (j + i);
                    if (jj < 1) {
                        ii -= Math.abs(jj - 1);
                        jj = 1;
                    }
                    for (; !done && ii > 0 && jj < j; --ii, ++jj) {
                        sh[0] = -1.0;
                        sh[1] = INF;
                        calcBulgeInternal(ii, jj, i, j, sh, 1);
                        if (equal(eS(i, j), sh[0]) && equal(eH(i, j), sh[1])) {
                            i = ii;
                            j = jj;
                            ps1[i - 1] = j;
                            ps2[j - 1] = i;
                            done = true;
                            break;
                        }
                    }
                }
                if (!done) {
                    // Primer3 would loop here for ever; it never happens with its tables.
                    break;
                }
            }
        }

        /** {@code tracebacku()}: the pairs of the hairpin's structure. */
        private void tracebacku(int[] bp) {
            double[] sh1 = new double[2];
            double[] sh2 = new double[2];
            double[] entropyEnthalpy = new double[2];
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{len1, 0, 1});
            while (!stack.isEmpty()) {
                int[] top = stack.pop();
                int i = top[0];
                int j = top[1];
                if (top[2] == 1) {
                    while (equal(send5[i], send5[i - 1]) && equal(hend5[i], hend5[i - 1])) {
                        --i; // the same structure as at the base before
                    }
                    if (i == 0) {
                        continue;
                    }
                    double[] e1 = end5(1, i);
                    double[] e2 = end5(2, i);
                    double[] e3 = end5(3, i);
                    double[] e4 = end5(4, i);
                    // Each sum is grouped as in Primer3 (left to right), so that equal() compares the same values.
                    if (equal(send5[i], e1[1]) && equal(hend5[i], e1[0])) {
                        for (int k = 0; k <= i - MIN_HRPN_LOOP - 2; ++k) {
                            double atS = atPenaltyS(numSeq1[k + 1], numSeq1[i]);
                            double atH = atPenaltyH(numSeq1[k + 1], numSeq1[i]);
                            if (equal(send5[i], atS + eS(k + 1, i)) && equal(hend5[i], atH + eH(k + 1, i))) {
                                stack.push(new int[]{k + 1, i, 0});
                                break;
                            } else if (equal(send5[i], send5[k] + atS + eS(k + 1, i))
                                    && equal(hend5[i], hend5[k] + atH + eH(k + 1, i))) {
                                stack.push(new int[]{k + 1, i, 0});
                                stack.push(new int[]{k, 0, 1});
                                break;
                            }
                        }
                    } else if (equal(send5[i], e2[1]) && equal(hend5[i], e2[0])) {
                        for (int k = 0; k <= i - MIN_HRPN_LOOP - 3; ++k) {
                            double atS = atPenaltyS(numSeq1[k + 2], numSeq1[i]);
                            double atH = atPenaltyH(numSeq1[k + 2], numSeq1[i]);
                            if (equal(send5[i], atS + sd5(i, k + 2) + eS(k + 2, i))
                                    && equal(hend5[i], atH + hd5(i, k + 2) + eH(k + 2, i))) {
                                stack.push(new int[]{k + 2, i, 0});
                                break;
                            } else if (equal(send5[i], send5[k] + atS + sd5(i, k + 2) + eS(k + 2, i))
                                    && equal(hend5[i], hend5[k] + atH + hd5(i, k + 2) + eH(k + 2, i))) {
                                stack.push(new int[]{k + 2, i, 0});
                                stack.push(new int[]{k, 0, 1});
                                break;
                            }
                        }
                    } else if (equal(send5[i], e3[1]) && equal(hend5[i], e3[0])) {
                        for (int k = 0; k <= i - MIN_HRPN_LOOP - 3; ++k) {
                            double atS = atPenaltyS(numSeq1[k + 1], numSeq1[i - 1]);
                            double atH = atPenaltyH(numSeq1[k + 1], numSeq1[i - 1]);
                            if (equal(send5[i], atS + sd3(i - 1, k + 1) + eS(k + 1, i - 1))
                                    && equal(hend5[i], atH + hd3(i - 1, k + 1) + eH(k + 1, i - 1))) {
                                stack.push(new int[]{k + 1, i - 1, 0});
                                break;
                            } else if (equal(send5[i], send5[k] + atS + sd3(i - 1, k + 1) + eS(k + 1, i - 1))
                                    && equal(hend5[i], hend5[k] + atH + hd3(i - 1, k + 1) + eH(k + 1, i - 1))) {
                                stack.push(new int[]{k + 1, i - 1, 0});
                                stack.push(new int[]{k, 0, 1});
                                break;
                            }
                        }
                    } else if (equal(send5[i], e4[1]) && equal(hend5[i], e4[0])) {
                        for (int k = 0; k <= i - MIN_HRPN_LOOP - 4; ++k) {
                            double atS = atPenaltyS(numSeq1[k + 2], numSeq1[i - 1]);
                            double atH = atPenaltyH(numSeq1[k + 2], numSeq1[i - 1]);
                            if (equal(send5[i], atS + ststack(i - 1, k + 2) + eS(k + 2, i - 1))
                                    && equal(hend5[i], atH + htstack(i - 1, k + 2) + eH(k + 2, i - 1))) {
                                stack.push(new int[]{k + 2, i - 1, 0});
                                break;
                            } else if (equal(send5[i], send5[k] + atS + ststack(i - 1, k + 2) + eS(k + 2, i - 1))
                                    && equal(hend5[i], hend5[k] + atH + htstack(i - 1, k + 2) + eH(k + 2, i - 1))) {
                                stack.push(new int[]{k + 2, i - 1, 0});
                                stack.push(new int[]{k, 0, 1});
                                break;
                            }
                        }
                    }
                } else {
                    bp[i - 1] = j;
                    bp[j - 1] = i;
                    sh1[0] = -1.0;
                    sh1[1] = INF;
                    calcHairpin(i, j, sh1, 1);
                    sh2[0] = -1.0;
                    sh2[1] = INF;
                    cbi(i, j, sh2, 2);
                    if (equal(eS(i, j), ss(i, j, 2) + eS(i + 1, j - 1))
                            && equal(eH(i, j), hs(i, j, 2) + eH(i + 1, j - 1))) {
                        stack.push(new int[]{i + 1, j - 1, 0});
                    } else if (equal(eS(i, j), sh1[0]) && equal(eH(i, j), sh1[1])) {
                        // a hairpin loop: nothing inside
                    } else if (equal(eS(i, j), sh2[0]) && equal(eH(i, j), sh2[1])) {
                        boolean done = false;
                        for (int d = j - i - 3; d >= MIN_HRPN_LOOP + 1 && d >= j - i - 2 - maxLoop && !done; --d) {
                            for (int ii = i + 1; ii < j - d; ++ii) {
                                int jj = d + ii;
                                entropyEnthalpy[0] = -1.0;
                                entropyEnthalpy[1] = INF;
                                calcBulgeInternal2(i, j, ii, jj, entropyEnthalpy, 1);
                                if (equal(eS(i, j), entropyEnthalpy[0] + eS(ii, jj))
                                        && equal(eH(i, j), entropyEnthalpy[1] + eH(ii, jj))) {
                                    stack.push(new int[]{ii, jj, 0});
                                    done = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }

        /** {@code drawDimer()}: the Tm, and the 4 lines {@code ntthal} draws. */
        private Result drawDimer(int[] ps1, int[] ps2, double h, double s, int bestI, int bestJ) {
            int n = 0;
            for (int i = 0; i < len1; i++) {
                if (ps1[i] > 0) {
                    ++n;
                }
            }
            for (int i = 0; i < len2; i++) {
                if (ps2[i] > 0) {
                    ++n;
                }
            }
            n = (n / 2) - 1;
            double t = ((h) / (s + (n * saltCorrection) + rc)) - ABSOLUTE_ZERO;
            double g = (h) - (a.temperatureKelvin() * (s + (n * saltCorrection)));
            double sCorrected = s + (n * saltCorrection);

            StringBuilder[] duplex = {new StringBuilder(), new StringBuilder(), new StringBuilder(), new StringBuilder()};
            int i = 0;
            int numSS1 = 0;
            while (ps1[i++] == 0) {
                ++numSS1;
            }
            int j = 0;
            int numSS2 = 0;
            while (ps2[j++] == 0) {
                ++numSS2;
            }
            if (numSS1 >= numSS2) {
                for (i = 0; i < numSS1; ++i) {
                    duplex[0].append(oligo1[i]);
                    duplex[1].append(' ');
                    duplex[2].append(' ');
                }
                for (j = 0; j < numSS1 - numSS2; ++j) {
                    duplex[3].append(' ');
                }
                for (j = 0; j < numSS2; ++j) {
                    duplex[3].append(oligo2[j]);
                }
            } else {
                for (j = 0; j < numSS2; ++j) {
                    duplex[3].append(oligo2[j]);
                    duplex[1].append(' ');
                    duplex[2].append(' ');
                }
                for (i = 0; i < numSS2 - numSS1; ++i) {
                    duplex[0].append(' ');
                }
                for (i = 0; i < numSS1; ++i) {
                    duplex[0].append(oligo1[i]);
                }
            }
            i = numSS1 + 1;
            j = numSS2 + 1;
            while (i <= len1) {
                int before = i + j;
                while (i <= len1 && ps1[i - 1] != 0 && j <= len2 && ps2[j - 1] != 0) {
                    duplex[0].append(' ');
                    duplex[1].append(oligo1[i - 1]);
                    duplex[2].append(oligo2[j - 1]);
                    duplex[3].append(' ');
                    ++i;
                    ++j;
                }
                numSS1 = 0;
                while (i <= len1 && ps1[i - 1] == 0) {
                    duplex[0].append(oligo1[i - 1]);
                    duplex[1].append(' ');
                    ++numSS1;
                    ++i;
                }
                numSS2 = 0;
                while (j <= len2 && ps2[j - 1] == 0) {
                    duplex[2].append(' ');
                    duplex[3].append(oligo2[j - 1]);
                    ++numSS2;
                    ++j;
                }
                if (numSS1 < numSS2) {
                    for (int k = 0; k < numSS2 - numSS1; ++k) {
                        duplex[0].append('-');
                        duplex[1].append(' ');
                    }
                } else if (numSS1 > numSS2) {
                    for (int k = 0; k < numSS1 - numSS2; ++k) {
                        duplex[2].append(' ');
                        duplex[3].append('-');
                    }
                }
                if (i + j == before) {
                    break; // no progress: Primer3 would loop for ever
                }
            }
            return new Result(t, sCorrected, h, g, bestI, bestJ, List.of(duplex[0].toString(), duplex[1].toString(),
                    duplex[2].toString(), duplex[3].toString()), "");
        }

        /** {@code drawHairpin()}: the Tm, and the 2 lines {@code ntthal} draws. */
        private Result drawHairpin(int[] bp, double mh, double ms, int alignEnd1, int alignEnd2) {
            if (!Double.isFinite(ms) || !Double.isFinite(mh)) {
                return Result.none("", alignEnd1, alignEnd2);
            }
            int n = 0;
            // Primer3 counts the paired bases but the last, then halves with an integer division: kept.
            for (int i = 1; i < len1; ++i) {
                if (bp[i - 1] > 0) {
                    n++;
                }
            }
            double t = (mh / (ms + (((n / 2) - 1) * saltCorrection))) - ABSOLUTE_ZERO;
            double mg = mh - (a.temperatureKelvin() * (ms + (((n / 2) - 1) * saltCorrection)));
            double msCorrected = ms + (((n / 2) - 1) * saltCorrection);
            char[] asciiRow = new char[len1];
            java.util.Arrays.fill(asciiRow, '0');
            for (int i = 1; i < len1 + 1; ++i) {
                if (bp[i - 1] == 0) {
                    asciiRow[(i - 1)] = '-';
                } else if (bp[i - 1] > (i - 1)) {
                    asciiRow[(bp[i - 1] - 1)] = '\\';
                } else {
                    asciiRow[(bp[i - 1] - 1)] = '/';
                }
            }
            return new Result(t, msCorrected, mh, mg, alignEnd1, alignEnd2,
                    List.of(new String(asciiRow), new String(oligo1)), "");
        }
    }
}
