package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Reads the per-sample BLAST TSV reports and attaches the accepted primer hits to the samples. */
public final class BlastReports {

    private BlastReports() {
    }

    /** Where the BLAST stage writes the report of the sample {@code sampleName}. */
    public static Path reportFile(Path detailedDir, String sampleName) {
        return detailedDir.resolve(sampleName).resolve(sampleName + ".tsv");
    }

    /**
     * Reads the BLAST report of each of {@code samples} (see {@link #reportFile}) and attaches
     * its accepted hits. Reports of other samples, left by an earlier run in the same output
     * folder, are not read.
     *
     * @param warning told about trimmed hits whose sequence was not found ({@link BlastHits#missingWarning})
     */
    public static void attachHits(Path detailedDir, Map<String, Sample> samples, PrimerPanel primers,
                                  AmpliconOptions options, Consumer<String> warning) {
        samples.forEach((sampleName, sample) -> {
            List<Path> searched = sample.searchedFasta().stream().filter(Files::isRegularFile).toList();
            String missing = BlastHits.missingWarning(
                    BlastHits.read(reportFile(detailedDir, sampleName), searched, sample, primers, options), searched);
            if (!missing.isEmpty()) {
                warning.accept(missing);
            }
        });
    }
}
