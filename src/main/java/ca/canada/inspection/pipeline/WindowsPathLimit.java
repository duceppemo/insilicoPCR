package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Windows' limit on the length of a path, checked before a run starts its programs.
 *
 * <p>BLAST+ cannot open a path of {@value #MAX_PATH} characters or more, and Windows starts no
 * program in a folder whose path is that long, whatever the long-path setting of the computer.
 * An output folder deep enough failed with "CreateProcess error=267, The directory name is invalid",
 * or with BLAST's "File is not accessible", after the input check; the folders the run writes add
 * {@code detailed_report\<sample>\} and the sample's file names to it.</p>
 */
final class WindowsPathLimit {
    /** {@code MAX_PATH}: a path must be shorter. */
    static final int MAX_PATH = 260;

    private WindowsPathLimit() {
    }

    /**
     * Checks the paths of {@code samples} in {@code directories}, on Windows only. With no sample, the
     * output folder's own files: checked before the input files are read, which can take minutes.
     */
    static void check(PipelineDirectories directories, Collection<Sample> samples) {
        if (AppPaths.isWindows()) {
            check(directories, samples, MAX_PATH);
        }
    }

    /**
     * @throws PipelineException naming the longest path when it is {@code limit} characters or more
     */
    static void check(PipelineDirectories directories, Collection<Sample> samples, int limit) {
        String longest = directories.primerDatabase().toAbsolutePath() + ".nhr";
        for (Sample sample : samples) {
            SampleFiles files = directories.sample(sample.getName());
            // The files it writes, and the copy of an assembly blastn may be given (BlastQuery).
            List<Path> written = new ArrayList<>(files.written(sample.isFastq()));
            written.add(files.directory().resolve(BlastQuery.LONGEST_NAME));
            for (Path path : written) {
                String file = path.toAbsolutePath().toString();
                if (file.length() > longest.length()) {
                    longest = file;
                }
            }
        }
        if (longest.length() >= limit) {
            int over = longest.length() - limit + 1;
            throw new PipelineException("The output folder's path is too long for Windows: the run would write "
                    + longest + " (" + longest.length() + " characters), and BLAST+ and the programs it runs"
                    + " cannot use a path of " + limit + " characters or more. Choose an output folder whose path"
                    + " is at least " + over + " characters shorter, such as one nearer the top of the drive, or give the"
                    + " sample's files a shorter name.");
        }
    }

    /** Whether {@code path} is too long for a program on Windows. */
    static boolean tooLong(Path path) {
        return AppPaths.isWindows() && path.toAbsolutePath().toString().length() >= MAX_PATH;
    }
}
