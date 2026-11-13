package ca.canada.inspection.insilicopcr.ui;

import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pipeline.TadpoleOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsRecordsTest {

    @Test
    void defaultAmpliconSettingsMatchCommandLineDefaults() {
        AmpliconOptions gui = AmpliconSettings.DEFAULTS.toOptions(2);
        AmpliconOptions cli = AmpliconOptions.withMismatches(2);
        assertEquals(cli, gui, "blank probe mismatches follow the primer mismatches, as on the command line");
        assertTrue(AmpliconSettings.DEFAULTS.isDefault());
        assertEquals("Amplicon settings…", AmpliconSettingsDialog.buttonLabel(AmpliconSettings.DEFAULTS));
    }

    @Test
    void customAmpliconSettingsAreApplied() {
        AmpliconSettings custom = new AmpliconSettings(3, 6, 4, 2, 80, 300);
        AmpliconOptions options = custom.toOptions(1);
        assertEquals(new AmpliconOptions(1, 3, 6, 4, 2, 80, 300), options);
        assertFalse(custom.isDefault());
        assertEquals("Amplicon settings… *", AmpliconSettingsDialog.buttonLabel(custom));
    }

    @Test
    void noMaximumAcceptsAnyMinimum() {
        AmpliconOptions options = new AmpliconSettings(null, 5, 0, 5, 6000, 0).toOptions(0);
        assertEquals(0, options.maxAmpliconLength());
        assertThrows(IllegalArgumentException.class, () -> new AmpliconSettings(null, 5, 0, 5, 600, 500).toOptions(0));
    }

    @Test
    void ampliconDialogParsesAndRejectsInvalidValues() {
        AmpliconSettings parsed = AmpliconSettingsDialog.parse(" ", "5", "3", "5", "60", "300");
        assertEquals(new AmpliconSettings(null, 5, 3, 5, 60, 300), parsed);
        assertEquals(2, AmpliconSettingsDialog.parse("2", "5", "0", "5", "0", "0").probeMismatches());
        assertTrue(AmpliconSettingsDialog.parse("", "5", "0", "5", "6000", "0").toOptions(0).maxAmpliconLength() == 0);

        IllegalArgumentException minAboveMax = assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("", "5", "0", "5", "600", "500"));
        assertEquals("Min amplicon (600 bp) is larger than Max amplicon (500 bp).", minAboveMax.getMessage());
        assertThrows(IllegalArgumentException.class, () -> AmpliconSettingsDialog.parse("-1", "5", "0", "5", "0", "0"));
        assertThrows(IllegalArgumentException.class, () -> AmpliconSettingsDialog.parse("", "five", "0", "5", "0", "0"));
    }

    @Test
    void fastqDialogParsesAndRejectsInvalidValues() {
        assertEquals(new FastqSettings(new TadpoleOptions(25, 2, 1, 80), 0.25),
                FastqSettingsDialog.parse("25", "2", "1", "80", " 0.25 "));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("0", "2", "1", "60", "0.3"));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "NaN"));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "30%"));
        // The read extension keeps its default unless another choice is made.
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED,
                FastqSettingsDialog.parse("31", "2", "1", "60", "0.3").readExtension());
        assertEquals(new FastqSettings(TadpoleOptions.DEFAULTS, 0.3, ReadExtensionCaller.Mode.TARGET),
                FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", ReadExtensionCaller.Mode.TARGET));
    }

    @Test
    void typedPathsAreParsedOrCleared() {
        assertEquals(java.nio.file.Path.of("/data/reads"), PathFieldBinder.toPath("  /data/reads "));
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), "out"), PathFieldBinder.toPath("~/out"));
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home")), PathFieldBinder.toPath("~"));
        assertEquals(java.nio.file.Path.of("rel", "dir").toAbsolutePath().normalize(), PathFieldBinder.toPath("rel/./dir"));
        assertTrue(PathFieldBinder.toPath("~user/x").isAbsolute(), "~user is not expanded, but still absolute");
        assertEquals(null, PathFieldBinder.toPath("   "));
        assertEquals(null, PathFieldBinder.toPath(null));
        assertEquals(null, PathFieldBinder.toPath("bad\u0000path"));
    }

    @Test
    void fastqSettingsDefaultsAndLabel() {
        assertTrue(FastqSettings.DEFAULTS.isDefault());
        assertEquals("FASTQ settings…", FastqSettingsDialog.buttonLabel(FastqSettings.DEFAULTS));
        FastqSettings custom = new FastqSettings(new TadpoleOptions(25, 2, 1, 60), 0.3);
        assertEquals("FASTQ settings… *", FastqSettingsDialog.buttonLabel(custom));
        assertTrue(FastqSettingsDialog.summary(custom).contains("k=25"));
        assertTrue(FastqSettingsDialog.summary(custom).endsWith("Read extension: unreported"));

        FastqSettings noExtension = new FastqSettings(TadpoleOptions.DEFAULTS, 0.3, ReadExtensionCaller.Mode.OFF);
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, FastqSettings.DEFAULTS.readExtension());
        assertTrue(!noExtension.isDefault());
        assertEquals("FASTQ settings… *", FastqSettingsDialog.buttonLabel(noExtension));
        assertTrue(FastqSettingsDialog.summary(noExtension).endsWith("Read extension: off"));
    }
}
