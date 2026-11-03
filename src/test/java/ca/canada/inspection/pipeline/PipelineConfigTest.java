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
        PipelineConfig config = new PipelineConfig(IN, OUT, PRIMERS, 2, 1e5,
                AmpliconOptions.withMismatches(3), true);
        assertTrue(config.input().isAbsolute());
        assertTrue(config.output().isAbsolute());
        assertTrue(config.primers().isAbsolute());
        assertEquals(3, config.mismatches());
        assertTrue(config.gel());
    }

    @Test
    void invalidArgumentsAreRejected() {
        AmpliconOptions options = AmpliconOptions.withMismatches(0);
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(IN, OUT, PRIMERS, 0, 1e5, options, false));
        assertThrows(IllegalArgumentException.class,
                () -> new PipelineConfig(IN, OUT, PRIMERS, 1, 0.0, options, false));
        assertThrows(NullPointerException.class,
                () -> new PipelineConfig(null, OUT, PRIMERS, 1, 1e5, options, false));
        assertThrows(NullPointerException.class,
                () -> new PipelineConfig(IN, OUT, PRIMERS, 1, 1e5, null, false));
    }
}
