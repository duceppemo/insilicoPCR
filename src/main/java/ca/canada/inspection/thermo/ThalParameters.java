/*
 * insilicoPCR: a Java port of the parameter reading of Primer3's thal library (src/thal.c, Primer3 2.6.1).
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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * The thermodynamic parameter tables of Primer3's thal (stacks, mismatches, dangling ends, terminal
 * mismatches, loops, triloop and tetraloop bonuses), read as {@code get_thermodynamic_values()} reads them.
 * Immutable once read, so one instance serves every thread: {@link #defaults()}.
 *
 * <p>Bases are numbered as in Primer3: A 0, C 1, G 2, T 3, anything else 4 ("N"). The four-dimensional
 * tables are flattened: {@code [a][b][c][d]} is at {@code ((a * 5 + b) * 5 + c) * 5 + d}.</p>
 */
public final class ThalParameters {

    /** Where the 16 files from Primer3's {@code primer3_config} directory are, on the class path. */
    static final String RESOURCE_DIR = "/thermo/primer3_config/";

    /** Primer3's AT penalty: enthalpy, cal/mol. */
    static final double AT_H = 2200.0;
    /** Primer3's AT penalty: entropy, cal/K/mol. */
    static final double AT_S = 6.9;

    final double[] stackEntropies = new double[625];
    final double[] stackEnthalpies = new double[625];
    final double[] stackint2Entropies = new double[625];
    final double[] stackint2Enthalpies = new double[625];
    /** 3' dangling ends, {@code [a][b][c]} at {@code (a * 5 + b) * 5 + c}. */
    final double[] dangleEntropies3 = new double[125];
    final double[] dangleEnthalpies3 = new double[125];
    /** 5' dangling ends. */
    final double[] dangleEntropies5 = new double[125];
    final double[] dangleEnthalpies5 = new double[125];
    final double[] tstackEntropies = new double[625];
    final double[] tstackEnthalpies = new double[625];
    final double[] tstack2Entropies = new double[625];
    final double[] tstack2Enthalpies = new double[625];
    final double[] interiorLoopEntropies = new double[30];
    final double[] bulgeLoopEntropies = new double[30];
    final double[] hairpinLoopEntropies = new double[30];
    final double[] interiorLoopEnthalpies = new double[30];
    final double[] bulgeLoopEnthalpies = new double[30];
    final double[] hairpinLoopEnthalpies = new double[30];
    /** Triloop bonuses by the 5 base codes of loop and closing pair, packed by {@link #loopKey}. */
    final Map<Long, Double> triloopEntropies = new HashMap<>();
    final Map<Long, Double> triloopEnthalpies = new HashMap<>();
    /** Tetraloop bonuses by the 6 base codes of loop and closing pair. */
    final Map<Long, Double> tetraloopEntropies = new HashMap<>();
    final Map<Long, Double> tetraloopEnthalpies = new HashMap<>();
    /** AT penalties, {@code [a][b]} at {@code a * 5 + b}. */
    final double[] atpS = new double[25];
    final double[] atpH = new double[25];

    private static final class Holder {
        private static final ThalParameters DEFAULTS = fromResources();
    }

    private ThalParameters() {
    }

    /** Primer3's default tables, those {@code ntthal} uses without {@code -path}. */
    public static ThalParameters defaults() {
        return Holder.DEFAULTS;
    }

    static int idx4(int a, int b, int c, int d) {
        return ((a * 5 + b) * 5 + c) * 5 + d;
    }

    static int idx3(int a, int b, int c) {
        return (a * 5 + b) * 5 + c;
    }

    /** The key of a triloop (5 codes) or tetraloop (6 codes) starting at {@code codes[from]}. */
    static long loopKey(int[] codes, int from, int length) {
        long key = length;
        for (int k = 0; k < length; k++) {
            key = key * 8 + codes[from + k];
        }
        return key;
    }

