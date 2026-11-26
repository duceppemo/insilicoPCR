package ca.canada.inspection.thermo;

import ca.canada.inspection.thermo.Thal.Args;
import ca.canada.inspection.thermo.Thal.Result;
import ca.canada.inspection.thermo.Thal.Type;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThalTest {

    /** ntthal -r prints the Tm with 6 decimals. */
    private static final double PRINTED_TM = 1e-6;

    private record Row(String kind, String mode, String args, String s1, String s2, String tm, String dS, String dH,
                       String dG, String t, String structure) {
        String where() {
            return kind + " " + mode + " [" + args + "] " + s1 + " / " + s2;
        }
    }

    private static List<Row> fixture() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(ThalTest.class.getResourceAsStream("/thermo/ntthal.tsv")),
                StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] f = line.split("\t", -1);
                rows.add(new Row(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], f[10]));
            }
        }
        return rows;
    }

    /** ntthal's options, with its defaults. */
    private static Args args(Row row) {
        double mv = 50;
        double dv = 0;
        double n = 0.8;
        double d = 50;
        double t = Thal.TEMP_KELVIN;
        int maxLoop = Thal.MAX_LOOP;
        String[] a = row.args().isBlank() ? new String[0] : row.args().trim().split(" ");
        for (int i = 0; i < a.length; i += 2) {
            double value = Double.parseDouble(a[i + 1]);
            switch (a[i]) {
                case "-mv" -> mv = value;
                case "-dv" -> dv = value;
                case "-n" -> n = value;
                case "-d" -> d = value;
                case "-t" -> t = value + Thal.ABSOLUTE_ZERO;
                case "-maxloop" -> maxLoop = (int) value;
                default -> throw new IllegalArgumentException(a[i]);
            }
        }
        return new Args(Type.valueOf(row.mode()), maxLoop, mv, dv, n, d, t);
    }

    private static Result run(Row row) {
        Args args = args(row);
        return args.type() == Type.HAIRPIN ? Thal.thal(row.s1(), row.s1(), args) : Thal.thal(row.s1(), row.s2(), args);
    }

    /** Half a unit in the last of the 6 significant digits C's %g printed. */
    private static double gTolerance(double printed) {
        if (printed == 0) {
            return 1e-12;
        }
        int exponent = (int) Math.floor(Math.log10(Math.abs(printed)));
        return 0.5 * Math.pow(10, exponent - 5) * 1.0001;
    }

    private static void assertG(String printed, double actual, String what, Row row) {
        double expected = Double.parseDouble(printed);
        assertEquals(expected, actual, gTolerance(expected), what + ": " + row.where());
    }

    @Test
    void givesNtthalsNumbersAndStructures() throws IOException {
        List<Row> rows = fixture();
        assertEquals(3952, rows.size());
        double largestTmDeviation = 0;
        int structures = 0;
        for (Row row : rows) {
            Result result = run(row);
            if (row.tm().equals("ERROR")) {
                assertEquals(Double.NEGATIVE_INFINITY, result.tm(), row.where());
                assertFalse(result.message().isEmpty(), row.where());
                continue;
            }
            double tm = Double.parseDouble(row.tm());
            largestTmDeviation = Math.max(largestTmDeviation, Math.abs(tm - result.tm()));
            assertEquals(tm, result.tm(), PRINTED_TM, "Tm: " + row.where());
            if (row.dS().equals("NA")) {
                assertFalse(result.found(), row.where());
                continue;
            }
            structures++;
            assertTrue(result.found(), row.where());
            assertG(row.dS(), result.dS(), "dS", row);
            assertG(row.dH(), result.dH(), "dH", row);
            assertG(row.dG(), result.dG(), "dG", row);
            assertG(row.t(), result.tm(), "t", row);
            assertEquals(Arrays.asList(row.structure().split("\\|", -1)), result.structure(),
                    "structure: " + row.where());
        }
        System.out.printf("ntthal fixture: %d rows, %d with a structure, largest Tm deviation %.3g%n",
                rows.size(), structures, largestTmDeviation);
    }

    @Test
    void aPrimerOnItsPerfectComplement() {
        Result result = Thal.thal("ACGGACACATGCCCGCTA", "TAGCGGGCATGTGTCCGT", Args.DEFAULTS);
        assertTrue(result.found());
        assertEquals(55.528453, result.tm(), PRINTED_TM);
        assertEquals(-141300, result.dH(), 1e-6);
        assertEquals(18, result.alignEnd1());
        assertEquals(18, result.alignEnd2());
        assertEquals(List.of("                  ", "ACGGACACATGCCCGCTA", "TGCCTGTGTACGGGCGAT", "                  "),
                result.structure());
    }

    @Test
    void aMismatchLowersTheTm() {
        String primer = "ACGGACACATGCCCGCTA";
        double perfect = Thal.thal(primer, "TAGCGGGCATGTGTCCGT", Args.DEFAULTS).tm();
        double middle = Thal.thal(primer, "TAGCGGGCAGGTGTCCGT", Args.DEFAULTS).tm();
        assertTrue(middle < perfect - 3, middle + " vs " + perfect);
    }

    @Test
    void errorsAndEmptySequencesAreReportedAsPrimer3Does() {
        Result tooLong = Thal.thal("A".repeat(61), "T".repeat(61), Args.DEFAULTS);
        assertEquals(Double.NEGATIVE_INFINITY, tooLong.tm());
        assertEquals("Both sequences longer than 60 for thermodynamic alignment", tooLong.message());
        assertTrue(Thal.thal("A".repeat(Thal.MAX_SEQ + 1), "ACGT", Args.DEFAULTS).message().endsWith("(1)"));
        assertTrue(Thal.thal("ACGT", "A".repeat(Thal.MAX_SEQ + 1), Args.DEFAULTS).message().endsWith("(2)"));
        Result empty1 = Thal.thal("", "ACGT", Args.DEFAULTS);
        assertEquals(0.0, empty1.tm());
        assertEquals("Empty first sequence", empty1.message());
        assertEquals("Empty second sequence", Thal.thal("ACGT", "", Args.DEFAULTS).message());
        assertFalse(empty1.found());
        assertTrue(Double.isNaN(empty1.dS()));
    }

    @Test
    void maxLoopIsZeroToThirty() {
        assertThrows(IllegalArgumentException.class, () -> new Args(Type.ANY, 31, 50, 0, 0.8, 50, 310.15));
        assertThrows(IllegalArgumentException.class, () -> new Args(Type.ANY, -1, 50, 0, 0.8, 50, 310.15));
        assertEquals(Type.HAIRPIN, Args.DEFAULTS.withType(Type.HAIRPIN).type());
    }

    @Test
    void helpersBehaveAsPrimer3s() {
        assertTrue(Thal.symmetryThermo("gaattc"));
        assertFalse(Thal.symmetryThermo("GAATTG"));
        assertFalse(Thal.symmetryThermo("GAATC"));
        assertEquals(0, Thal.str2int('0'));
        assertEquals(3, Thal.str2int('T'));
        assertEquals(4, Thal.str2int('a'));
        // no divalent cations: dNTP do not count
        assertEquals(0.368 * Math.log(0.05), Thal.saltCorrectS(50, 0, 0.8), 1e-15);
        // dNTP above the divalent cations: no free magnesium
        assertEquals(0.368 * Math.log(0.05), Thal.saltCorrectS(50, 1, 2), 1e-15);
        assertSame(ThalParameters.defaults(), ThalParameters.defaults());
    }

    @Test
    void isThreadSafe() throws Exception {
        List<Row> rows = fixture().subList(0, 400);
        List<Result> sequential = rows.stream().map(ThalTest::run).toList();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Result>> futures = new ArrayList<>();
            for (Row row : rows) {
                futures.add(pool.submit(() -> run(row)));
            }
            for (int k = 0; k < rows.size(); k++) {
                assertEquals(sequential.get(k), futures.get(k).get(), rows.get(k).where());
            }
        } finally {
            pool.shutdown();
        }
    }
}
