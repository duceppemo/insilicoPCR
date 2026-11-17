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

    /** How deep a run writes in its report folders: {@code detailed_report/<sample>/<file>}. */
    private static final int DEPTH = 2;

    /**
     * Whether a run writes {@code name}, an entry of its output folder: the primer database
     * ({@code primer_tmp.fasta} and BLAST's files beside it), the logs, the lock, and the two report folders.
     */
    static boolean written(String name) {
        // Whatever the case: on Windows and macOS, Detailed_Report is the folder a run writes in.
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("primer_tmp.fasta") || lower.startsWith("qalog") || lower.startsWith("log.txt")
                || lower.equals(OutputLock.FILE_NAME.toLowerCase(java.util.Locale.ROOT))
                || lower.equals("detailed_report") || lower.equals(CONSOLIDATED);
    }

    /**
     * Whether {@code entry} leads elsewhere: a symbolic link, or on Windows a junction (a link to a folder that
     * {@link Files#isSymbolicLink} does not see; Java reads it as "other"). A pipe or a device where a run writes is
     * no place for its files either.
     */
    static boolean leadsElsewhere(Path entry) {
        try {
            java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(entry,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return attributes.isSymbolicLink() || attributes.isOther();
        } catch (IOException e) {
            return false; // gone since it was listed
        }
    }

    /**
     * Refuses an output folder holding a symbolic link where the run writes: in a folder reused from another
     * run (unpacked from an archive, or shared with others), a link named like an output (primer_tmp.fasta,
     * QAlog.txt.part, detailed_report/&lt;sample&gt;) made the run overwrite, or delete, the file it points to,
     * outside the folder. A run makes no link itself. Links elsewhere in the folder are no concern of the run's
     * (an input folder inside it with linked reads, a .venv): before 0.15.3 any link within three levels was
     * refused. The output folder may itself be a link. Junctions too (Windows): a run wrote through them.
     */
    static void refuseLinks(Path output) {
        if (!Files.isDirectory(output)) {
            return;
        }
        List<Path> links = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> entries = Files.list(output.toRealPath())) {
            for (Path entry : entries.filter(entry -> written(entry.getFileName().toString())).sorted().toList()) {
                if (leadsElsewhere(entry)) {
                    links.add(entry);
                } else if (Files.isDirectory(entry)) {
                    try (java.util.stream.Stream<Path> inside = Files.walk(entry, DEPTH)) {
                        inside.filter(PipelineDirectories::leadsElsewhere).sorted().forEach(links::add);
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            return; // listed again as the run writes; a folder it cannot read fails there
        }
        if (!links.isEmpty()) {
            throw new PipelineException("The output folder " + output + " holds symbolic links or junctions where a"
                    + " run writes (" + String.join(", ", links.stream().limit(5).map(Path::toString).toList())
                    + "): a run would write through them, into the files they point to. Remove them, or choose"
                    + " another output folder.");
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
