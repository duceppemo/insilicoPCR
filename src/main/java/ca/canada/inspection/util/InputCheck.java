package ca.canada.inspection.util;

import ca.canada.inspection.model.Sample;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import java.util.zip.ZipException;

/**
 * Finds the samples of a run and checks their files before anything is run on them.
 *
 * <p>A file that is empty, cannot be read, is not what its extension says or is not well
 * formed would otherwise be left out without a word (the sample then looks as if it had
 * never been given), run as half of a read pair, or stop the whole run in the middle of
 * an external tool. Here every such file is named with what is wrong with it, its sample
 * is left out, and the other samples are run.</p>
 */
public final class InputCheck {

    /** How much of each FASTQ file is read. */
    public enum Depth {
        /** Every record, and the read counts of the two files of a pair. */
        FULL,
        /** The first record only: catches empty files and files of the wrong kind. */
        QUICK;

        /** As written on the command line: {@code full} or {@code quick}. */
        public String option() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Depth parse(String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "full" -> FULL;
                case "quick" -> QUICK;
                default -> throw new IllegalArgumentException("--input-check must be full or quick, not: " + value);
            };
        }
    }

    /**
     * An input file, or the two files of a read pair, that cannot be analysed.
     *
     * @param sample  the sample the files belong to; it is left out of the run
     * @param files   file names: one, the two mates, or the files that share a sample name
     * @param problem a few words: what kind of fault
     * @param detail  where and what
     */
    public record Issue(String sample, List<String> files, String problem, String detail) {
        public Issue {
            files = List.copyOf(files);
        }

        /** The files and what is wrong with them. */
        public String describe() {
            return String.join(" and ", files) + ": " + problem + (detail.isEmpty() ? "" : " (" + detail + ")");
        }

        /** One line for the terminal and the application's output area. */
        public String line() {
            return describe() + ". Sample " + sample + " is not analysed.";
        }
    }

    /**
     * @param samples the samples to run, in name order; none of their files has an issue
     * @param issues  what was left out, in file order
     * @param input   the file or folder that was scanned
     * @param files   how many files with a sequence file extension were looked at
     * @param subfoldersNotRead the folder's subfolders that hold sequence files, which are not read
     */
    public record Result(Map<String, Sample> samples, List<Issue> issues, Path input, int files,
                         List<Path> subfoldersNotRead) {

        /** The names of the samples left out. */
        public Set<String> samplesNotAnalysed() {
            Set<String> names = new LinkedHashSet<>();
            issues.forEach(issue -> names.add(issue.sample()));
            return names;
        }

        /** What to tell the user, one line each, without a prefix; empty when all is well. */
        public List<String> warnings() {
            List<String> lines = new ArrayList<>();
            issues.forEach(issue -> lines.add(issue.line()));
            // A sample in a per-sample folder (as sequencers' software writes them) was left out without a word.
            subfoldersNotRead.forEach(folder -> lines.add("the folder " + folder + " holds sequence files, which are"
                    + " not read: only the files directly in " + input + " are. Give that folder as an input of its own,"
                    + " or move its files up."));
            return lines;
        }
    }

    private InputCheck() {
    }

    /**
     * Scans {@code input}, a sequence file or a folder of them.
     *
     * @param threads files are checked this many at a time
     */
    public static Result scan(Path input, Depth depth, int threads) {
        List<Path> files = candidates(input);
        Map<Path, FileFault> faults = check(files, depth, threads);

        Map<String, Sample> samples = SequenceFileUtils.sampleMapOf(files);
        List<Issue> issues = new ArrayList<>();
        Set<String> rejected = new LinkedHashSet<>();
        for (Path file : files) {
            FileFault fault = faults.get(file);
            if (fault.problem() != null) {
                String sample = SequenceFileUtils.sampleName(file);
                issues.add(new Issue(sample, List.of(file.getFileName().toString()), fault.problem(), fault.detail()));
                rejected.add(sample);
            }
        }
        rejectFilesThatAreNotOneSample(samples, issues, rejected);
        rejectNamesThatDifferInCase(samples, issues, rejected);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            rejectNamesWindowsCannotUse(samples, issues, rejected);
        }
        if (depth == Depth.FULL) {
            samples.forEach((name, sample) -> {
                if (!rejected.contains(name) && sample.isFastq() && sample.files().size() == 2) {
                    Path first = sample.files().get(0);
                    Path second = sample.files().get(1);
                    long firstReads = faults.get(first).records();
                    long secondReads = faults.get(second).records();
                    if (firstReads != secondReads) {
                        issues.add(new Issue(name, List.of(first.getFileName().toString(), second.getFileName().toString()),
                                "the two files of the pair hold different numbers of reads",
                                String.format(Locale.ROOT, "%,d and %,d", firstReads, secondReads)));
                        rejected.add(name);
                    }
                }
            });
        }
        Map<String, Sample> kept = new LinkedHashMap<>(samples);
        kept.keySet().removeAll(rejected);
        return new Result(kept, List.copyOf(issues), input, files.size(), subfoldersWithSequenceFiles(input));
    }

    /** The subfolders of {@code input}, a folder, that hold a file named like a sequence file. */
    private static List<Path> subfoldersWithSequenceFiles(Path input) {
        if (!Files.isDirectory(input)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(input)) {
            return entries.filter(Files::isDirectory).filter(folder -> !candidates(folder).isEmpty())
                    .map(folder -> folder.toAbsolutePath().normalize()).sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of(); // only a warning: the run does not need it
        }
    }

    /**
     * A sample is one FASTA file, one FASTQ file or the two mates of a read pair. Files that
     * share a sample name otherwise ({@code x.fasta} and {@code x.fastq}, {@code x.fasta} and
     * {@code x.fna}) would be searched as one, or overwrite each other's results.
     */
    private static void rejectFilesThatAreNotOneSample(Map<String, Sample> samples, List<Issue> issues,
                                                       Set<String> rejected) {
        samples.forEach((name, sample) -> {
            if (!rejected.contains(name) && !isOneSample(sample.files())) {
                issues.add(new Issue(name, fileNames(sample.files()), "these files have the same sample name",
                        "a sample is one FASTA file, one FASTQ file or the _R1 and _R2 files of a read pair;"
                                + " rename the files that are not"));
                rejected.add(name);
            }
        });
    }

    private static boolean isOneSample(List<Path> files) {
        if (files.size() == 1) {
            return true;
        }
        return files.size() == 2
                && files.stream().allMatch(file -> SequenceFileUtils.fileType(file) == Sample.FileType.FASTQ)
                // Read 1 first: the BBTools steps take the first file as in1. A sample's files are in
                // name order whatever the case, so s_r1 comes before s_R2.
                && SequenceFileUtils.mate(files.get(0)) == 1
                && SequenceFileUtils.mate(files.get(1)) == 2;
    }

    /**
     * Each sample's results go to a folder named after it, and two names that differ only in
     * case ({@code iso2.fasta} and {@code ISO2.fna}) are one folder on Windows and macOS.
     */
    private static void rejectNamesThatDifferInCase(Map<String, Sample> samples, List<Issue> issues,
                                                    Set<String> rejected) {
        Map<String, List<String>> byFolder = new LinkedHashMap<>();
        samples.keySet().forEach(name -> byFolder.computeIfAbsent(name.toLowerCase(Locale.ROOT),
                folder -> new ArrayList<>()).add(name));
        byFolder.values().stream().filter(names -> names.size() > 1).forEach(names -> names.forEach(name -> {
            if (rejected.add(name)) {
                issues.add(new Issue(name, fileNames(samples.get(name).files()),
                        "the sample name differs only in case from another one",
                        String.join(", ", names) + " would share a results folder; rename one"));
            }
        }));
    }

    /** Names Windows keeps for its devices: no folder can have one, whatever the extension after it. */
    private static final Set<String> WINDOWS_DEVICES = Set.of("con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    /**
     * Why Windows cannot make a results folder named {@code sample}, or {@code null}. A name ending in
     * a space or a dot ({@code Sample 1 _R1.fastq.gz}) stopped the whole run, not only its sample,
     * with "Trailing char < >"; a device name ({@code con.fasta}) cannot be a folder at all.
     */
    static String windowsFolderProblem(String sample) {
        if (sample.endsWith(" ") || sample.endsWith(".")) {
            return "the sample name ends with " + (sample.endsWith(" ") ? "a space" : "a dot");
        }
        String base = sample.contains(".") ? sample.substring(0, sample.indexOf('.')) : sample;
        if (WINDOWS_DEVICES.contains(base.strip().toLowerCase(Locale.ROOT))) {
            return "the sample name is one Windows keeps for a device";
        }
        return null;
    }

    private static void rejectNamesWindowsCannotUse(Map<String, Sample> samples, List<Issue> issues,
                                                    Set<String> rejected) {
        samples.forEach((name, sample) -> {
            String problem = windowsFolderProblem(name);
            if (problem != null && rejected.add(name)) {
                issues.add(new Issue(name, fileNames(sample.files()), problem,
                        "Windows cannot make its results folder; rename the file"));
            }
        });
    }

    private static List<String> fileNames(List<Path> files) {
        return files.stream().map(file -> file.getFileName().toString()).toList();
    }

    /** The files of {@code input} named like sequence files, in path order. */
    private static List<Path> candidates(Path input) {
        if (!Files.isDirectory(input)) {
            return Files.isRegularFile(input) && SequenceFileUtils.hasExtension(input, SequenceFileUtils.ACCEPTED_EXTENSIONS)
                    ? List.of(input.toAbsolutePath().normalize()) : List.of();
        }
        try (Stream<Path> entries = Files.list(input)) {
            return entries.filter(Files::isRegularFile)
                    .filter(entry -> SequenceFileUtils.hasExtension(entry, SequenceFileUtils.ACCEPTED_EXTENSIONS))
                    .map(entry -> entry.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to list input directory: " + input, e);
        }
    }

    /**
     * What is wrong with one file.
     *
     * @param problem a few words, or {@code null} when the file is fine
     * @param records reads counted in a FASTQ file
     */
    record FileFault(String problem, String detail, long records) {
        static final FileFault NONE = new FileFault(null, "", 0);
    }

    private static Map<Path, FileFault> check(List<Path> files, Depth depth, int threads) {
        Map<Path, FileFault> faults = new LinkedHashMap<>();
        if (files.isEmpty()) {
            return faults;
        }
        // Daemon threads: a window closed during the check must not leave the program running.
        ExecutorService pool = Executors.newFixedThreadPool(Math.clamp(threads, 1, files.size()), task -> {
            Thread worker = new Thread(task, "input-check");
            worker.setDaemon(true);
            return worker;
        });
        try {
            Map<Path, Future<FileFault>> pending = new LinkedHashMap<>();
            for (Path file : files) {
                pending.put(file, pool.submit(() -> check(file, depth)));
            }
            for (Map.Entry<Path, Future<FileFault>> entry : pending.entrySet()) {
                faults.put(entry.getKey(), entry.getValue().get());
            }
        } catch (InterruptedException e) {
            // The run thread is interrupted when the application cancels the run.
            Thread.currentThread().interrupt();
            CancellationException cancelled = new CancellationException("The run was cancelled (input check).");
            cancelled.initCause(e);
            throw cancelled;
        } catch (ExecutionException e) {
            // A file that cannot be read is a fault the check returns, not an exception: what is
            // thrown here is a bug, kept as it is.
            if (e.getCause() instanceof RuntimeException bug) {
                throw bug;
            }
            throw new IllegalStateException("Unable to check the input files", e.getCause());
        } finally {
            // Nothing is left to wait for after a normal end; after a cancelled run the files
            // still being read are dropped (FastqCheck stops at its next block).
            pool.shutdownNow();
        }
        return faults;
    }

    static FileFault check(Path file, Depth depth) {
        return SequenceFileUtils.hasExtension(file, SequenceFileUtils.FASTQ_EXTENSIONS)
                ? fastq(file, depth) : fasta(file);
    }

    private static FileFault fastq(Path file, Depth depth) {
        FastqCheck.Result result = depth == Depth.FULL ? FastqCheck.check(file) : FastqCheck.check(file, 1);
        String problem = switch (result.fault()) {
            case NONE -> null;
            case EMPTY -> "the file holds no read";
            case UNREADABLE -> "the file cannot be read";
            case NOT_FASTQ -> "not a FASTQ file";
            case MALFORMED -> "a read is not well formed";
            case TRUNCATED -> "the file is incomplete";
        };
        return new FileFault(problem, result.detail(), result.records());
    }

    /** The longest part of a header line kept to find its ID: the ID ends at the first blank. */
    private static final int HEADER_KEPT = 1 << 16;

    /**
     * A FASTA file must start with a header, after any blank or comment lines (BLAST skips them too), hold
     * at least one line of sequence, and give each sequence its own ID. It is read to its end, at
     * either depth, in one pass: the IDs are in every header, and a gzipped file cut short is
     * found here rather than at the BLAST search.
     */
    private static FileFault fasta(Path file) {
        boolean sequenceSeen = false;
        try {
            if (Files.size(file) == 0) {
                return new FileFault("the file holds no sequence", "the file is empty", 0);
            }
            // Read a block at a time: a contig written on one line would otherwise be built
            // as one string, for every file being checked at once.
            try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(file)) {
                char[] block = new char[1 << 16];
                Set<String> ids = new HashSet<>();
                boolean beforeFirstHeader = true;
                boolean atLineStart = true;
                boolean comment = false;
                StringBuilder header = null; // the header line being read, '>' included
                int read;
                while ((read = reader.read(block)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        return new FileFault("the file cannot be read", "the check was interrupted", 0);
                    }
                    for (int i = 0; i < read; i++) {
                        char c = block[i];
                        if (c == '\n' || c == '\r') {
                            if (header != null) {
                                FileFault repeated = repeatedId(ids, header);
                                if (repeated != null) {
                                    return repeated;
                                }
                                header = null;
                            }
                            atLineStart = true;
                            comment = false;
                        } else if (comment) {
                            continue; // the rest of a comment line
                        } else if (header != null) {
                            if (header.length() < HEADER_KEPT) {
                                header.append(c);
                            }
                        } else if (atLineStart && c == '>') {
                            beforeFirstHeader = false;
                            atLineStart = false;
                            header = new StringBuilder(">");
                        } else if (FastaScanner.isComment(c, atLineStart)) {
                            comment = true; // as BLAST reads FASTA, before the first header too
                            atLineStart = false;
                        } else if (beforeFirstHeader) {
                            if (!Character.isWhitespace(c)) {
                                return new FileFault("not a FASTA file", "the first line does not start with >", 0);
                            }
                            atLineStart = false; // a blank before a > is not a header
                        } else {
                            atLineStart = false;
                            if (!Character.isWhitespace(c)) {
                                sequenceSeen = true;
                            }
                        }
                    }
                }
                if (header != null) {
                    FileFault repeated = repeatedId(ids, header); // a last line without a line end
                    if (repeated != null) {
                        return repeated;
                    }
                }
                if (beforeFirstHeader) {
                    return new FileFault("the file holds no sequence", "the file is empty", 0);
                }
            }
        } catch (EOFException e) {
            return new FileFault("the file cannot be read", "the gzip stream ends early: the file is incomplete", 0);
        } catch (ZipException e) {
            return new FileFault("the file cannot be read", "not a gzip file, or a damaged one (" + e.getMessage() + ")", 0);
        } catch (IOException e) {
            return new FileFault("the file cannot be read", String.valueOf(e.getMessage()), 0);
        }
        if (!sequenceSeen) {
            return new FileFault("the file holds no sequence", "header lines only", 0);
        }
        return FileFault.NONE;
    }

    /**
     * Two sequences of one file with one ID (assemblies joined into one file, each with contigs
     * named 1, 2, ...): BLAST takes them, but its hits name the ID only, so a primer on one and a
     * primer on the other would be paired into a product of two molecules.
     *
     * @return the fault when {@code header}'s ID is already in {@code ids}, else {@code null}
     */
    private static FileFault repeatedId(Set<String> ids, CharSequence header) {
        String id = SequenceFileUtils.fastaId(header.toString());
        if (id.isEmpty()) {
            // BLAST names such a sequence Query_1, Query_2...: an ID the run's other readers do not know.
            return new FileFault("a sequence has no ID", "a header line holds nothing after its >; give each"
                    + " sequence an ID", 0);
        }
        if (ids.add(id)) {
            return null;
        }
        return new FileFault("two sequences share an ID", "the ID " + id + " names more than one"
                + " sequence; give each its own (the report and BLAST tell sequences apart by ID)", 0);
    }
}
