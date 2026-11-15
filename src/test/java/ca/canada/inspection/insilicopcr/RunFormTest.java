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
        return new RunForm(input, dir.resolve("out"), primers, 3, 2, evalue, AmpliconSettings.DEFAULTS,
                FastqSettings.DEFAULTS);
    }

    @Test
    void aValidFormIsTheRunItShows() throws IOException {
        PipelineConfig config = assertInstanceOf(RunForm.Outcome.Run.class, form(" 1e3 ").toConfig()).config();

        assertEquals(3, config.threads());
        assertEquals(1e3, config.evalue());
        assertEquals(2, config.mismatches());
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
    }

    @Test
    void aMissingPathIsAMessage() {
        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class,
                new RunForm(null, dir, dir, 1, 0, "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig());
        assertEquals("Please enter an input file, a reference file, and an output file", invalid.message());
    }

    @Test
    void theInputFolderAsTheOutputFolderIsAMessage() throws IOException {
        Path primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGTACGT\n>a-R\nACGTACGT\n");
        RunForm.Outcome.Invalid invalid = assertInstanceOf(RunForm.Outcome.Invalid.class,
                new RunForm(dir, dir, primers, 1, 0, "1e5", AmpliconSettings.DEFAULTS, FastqSettings.DEFAULTS).toConfig());
        assertEquals("The output folder must not be the input folder: " + dir, invalid.message());
    }
}
