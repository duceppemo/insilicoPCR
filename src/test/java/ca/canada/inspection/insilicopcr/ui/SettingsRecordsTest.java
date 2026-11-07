package ca.canada.inspection.insilicopcr.ui;

import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.pcr.AmpliconOptions;
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
    void fastqSettingsDefaultsAndLabel() {
        assertTrue(FastqSettings.DEFAULTS.isDefault());
        assertEquals("FASTQ settings…", FastqSettingsDialog.buttonLabel(FastqSettings.DEFAULTS));
        FastqSettings custom = new FastqSettings(new TadpoleOptions(25, 2, 1, 60), 0.3);
        assertEquals("FASTQ settings… *", FastqSettingsDialog.buttonLabel(custom));
        assertTrue(FastqSettingsDialog.summary(custom).contains("k=25"));
    }
}
