package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** The window's form to the run it asks for, without the window. */
class RunFormTest {

    @TempDir
    Path dir;

    private RunForm form(String evalue) throws IOException {
        Path input = Files.writeString(dir.resolve("genome.fasta"), ">c1\nACGT\n");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        return new RunForm(input, dir.resolve("out"), primers, 3, "60", evalue, AmpliconSettings.DEFAULTS,
                FastqSettings.DEFAULTS);
    }

    @Test
    void theAnnealingTemperatureMustBeATemperature() throws IOException {
        Path input = Files.writeString(dir.resolve("genome.fasta"), ">c1\nACGT\n");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        for (String bad : new String[]{"hot", "", "101", "-1"}) {
            RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class, new RunForm(input,
                    dir.resolve("out"), primers, 1, bad, "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS)
                    .toConfig());
            assertEquals("Annealing temperature must be 0 to 100 °C, e.g. 60.", invalid.message());
        }
        assertEquals(57.5, assertInstanceOf(RunForm.Outcome.Run.class, new RunForm(input, dir.resolve("out"), primers,
                1, " 57.5 ", "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig()).config()
                .ampliconOptions().tmGate().annealTemp());
    }

    @Test
    void aValidFormIsTheRunItShows() throws IOException {
        PipelineConfig config = assertInstanceOf(RunForm.Outcome.Run.class, form(" 1e3 ").toConfig()).config();

        assertEquals(3, config.threads());
        assertEquals(1e3, config.evalue());
        assertEquals(ca.canada.inspection.pcr.TmGate.DEFAULT, config.ampliconOptions().tmGate());
        assertEquals(InputCheck.Depth.FULL, config.inputCheck());
        assertEquals(false, config.gel());
        assertEquals(FastqSettings.DEFAULTS.tadpole(), config.tadpole());
    }

    @Test
    void aWrongEvalueIsAMessage() throws IOException {
        for (String evalue : new String[]{"abc", "0", "-1", ""}) {
            RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class, form(evalue).toConfig());
            assertEquals("E-value must be a number greater than 0, e.g. 1e5.", invalid.message(), evalue);
        }
        // Above 0, but too small for a double: in the command line's words, not "must be greater than 0".
        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class, form(" 1e-400").toConfig());
        assertEquals("E-value 1e-400 is too small for a number of the program (it reads as 0): give 1e-300 or more.",
                invalid.message());
        // Below 0, or 0 written otherwise: not "too small".
        for (String evalue : new String[]{"-1e-400", "0d", "0x0p0", "0e-5"}) {
            assertEquals("E-value must be a number greater than 0, e.g. 1e5.",
                    assertInstanceOf(RunForm.Outcome.Invalid.class, form(evalue).toConfig()).message(), evalue);
        }
    }

    @Test
    void aMissingPathIsAMessage() {
        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class,
                new RunForm(null, dir, dir, 1, "60", "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig());
        assertEquals("Please choose an input file or folder, a primer file and an output folder", invalid.message());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anInputFolderThatCannotBeListedIsAMessage() throws IOException {
        // Another user's folder: Run did nothing, the error escaping on the window's thread.
        Path input = Files.createDirectories(dir.resolve("theirs"));
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        Files.setPosixFilePermissions(input, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(Files.isReadable(input), "run as root: every folder is readable");
            RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class,
                    new RunForm(input, dir.resolve("out"), primers, 1, "60", "1e5", AmpliconSettings.DEFAULTS,
                            FastqSettings.DEFAULTS).toConfig());
            assertEquals("Unable to list input directory: " + input + " (" + input + ": permission denied)",
                    invalid.message());
        } finally {
            Files.setPosixFilePermissions(input, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void anInputFolderWithFilesInSubfoldersOnlySaysSo() throws IOException {
        Path input = Files.createDirectories(dir.resolve("run").resolve("S1"));
        Files.writeString(input.resolve("S1.fasta"), ">c1\nACGT\n");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");

        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class, new RunForm(input.getParent(),
                dir.resolve("out"), primers, 1, "60", "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig());
        // The run's words: the window had its own.
        assertEquals(ca.canada.inspection.util.InputCheck.noInputFiles(input.getParent()), invalid.message());
        org.junit.jupiter.api.Assertions.assertTrue(invalid.message().contains("its subfolders S1 hold some, but subfolders are not read"), invalid.message());
    }

    @Test
    void theInputFolderAsTheOutputFolderIsAMessage() throws IOException {
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class,
                new RunForm(dir, dir, primers, 1, "60", "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig());
        assertEquals("The output folder must not be the input folder: " + dir, invalid.message());
    }

    @Test
    void theBbtoolsMemoryOfTheFastqSettingsReachesTheRun() throws IOException {
        Path input = Files.writeString(dir.resolve("genome.fasta"), ">c1\nACGT\n");
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        FastqSettings fastq = new FastqSettings(FastqSettings.DEFAULTS.tadpole(), 0.3,
                FastqSettings.DEFAULTS.readExtension(), 16);
        PipelineConfig config = assertInstanceOf(RunForm.Outcome.Run.class, new RunForm(input, dir.resolve("out"),
                primers, 2, "60", "1e5", AmpliconSettings.DEFAULTS, fastq).toConfig()).config();
        assertEquals(16, config.bbtoolsMemoryGiB());
    }
}
