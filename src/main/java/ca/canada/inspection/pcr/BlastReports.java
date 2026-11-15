package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Reads the per-sample BLAST TSV reports and attaches the accepted primer hits to the samples. */
public final class BlastReports {

    private BlastReports() {
    }

    /**
     * Reads the BLAST report of each of {@code samples} and attaches its accepted hits. Reports
     * of other samples, left by an earlier run in the same output folder, are not read.
     *
     * @param reportOf where the BLAST stage wrote a sample's report, by sample name
     * @param warning  told about trimmed hits whose sequence was not found ({@link BlastHits#missingWarning})
     */
    public static void attachHits(Function<String, Path> reportOf, Map<String, Sample> samples, PrimerPanel primers,
                                  AmpliconOptions options, Consumer<String> warning) {
        samples.forEach((sampleName, sample) -> {
            List<Path> searched = sample.searchedFasta().stream().filter(Files::isRegularFile).toList();
            String missing = BlastHits.missingWarning(
                    BlastHits.read(reportOf.apply(sampleName), searched, sample.getName(), sample.hits(), primers, options),
                    searched);
            if (!missing.isEmpty()) {
                warning.accept(missing);
            }
        });
    }
}
