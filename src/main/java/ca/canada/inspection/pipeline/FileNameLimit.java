package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.InputCheck;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The longest file name a run writes for a sample, against the 255 bytes most file systems allow a name
 * (ext4, NTFS, APFS): a sample named at length (a long sample sheet ID, two read files' names joined) gave
 * names that cannot be made, and the run failed, every sample with it.
 */
final class FileNameLimit {
    /** Bytes a file name may have. */
    static final int MAX_NAME_BYTES = 255;
    /** What is added to a name while it is written ({@code .part}, then moved over it). */
    private static final String WRITING = ca.canada.inspection.util.OutputFiles.PART;

    private FileNameLimit() {
    }

    /** The samples of {@code samples} whose files' names would be too long, as input issues. */
    static List<InputCheck.Issue> tooLong(PipelineDirectories directories, Collection<Sample> samples) {
        List<InputCheck.Issue> issues = new ArrayList<>();
        for (Sample sample : samples) {
            int longest = directories.sample(sample.getName()).written(sample.isFastq()).stream()
                    .map(Path::getFileName).mapToInt(name -> (name + WRITING).getBytes(StandardCharsets.UTF_8).length)
                    .max().orElse(0);
            if (longest > MAX_NAME_BYTES) {
                issues.add(new InputCheck.Issue(sample.getName(), sample.files().stream()
                        .map(file -> file.getFileName().toString()).toList(), "the sample name is too long",
                        "its result files' names would have " + longest + " bytes, more than the " + MAX_NAME_BYTES
                                + " a file name may have; shorten the file's name by " + (longest - MAX_NAME_BYTES)));
            }
        }
        return issues;
    }
}
