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
    void defaultAmpliconSettingsAreTheLibrarysDefaults() {
        // Compared with the command line itself in CliConfigTest.theWindowsDefaultsAreTheCommandLines.
        AmpliconOptions gui = AmpliconSettings.DEFAULTS.toOptions(60);
        assertEquals(AmpliconOptions.builder().tmGate(ca.canada.inspection.pcr.TmGate.DEFAULT).build(), gui);
        assertTrue(AmpliconSettings.DEFAULTS.isDefault());
        assertEquals("Amplicon settings…", AmpliconSettingsDialog.buttonLabel(AmpliconSettings.DEFAULTS));
    }

    @Test
    void customAmpliconSettingsAreApplied() {
        AmpliconSettings custom = new AmpliconSettings(8, 40, 1.5, 0.2, 500, 6, 4, 80, 300);
        AmpliconOptions options = custom.toOptions(55);
        assertEquals(AmpliconOptions.builder().threePrimeWindow(6).threePrimePenalty(4).minLength(80).maxLength(300)
                .tmGate(new ca.canada.inspection.pcr.TmGate(55, 8, 40, 1.5, 0.2, 500)).build(), options);
        assertFalse(custom.isDefault());
        assertEquals("Amplicon settings… *", AmpliconSettingsDialog.buttonLabel(custom));
        assertTrue(AmpliconSettingsDialog.summary(custom).contains("Tm margin 8 °C; 40 mM Na+, 1.5 mM Mg2+, 0.2 mM dNTP,"
                + " 500 nM each oligo"), AmpliconSettingsDialog.summary(custom));
    }

    @Test
    void noMaximumAcceptsAnyMinimum() {
        AmpliconOptions options = new AmpliconSettings(5, 50, 3, 0.8, 250, 5, 0, 6000, 0).toOptions(60);
        assertEquals(0, options.maxAmpliconLength());
        assertThrows(IllegalArgumentException.class,
                () -> new AmpliconSettings(5, 50, 3, 0.8, 250, 5, 0, 600, 500).toOptions(60));
    }

    @Test
    void ampliconDialogParsesAndRejectsInvalidValues() {
        AmpliconSettings parsed = AmpliconSettingsDialog.parse("5", "50", "3", "0.8", "250", "5", "3", "60", "300");
        assertEquals(new AmpliconSettings(5, 50, 3, 0.8, 250, 5, 3, 60, 300), parsed);
        assertTrue(AmpliconSettingsDialog.parse(" 7.5 ", "50", "3", "0.8", "250", "5", "0", "6000", "0").toOptions(60)
                .maxAmpliconLength() == 0);

        IllegalArgumentException minAboveMax = assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("5", "50", "3", "0.8", "250", "5", "0", "600", "500"));
        assertEquals("Min amplicon (600 bp) is larger than Max amplicon (500 bp).", minAboveMax.getMessage());
        assertEquals("Tm margin must be a number of 0 or more.", assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("-1", "50", "3", "0.8", "250", "5", "0", "0", "0")).getMessage());
        assertEquals("Each oligo must be greater than 0 nM.", assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("5", "50", "3", "0.8", "0", "5", "0", "0", "0")).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("5", "50", "3", "0.8", "250", "five", "0", "0", "0"));
        assertEquals("No cations left for a Tm: give Na+ / K+ above 0, or Mg2+ above dNTP (the dNTP bind their own"
                + " amount of Mg2+).", assertThrows(IllegalArgumentException.class,
                () -> AmpliconSettingsDialog.parse("5", "0", "0.5", "0.8", "250", "5", "0", "0", "0")).getMessage());
    }

    @Test
    void fastqDialogParsesAndRejectsInvalidValues() {
        assertEquals(new FastqSettings(new TadpoleOptions(25, 2, 1, 80), 0.25, ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ca.canada.inspection.pipeline.PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY),
                FastqSettingsDialog.parse("25", "2", "1", "80", " 0.25 ", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ""));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("0", "2", "1", "60", "0.3", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ""));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "1.5", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ""));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "NaN", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ""));
        assertThrows(IllegalArgumentException.class, () -> FastqSettingsDialog.parse("31", "2", "1", "60", "30%", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ""));
        // The read extension keeps its default unless another choice is made.
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED,
                FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, "").readExtension());
        assertEquals(new FastqSettings(TadpoleOptions.DEFAULTS, 0.3, ReadExtensionCaller.Mode.TARGET, ca.canada.inspection.pipeline.PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY),
                FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", ReadExtensionCaller.Mode.TARGET, ""));
    }

    @Test
    void aDropOfOneFileOrFolderIsTakenNotOfSeveral() {
        // Both files of a read pair dropped on the input field: the first was run alone, as single-end reads.
        java.io.File r1 = new java.io.File("s_R1.fastq.gz");
        assertEquals(r1.toPath(), PathFieldBinder.dropped(java.util.List.of(r1)));
        assertEquals(null, PathFieldBinder.dropped(java.util.List.of(r1, new java.io.File("s_R2.fastq.gz"))));
        assertEquals(null, PathFieldBinder.dropped(java.util.List.of()));
    }

    @Test
    void typedPathsAreParsedOrCleared() {
        assertEquals(java.nio.file.Path.of("/data/reads").toAbsolutePath(), PathFieldBinder.toPath("  /data/reads "));
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home"), "out"), PathFieldBinder.toPath("~/out"));
        assertEquals(java.nio.file.Path.of(System.getProperty("user.home")), PathFieldBinder.toPath("~"));
        assertEquals(java.nio.file.Path.of("rel", "dir").toAbsolutePath().normalize(), PathFieldBinder.toPath("rel/./dir"));
        assertTrue(PathFieldBinder.toPath("~user/x").isAbsolute(), "~user is not expanded, but still absolute");
        assertEquals(null, PathFieldBinder.toPath("   "));
        assertEquals(null, PathFieldBinder.toPath(null));
        assertEquals(null, PathFieldBinder.toPath("bad\u0000path"));
    }

    @Test
    void aPathPastedWithItsQuotesIsThePathInside() {
        // Windows Explorer's "Copy as path" (Ctrl+Shift+C) quotes the path: the field was then
        // cleared on Windows, a quote being no part of a file name there.
        java.nio.file.Path reads = java.nio.file.Path.of("data dir", "reads").toAbsolutePath().normalize();
        assertEquals(reads, PathFieldBinder.toPath("\"" + reads + "\""));
        assertEquals(reads, PathFieldBinder.toPath("  \"" + reads + "\"\r\n"));
        assertEquals(null, PathFieldBinder.toPath("\"\""));
        assertEquals(null, PathFieldBinder.toPath("\"  \""));
    }

    @Test
    void fastqSettingsDefaultsAndLabel() {
        assertTrue(FastqSettings.DEFAULTS.isDefault());
        assertEquals("FASTQ settings…", FastqSettingsDialog.buttonLabel(FastqSettings.DEFAULTS));
        FastqSettings custom = new FastqSettings(new TadpoleOptions(25, 2, 1, 60), 0.3, ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT, ca.canada.inspection.pipeline.PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY);
        assertEquals("FASTQ settings… *", FastqSettingsDialog.buttonLabel(custom));
        assertTrue(FastqSettingsDialog.summary(custom).contains("k=25"));
        assertTrue(FastqSettingsDialog.summary(custom).contains("\nRead extension: unreported\n"));
        assertTrue(FastqSettingsDialog.summary(custom).endsWith("Memory for BBTools and BLAST: automatic: three quarters of the"
                + " memory insilicoPCR leaves free on the computer (a job's limit on a cluster), at least half of its own"
                + " heap"), FastqSettingsDialog.summary(custom));

        FastqSettings noExtension = new FastqSettings(TadpoleOptions.DEFAULTS, 0.3, ReadExtensionCaller.Mode.OFF, ca.canada.inspection.pipeline.PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY);
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, FastqSettings.DEFAULTS.readExtension());
        assertTrue(!noExtension.isDefault());
        assertEquals("FASTQ settings… *", FastqSettingsDialog.buttonLabel(noExtension));
        assertTrue(FastqSettingsDialog.summary(noExtension).contains("\nRead extension: off\n"));
    }

    @Test
    void fastqDialogReadsTheBbtoolsMemoryOrLeavesItAutomatic() {
        var mode = ca.canada.inspection.pcr.ReadExtensionCaller.Mode.DEFAULT;
        assertEquals(24, FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", mode, " 24 ").bbtoolsMemoryGiB());
        assertEquals(ca.canada.inspection.pipeline.PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY,
                FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", mode, "").bbtoolsMemoryGiB());
        assertThrows(IllegalArgumentException.class,
                () -> FastqSettingsDialog.parse("31", "2", "1", "60", "0.3", mode, "0"));
        assertTrue(FastqSettingsDialog.FastqSettings.DEFAULTS.isDefault());
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aPastedPathIsReadAsTheFileItNames() {
        // A file manager's file URI, single quotes and a shell's escaped space were taken for relative paths, the
        // working folder glued to the front: "Input does not exist: /home/me/file:/data/my reads".
        java.nio.file.Path expected = java.nio.file.Path.of("/data/my reads é");
        assertEquals(expected, PathFieldBinder.toPath("file:///data/my%20reads%20%C3%A9"));
        assertEquals(expected, PathFieldBinder.toPath("'/data/my reads é'"));
        assertEquals(expected, PathFieldBinder.toPath("\"/data/my reads é\""));
        assertEquals(expected, PathFieldBinder.toPath("/data/my\\ reads\\ é"));
        assertEquals(expected, PathFieldBinder.toPath("  /data/my reads é \n"));
    }
}
