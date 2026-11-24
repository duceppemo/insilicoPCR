package ca.canada.inspection.thermo;

import ca.canada.inspection.thermo.OligoTm.Conditions;
import ca.canada.inspection.thermo.OligoTm.Method;
import ca.canada.inspection.thermo.OligoTm.SaltCorrection;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OligoTmTest {

    /** oligotm prints 6 decimals. */
    private static final double PRINTED = 1e-6;

    private record Row(String sequence, String args, String tm) {
    }

    private static List<Row> fixture() throws IOException {
        List<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(OligoTmTest.class.getResourceAsStream("/thermo/oligotm.tsv")),
                StandardCharsets.UTF_8))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                rows.add(new Row(fields[0], fields[1], fields[2]));
            }
        }
        return rows;
    }

    /** The oligotm program's options, with its defaults. */
    private static double[] run(Row row) {
        double mv = 50;
        double dv = 1.5;
        double n = 0.6;
        double d = 50;
        int tp = 1;
        int sc = 1;
        String[] args = row.args().isBlank() ? new String[0] : row.args().trim().split(" ");
        for (int i = 0; i < args.length; i += 2) {
            double value = Double.parseDouble(args[i + 1]);
            switch (args[i]) {
                case "-mv" -> mv = value;
                case "-dv" -> dv = value;
                case "-n" -> n = value;
                case "-d" -> d = value;
                case "-tp" -> tp = (int) value;
                case "-sc" -> sc = (int) value;
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        OligoTm.Result result = OligoTm.oligotm(row.sequence(), new Conditions(d, mv, dv, n, 0, 0.6, 0),
                Method.values()[tp], SaltCorrection.values()[sc], -10.0);
        return new double[]{result.tm(), result.bound()};
    }

    @Test
    void givesPrimer3sOligotmNumbers() throws IOException {
        List<Row> rows = fixture();
        assertEquals(539, rows.size());
        for (Row row : rows) {
            double tm = run(row)[0];
            String where = row.sequence() + " [" + row.args() + "]";
            if (row.tm().equals("ERROR")) {
                assertEquals(OligoTm.ERROR, tm, where);
            } else {
                assertEquals(Double.parseDouble(row.tm()), tm, PRINTED, where);
            }
        }
    }

    @Test
    void defaultsAreOligotmsDefaults() {
        assertEquals(OligoTm.oligotm("ACGGACACATGCCCGCTA", Conditions.PRIMER3_DEFAULTS, Method.SANTALUCIA,
                SaltCorrection.SANTALUCIA, -10.0).tm(), OligoTm.tm("ACGGACACATGCCCGCTA", Conditions.PRIMER3_DEFAULTS));
    }

    @Test
    void symmetryIsReverseComplementOfItself() {
        assertTrue(OligoTm.symmetry("GAATTC"));
        assertFalse(OligoTm.symmetry("GAATTG"));
        assertFalse(OligoTm.symmetry("GAATC"));
    }

    @Test
    void boundIsComputedOnlyWithAnAnnealingTemperature() {
        OligoTm.Result without = OligoTm.oligotm("ACGGACACATGCCCGCTA", Conditions.PRIMER3_DEFAULTS,
                Method.SANTALUCIA, SaltCorrection.SANTALUCIA, -10.0);
        OligoTm.Result at60 = OligoTm.oligotm("ACGGACACATGCCCGCTA", Conditions.PRIMER3_DEFAULTS,
                Method.SANTALUCIA, SaltCorrection.SANTALUCIA, 60.0);
        assertEquals(OligoTm.ERROR, without.bound());
        assertTrue(at60.bound() > 0 && at60.bound() < 100, String.valueOf(at60.bound()));
        // Half bound at the Tm, by definition.
        OligoTm.Result atTm = OligoTm.oligotm("ACGGACACATGCCCGCTA", Conditions.PRIMER3_DEFAULTS,
                Method.SANTALUCIA, SaltCorrection.SANTALUCIA, without.tm());
        assertEquals(50.0, atTm.bound(), 1e-6);
    }

    @Test
    void longSequencesUseTheGcFormula() {
        String seq = "ACGTACGTACGTACGTACGTACGTACGTACGTACGTACGTACGT";
        assertEquals(OligoTm.longSeqTm(seq, 0, seq.length(), Conditions.PRIMER3_DEFAULTS).tm(),
                OligoTm.seqtm(seq, Conditions.PRIMER3_DEFAULTS, 36, Method.SANTALUCIA, SaltCorrection.SANTALUCIA,
                        -10.0).tm());
        // 81.5 + 16.6 log10((50 + 120 sqrt(0.9)) / 1000) + 41 * 0.5 - 600 / 44
        double salt = 50 + 120 * Math.sqrt(1.5 - 0.6);
        assertEquals(81.5 + 16.6 * Math.log10(salt / 1000) + 20.5 - 600.0 / 44,
                OligoTm.longSeqTm(seq, 0, seq.length(), Conditions.PRIMER3_DEFAULTS).tm(), 1e-9);
    }

    @Test
    void deltaGOfDisruption() {
        // SantaLucia: initiation -1.96, terminal A/T -0.05 at each end, then the pairs.
        assertEquals((-1960 - 50 + 1000 + 880 - 50) / 1000.0, OligoTm.oligodg("AAT", Method.SANTALUCIA), 1e-12);
        assertEquals(OligoTm.oligodg("CAT", Method.SANTALUCIA), OligoTm.endOligodg("GGCAT", 3, Method.SANTALUCIA));
        assertEquals(OligoTm.ERROR, OligoTm.oligodg("AXT", Method.BRESLAUER));
    }
}
