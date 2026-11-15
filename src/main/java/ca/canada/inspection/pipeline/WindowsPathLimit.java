package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;

import java.nio.file.Path;
import java.util.Collection;

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
    /** The longest name of a file the run writes for a sample, after the sample's name. */
    static final String LONGEST_FASTQ_SUFFIX = "_extensionCandidateReads.fastq.gz";
    /** The BLAST hits of an assembly, the one file a FASTA sample has. */
    static final String FASTA_SUFFIX = ".tsv";

    private WindowsPathLimit() {
    }

    /** Checks the paths of {@code samples} in {@code directories}, on Windows only. */
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
            String name = sample.isFastq() ? sample.getName() + LONGEST_FASTQ_SUFFIX
                    : longer(sample.getName() + FASTA_SUFFIX, BlastQuery.LONGEST_NAME);
            String file = files.directory().toAbsolutePath().resolve(name).toString();
            if (file.length() > longest.length()) {
                longest = file;
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

    private static String longer(String a, String b) {
        return b.length() > a.length() ? b : a;
    }

    /** Whether {@code path} is too long for a program on Windows. */
    static boolean tooLong(Path path) {
        return AppPaths.isWindows() && path.toAbsolutePath().toString().length() >= MAX_PATH;
    }
}