    private static ThalParameters fromResources() {
        ThalParameters p = new ThalParameters();
        p.readStack(new Lines(read("stack.ds")), new Lines(read("stack.dh")), p.stackEntropies, p.stackEnthalpies);
        p.readStack(new Lines(read("stackmm.ds")), new Lines(read("stackmm.dh")), p.stackint2Entropies,
                p.stackint2Enthalpies);
        p.readDangle(new Lines(read("dangle.ds")), new Lines(read("dangle.dh")));
        p.readLoops(new Lines(read("loops.ds")), new Lines(read("loops.dh")));
        p.readTstack(new Lines(read("tstack_tm_inf.ds")), new Lines(read("tstack.dh")), p.tstackEntropies,
                p.tstackEnthalpies);
        p.readTstack(new Lines(read("tstack2.ds")), new Lines(read("tstack2.dh")), p.tstack2Entropies,
                p.tstack2Enthalpies);
        readTLoops(new Lines(read("triloop.ds")), 5, p.triloopEntropies);
        readTLoops(new Lines(read("triloop.dh")), 5, p.triloopEnthalpies);
        readTLoops(new Lines(read("tetraloop.ds")), 6, p.tetraloopEntropies);
        readTLoops(new Lines(read("tetraloop.dh")), 6, p.tetraloopEnthalpies);
        // tableStartATS and tableStartATH
        for (int k = 0; k < 25; k++) {
            p.atpS[k] = 0.00000000001;
            p.atpH[k] = 0.0;
        }
        p.atpS[3] = p.atpS[15] = AT_S;
        p.atpH[3] = p.atpH[15] = AT_H;
        return p;
    }

