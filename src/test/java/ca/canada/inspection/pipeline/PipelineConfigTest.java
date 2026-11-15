package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineConfigTest {

    private static final Path IN = Path.of("in.fasta");
    private static final Path OUT = Path.of("out");
    private static final Path PRIMERS = Path.of("primers.fasta");

    @Test
    void pathsAreNormalizedToAbsoluteAndMismatchesDelegates() {
        PipelineConfig config = PipelineConfig.builder(IN, OUT, PRIMERS)
                .ampliconOptions(AmpliconOptions.withMismatches(3)).gel(true).build();
        assertTrue(config.input().isAbsolute());
        assertTrue(config.output().isAbsolute());
        assertTrue(config.primers().isAbsolute());
        assertEquals(3, config.mismatches());
        assertTrue(config.gel());
        assertEquals(ca.canada.inspection.pcr.ReadExtensionCaller.Mode.UNREPORTED, config.readExtension());
    }

    @Test
    void theBuilderStartsFromTheDocumentedDefaults() {
        PipelineConfig config = PipelineConfig.builder(IN, OUT, PRIMERS).build();
        assertEquals(Runtime.getRuntime().availableProcessors(), config.threads());
        assertEquals(1e5, config.evalue());
        assertEquals(PipelineConfig.DEFAULT_EVALUE, Double.parseDouble(PipelineConfig.DEFAULT_EVALUE_TEXT));
        assertEquals(AmpliconOptions.withMismatches(0), config.ampliconOptions());
        assertEquals(ca.canada.inspection.pipeline.TadpoleOptions.DEFAULTS, config.tadpole());
        assertEquals(ca.canada.inspection.pcr.ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, config.readMinFraction());
        assertEquals(ca.canada.inspection.util.InputCheck.Depth.FULL, config.inputCheck());
        assertTrue(!config.gel());
    }

    @Test
    void invalidArgumentsAreRejected() {
        AmpliconOptions options = AmpliconOptions.withMismatches(0);
        assertThrows(IllegalArgumentException.class, () -> PipelineConfig.builder(IN, OUT, PRIMERS).threads(0).build());
        assertThrows(IllegalArgumentException.class, () -> PipelineConfig.builder(IN, OUT, PRIMERS).evalue(0.0).build());
        assertThrows(NullPointerException.class, () -> PipelineConfig.builder(null, OUT, PRIMERS).build());
        assertThrows(NullPointerException.class,
                () -> PipelineConfig.builder(IN, OUT, PRIMERS).ampliconOptions(null).build());
        assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.builder(IN, OUT, PRIMERS).readMinFraction(1.5).build());
    }

    @Test
    void theOutputFolderMustNotBeTheInputFolder() {
        // The primer database written there would be listed as a sample.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.builder(IN, IN, PRIMERS).build());
        assertEquals("The output folder must not be the input folder: " + IN.toAbsolutePath().normalize(),
                error.getMessage());
    }

    @Test
    void anEvalueBlastnCannotTakeIsRejectedBeforeTheRun() {
        // Infinity, and 1e999 which reads as it, only failed at blastn, hours into a FASTQ run.
        for (double evalue : new double[]{Double.POSITIVE_INFINITY, Double.parseDouble("1e999"), Double.NaN}) {
            assertThrows(IllegalArgumentException.class,
                    () -> PipelineConfig.builder(IN, OUT, PRIMERS).evalue(evalue).build(), String.valueOf(evalue));
        }
        assertEquals(1e300, PipelineConfig.builder(IN, OUT, PRIMERS).evalue(1e300).build().evalue());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anOutputFolderThatIsTheInputFolderThroughALinkIsRejected(@org.junit.jupiter.api.io.TempDir Path dir)
            throws java.io.IOException {
        Path input = java.nio.file.Files.createDirectories(dir.resolve("genomes"));
        Path link = java.nio.file.Files.createSymbolicLink(dir.resolve("glink"), input);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PipelineConfig.builder(input, link, PRIMERS).build());
        assertTrue(error.getMessage().startsWith("The output folder must not be the input folder"));
    }
}
