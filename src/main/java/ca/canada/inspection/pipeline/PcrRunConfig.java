package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.nio.file.Path;

public record PcrRunConfig(
        Path inputFile,
        Path outDir,
        Path primerFile,
        int threads,
        double evalue,
        AmpliconOptions ampliconOptions,
        TextArea outputField,
        ProgressBar blastProgress
) {
    /** Allowed primer mismatches; also used as BBDuk {@code hdist} when baiting reads. */
    public int mismatches() {
        return ampliconOptions.maxTotalMismatches();
    }
}