    private static String read(String name) {
        try (InputStream in = ThalParameters.class.getResourceAsStream(RESOURCE_DIR + name)) {
            if (in == null) {
                throw new IllegalStateException("Unable to open file " + RESOURCE_DIR + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code th_read_str_line()}: the file's lines, one at a time; {@code null} at an empty line or the end. */
    private static final class Lines {
        private final String text;
        private int at;

        Lines(String text) {
            this.text = text;
        }

        String next() {
            if (at < 0 || at >= text.length()) {
                return null;
            }
            int end = text.indexOf('\n', at);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(at, end);
            at = end + 1 >= text.length() ? -1 : end + 1;
            return line.isEmpty() ? null : line;
        }
    }

    /** {@code readDouble()}: a value per line, {@code inf} for infinity. */
    private static double readDouble(Lines lines) {
        String line = lines.next();
        if (line == null) {
            throw new IllegalStateException("thermodynamic parameter file ends early");
        }
        line = line.stripLeading();
        if (line.startsWith("inf")) {
            return Double.POSITIVE_INFINITY;
        }
        return parseLeadingDouble(line);
    }

    /** What {@code sscanf("%lf")} takes from the start of {@code s}. */
    private static double parseLeadingDouble(String s) {
        int end = 0;
        while (end < s.length() && "+-.0123456789eE".indexOf(s.charAt(end)) >= 0) {
            end++;
        }
        return Double.parseDouble(s.substring(0, end));
    }

    private static double readValue(String token) {
        return token.equals("inf") ? Double.POSITIVE_INFINITY : parseLeadingDouble(token);
    }

    private void readStack(Lines ds, Lines dh, double[] entropies, double[] enthalpies) {
        for (int i = 0; i < 5; ++i) {
            for (int ii = 0; ii < 5; ++ii) {
                for (int j = 0; j < 5; ++j) {
                    for (int jj = 0; jj < 5; ++jj) {
                        int k = idx4(i, ii, j, jj);
                        if (i == 4 || j == 4 || ii == 4 || jj == 4) {
                            entropies[k] = -1.0;
                            enthalpies[k] = Double.POSITIVE_INFINITY;
                        } else {
                            entropies[k] = readDouble(ds);
                            enthalpies[k] = readDouble(dh);
                            if (!Double.isFinite(entropies[k]) || !Double.isFinite(enthalpies[k])) {
                                entropies[k] = -1.0;
                                enthalpies[k] = Double.POSITIVE_INFINITY;
                            }
                        }
                    }
                }
            }
        }
    }

    private void readDangle(Lines ds, Lines dh) {
        // The 3' table is filled as [i][k][j] while the files list it in the order i, j, k: kept as Primer3 reads it.
        for (int i = 0; i < 5; ++i) {
            for (int j = 0; j < 5; ++j) {
                for (int k = 0; k < 5; ++k) {
                    int n = idx3(i, k, j);
                    if (i == 4 || j == 4 || k == 4) {
                        dangleEntropies3[n] = -1.0;
                        dangleEnthalpies3[n] = Double.POSITIVE_INFINITY;
                    } else {
                        dangleEntropies3[n] = readDouble(ds);
                        dangleEnthalpies3[n] = readDouble(dh);
                        if (!Double.isFinite(dangleEntropies3[n]) || !Double.isFinite(dangleEnthalpies3[n])) {
                            dangleEntropies3[n] = -1.0;
                            dangleEnthalpies3[n] = Double.POSITIVE_INFINITY;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 5; ++i) {
            for (int j = 0; j < 5; ++j) {
                for (int k = 0; k < 5; ++k) {
                    int n = idx3(i, j, k);
                    if (i == 4 || j == 4 || k == 4) {
                        dangleEntropies5[n] = -1.0;
                        dangleEnthalpies5[n] = Double.POSITIVE_INFINITY;
                    } else {
                        dangleEntropies5[n] = readDouble(ds);
                        dangleEnthalpies5[n] = readDouble(dh);
                        if (!Double.isFinite(dangleEntropies5[n]) || !Double.isFinite(dangleEnthalpies5[n])) {
                            dangleEntropies5[n] = -1.0;
                            dangleEnthalpies5[n] = Double.POSITIVE_INFINITY;
                        }
                    }
                }
            }
        }
    }

    /** {@code getLoop()}: per loop length, the interior, bulge and hairpin values (after the length itself). */
    private void readLoops(Lines ds, Lines dh) {
        for (int k = 0; k < 30; ++k) {
            double[] s = readLoop(ds);
            interiorLoopEntropies[k] = s[0];
            bulgeLoopEntropies[k] = s[1];
            hairpinLoopEntropies[k] = s[2];
            double[] h = readLoop(dh);
            interiorLoopEnthalpies[k] = h[0];
            bulgeLoopEnthalpies[k] = h[1];
            hairpinLoopEnthalpies[k] = h[2];
        }
    }

    private static double[] readLoop(Lines lines) {
        String line = lines.next();
        if (line == null) {
            throw new IllegalStateException("thermodynamic loop file ends early");
        }
        String[] fields = line.strip().split("\\s+");
        return new double[]{readValue(fields[1]), readValue(fields[2]), readValue(fields[3])};
    }

    private void readTstack(Lines ds, Lines dh, double[] entropies, double[] enthalpies) {
        for (int i1 = 0; i1 < 5; ++i1) {
            for (int i2 = 0; i2 < 5; ++i2) {
                for (int j1 = 0; j1 < 5; ++j1) {
                    for (int j2 = 0; j2 < 5; ++j2) {
                        int k = idx4(i1, i2, j1, j2);
                        if (i1 == 4 || j1 == 4) {
                            enthalpies[k] = Double.POSITIVE_INFINITY;
                            entropies[k] = -1.0;
                        } else if (i2 == 4 || j2 == 4) {
                            entropies[k] = 0.00000000001;
                            enthalpies[k] = 0.0;
                        } else {
                            entropies[k] = readDouble(ds);
                            enthalpies[k] = readDouble(dh);
                            if (!Double.isFinite(entropies[k]) || !Double.isFinite(enthalpies[k])) {
                                entropies[k] = -1.0;
                                enthalpies[k] = Double.POSITIVE_INFINITY;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@code getTriloop()} and {@code getTetraloop()}. Primer3 finds a loop by binary search in the file's order;
     * the files are sorted with one line per loop, so a lookup by key finds the same value.
     */
    private static void readTLoops(Lines lines, int length, Map<Long, Double> into) {
        String line;
        while ((line = lines.next()) != null) {
            String trimmed = line.stripLeading();
            int end = 0;
            while (end < trimmed.length() && Character.isLetter(trimmed.charAt(end))) {
                end++;
            }
            String loop = trimmed.substring(0, Math.min(end, length));
            String rest = trimmed.substring(Math.min(end + 1, trimmed.length())).strip();
            int[] codes = new int[length];
            for (int k = 0; k < length; k++) {
                codes[k] = k < loop.length() ? Thal.str2int(loop.charAt(k)) : 4;
            }
            into.put(loopKey(codes, 0, length), readValue(rest.split("\\s+")[0]));
        }
    }
}
