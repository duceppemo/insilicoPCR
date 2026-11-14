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
}
