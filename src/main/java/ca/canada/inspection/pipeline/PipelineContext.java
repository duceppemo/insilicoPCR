package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.util.InputCheck;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public record PipelineContext(
        PipelineConfig config,
        RuntimeDependencies dependencies,
        PipelineDirectories directories,
        Map<String, Sample> samples,
        PrimerPanel primers,
        int childJavaMemoryBudgetGiB,
        List<InputCheck.Issue> inputIssues,
        RunMonitor monitor
) {
    /**
     * The FASTA files BLAST searches for {@code sample}: its own file, or for reads the
     * mini-assembly the read step wrote ({@link SampleFiles#assembly}), the one place its path is.
     *
     * @throws PipelineException for reads not assembled (yet)
     */
    public List<Path> searchedFasta(Sample sample) {
        if (!sample.isFastq()) {
            return sample.files();
        }
        Path assembly = directories.sample(sample.getName()).assembly();
        if (!java.nio.file.Files.isRegularFile(assembly)) {
            throw new PipelineException("FASTQ sample " + sample.getName() + " has no mini-assembly: " + assembly);
        }
        return List.of(assembly);
    }

    public boolean hasFastqSamples() {
        return samples.values().stream().anyMatch(Sample::isFastq);
    }

    /** The FASTQ samples of {@code samples}, by name: the order the read steps go through them in. */
    static java.util.List<Sample> fastqSamples(java.util.Map<String, Sample> samples) {
        return samples.values().stream().filter(Sample::isFastq)
                .sorted(java.util.Comparator.comparing(Sample::getName)).toList();
    }
}
