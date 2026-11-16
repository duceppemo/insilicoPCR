package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record PipelineDirectories(Path output, Path detailed, Path consolidated) {
    /** The folder of a run's consolidated reports, in its output folder. */
    static final String CONSOLIDATED = "consolidated_report";

    public static PipelineDirectories create(Path output) {
        refuseLinks(output);
        PipelineDirectories directories = new PipelineDirectories(
                output,
                output.resolve("detailed_report"),
                output.resolve(CONSOLIDATED)
        );
        directories.createAll();
        return directories;
    }

    /** How deep a run writes in its output folder: {@code detailed_report/<sample>/<file>}. */
    private static final int DEPTH = 3;

    /**
     * Refuses an output folder holding a symbolic link where the run writes: in a folder reused from another
     * run (unpacked from an archive, or shared with others), a link named like an output (primer_tmp.fasta,
     * QAlog.txt.part, detailed_report/&lt;sample&gt;) made the run overwrite, or delete, the file it points to,
     * outside the folder. A run makes no link itself. The output folder may itself be a link.
     */
    static void refuseLinks(Path output) {
        if (!Files.isDirectory(output)) {
            return;
        }
        List<Path> links;
        try (java.util.stream.Stream<Path> entries = Files.walk(output.toRealPath(), DEPTH)) {
            links = entries.filter(Files::isSymbolicLink).sorted().limit(5).toList();
        } catch (IOException | java.io.UncheckedIOException e) {
            return; // listed again as the run writes; a folder it cannot read fails there
        }
        if (!links.isEmpty()) {
            throw new PipelineException("The output folder " + output + " holds symbolic links where a run writes ("
                    + String.join(", ", links.stream().map(Path::toString).toList()) + "): a run would write through"
                    + " them, into the files they point to. Remove them, or choose another output folder.");
        }
    }

    public Path primerDatabase() {
        return output.resolve("primer_tmp.fasta");
    }

    /** The files of sample {@code sampleName}. */
    public SampleFiles sample(String sampleName) {
        return SampleFiles.of(detailed, sampleName);
    }

    /**
     * Makes the folder of sample {@code sampleName}, without the decompressed assembly a run killed
     * while searching it left there ({@code blast_query_*.fasta}, a whole genome).
     */
    public void createSampleDirectory(String sampleName) {
        Path directory = sample(sampleName).directory();
        createDirectory(directory);
        try (java.nio.file.DirectoryStream<Path> left = Files.newDirectoryStream(directory, BlastQuery.LEFT_BEHIND)) {
            for (Path file : left) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // A leftover is only clutter; the search writes a new one under its own name.
        }
    }

    private void createAll() {
        createDirectory(detailed);
        createDirectory(consolidated);
    }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new PipelineException("Unable to create directory: " + directory, e);
        }
    }
}
