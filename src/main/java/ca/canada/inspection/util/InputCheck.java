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
     * @param sampleFiles all the files of the sample, the faulty one among them: both mates of a pair one of which is
     *                    cut short ({@code report_samples.tsv} lists the sample's files, not only the faulty one)
     */
    public record Issue(String sample, List<String> files, String problem, String detail, List<String> sampleFiles) {
        public Issue {
            files = List.copyOf(files);
            sampleFiles = List.copyOf(sampleFiles);
        }

        /** An issue whose files are all the sample's. */
        public Issue(String sample, List<String> files, String problem, String detail) {
            this(sample, files, problem, detail, files);
        }

        /** The files and what is wrong with them. */
        public String describe() {
            return visible(String.join(" and ", files) + ": " + problem + (detail.isEmpty() ? "" : " (" + detail + ")"));
        }

        /** One line for the terminal and the application's output area. */
        public String line() {
            return describe() + ". Sample " + visible(sample) + " is not analysed.";
        }
    }

    /**
     * {@code text} with its control and invisible characters written as {@code \\u001b}: a file name holding
     * an escape sequence set the terminal's title and cleared its screen when a warning named it, and one holding
     * a line break wrote a line of its own in QAlog.txt.
     */
    public static String visible(String text) {
        StringBuilder shown = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (invisible(c)) {
                shown.append(String.format(Locale.ROOT, "\\u%04x", c));
            } else {
                shown.appendCodePoint(c);
            }
        });
        return shown.toString();
    }

    /** A control character, or one that shows nothing or reorders the text (zero-width, bidirectional marks). */
    static boolean invisible(int c) {
        return Character.isISOControl(c) || Character.getType(c) == Character.FORMAT;
    }

    /**
     * @param samples the samples to run, in name order; none of their files has an issue
     * @param issues  what was left out, in file order
     * @param input   the file or folder that was scanned
     * @param files   how many files with a sequence file extension were looked at
     * @param subfoldersNotRead the folder's subfolders that hold sequence files, which are not read
     * @param namesNotRead the names of the folder's files named like sequence files of a kind not read
     *                     ({@code x.fas}, {@code x.fasta.bz2})
     */
    public record Result(Map<String, Sample> samples, List<Issue> issues, Path input, int files,
                         List<Path> subfoldersNotRead, List<String> namesNotRead) {

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
            // One read of a pair, its mate not given: run as single-end reads, half the data unused, without a word.
            samples.values().stream().filter(sample -> sample.isFastq() && sample.files().size() == 1).forEach(sample -> {
                Path file = sample.files().getFirst();
                int mate = SequenceFileUtils.mate(file);
                if (mate > 0) {
                    lines.add(file.getFileName() + " is named as read " + mate + " of a pair, but its mate is not in the"
                            + " input: sample " + sample.getName() + " is run as single-end reads");
                }
            });
            // A sample in a per-sample folder (as sequencers' software writes them) was left out without a word.
            subfoldersNotRead.forEach(folder -> lines.add("the folder " + folder + " holds sequence files, which are"
                    + " not read: only the files directly in " + input + " are. Give that folder as an input of its own,"
                    + " or move its files up."));
            // x.fas or x.fasta.bz2 beside the other inputs was left out without a word.
            namesNotRead.forEach(name -> lines.add(name + " is not read: input files are named "
                    + String.join(", ", SequenceFileUtils.ACCEPTED_EXTENSIONS)));
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
        return scan(input, depth, threads, null);
    }

    /**
     * As {@link #scan(Path, Depth, int)}, {@code output} (the run's output folder, which may lie in the
     * input folder) not taken for a subfolder of inputs: its primer file made it one, with a misleading warning.
     */
    public static Result scan(Path input, Depth depth, int threads, Path output) {
        List<Path> files = candidates(input);
        Map<Path, FileFault> faults = check(files, depth, threads);

        Map<String, Sample> samples = SequenceFileUtils.sampleMapOf(files);
        List<Issue> issues = new ArrayList<>();
        Set<String> rejected = new LinkedHashSet<>();
        // A link to a file that is not there: left out without a word, its sample with it.
        for (Path entry : namedLikeInputs(input)) {
            if (!Files.isRegularFile(entry)) {
                String sample = SequenceFileUtils.sampleName(entry);
                issues.add(new Issue(sample, List.of(entry.getFileName().toString()), "the file cannot be read",
                        Files.isSymbolicLink(entry) ? "a link to a file that does not exist" : "not a file"));
                rejected.add(sample);
            }
        }
        for (Path file : files) {
            FileFault fault = faults.get(file);
            if (fault.problem() != null) {
                String sample = SequenceFileUtils.sampleName(file);
                Sample all = samples.get(sample);
                issues.add(new Issue(sample, List.of(file.getFileName().toString()), fault.problem(), fault.detail(),
                        all == null ? List.of(file.getFileName().toString()) : fileNames(all.files())));
                rejected.add(sample);
            }
        }
        rejectFilesThatAreNotOneSample(samples, issues, rejected);
        rejectNamesThatAreNoName(samples, issues, rejected);
        rejectNamesThatDifferInCase(samples, issues, rejected);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            rejectNamesWindowsCannotUse(samples, issues, rejected);
        }
        if (depth == Depth.FULL) {
            samples.forEach((name, sample) -> {
                if (!rejected.contains(name) && sample.isPaired()) {
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
        return new Result(kept, List.copyOf(issues), input, files.size(), subfoldersWithSequenceFiles(input, output),
                namesNotRead(input));
    }

    /** Names of a sequence file, of a kind the run does not read: x.fas, x.fasta.bz2, x.fq.xz. */
    private static final java.util.regex.Pattern LIKE_A_SEQUENCE_FILE =
            java.util.regex.Pattern.compile("(?i).+\\.(fasta|fas|fa|fna|ffn|fsa|faa|fastq|fq)(\\.[a-z0-9]{1,4})?$");

    /** The names of the files of {@code input}, a folder, named like sequence files of a kind it does not read. */
    private static List<String> namesNotRead(Path input) {
        if (!Files.isDirectory(input)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(input)) {
            return entries.filter(entry -> !Files.isDirectory(entry))
                    .map(entry -> entry.getFileName().toString())
                    .filter(name -> LIKE_A_SEQUENCE_FILE.matcher(name).matches()
                            && !SequenceFileUtils.hasExtension(Path.of(name), SequenceFileUtils.ACCEPTED_EXTENSIONS))
                    .sorted().toList();
        } catch (IOException | UncheckedIOException e) {
            return List.of(); // only a warning: the run does not need it
        }
    }

    /** The entries of {@code input}, a folder, named as input files, files or not (a link to nothing). */
    private static List<Path> namedLikeInputs(Path input) {
        if (!Files.isDirectory(input)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(input)) {
            return entries.filter(entry -> !Files.isDirectory(entry))
                    .filter(entry -> SequenceFileUtils.hasExtension(entry, SequenceFileUtils.ACCEPTED_EXTENSIONS))
                    .sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to list input directory: " + input, e);
        }
    }

    /** The subfolders of {@code input}, a folder, that hold a file named like a sequence file, {@code output} not. */
    private static List<Path> subfoldersWithSequenceFiles(Path input, Path output) {
        if (!Files.isDirectory(input)) {
            return List.of();
        }
        Path outputFolder = output == null ? null : output.toAbsolutePath().normalize();
        try (Stream<Path> entries = Files.list(input)) {
            return entries.filter(Files::isDirectory).filter(folder -> !candidates(folder).isEmpty())
                    .map(folder -> folder.toAbsolutePath().normalize())
                    .filter(folder -> !folder.equals(outputFolder)).sorted().toList();
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
        // In one Unicode form too: café typed and café decomposed look alike, and share a folder on macOS.
        samples.keySet().forEach(name -> byFolder.computeIfAbsent(java.text.Normalizer.normalize(name,
                java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT), folder -> new ArrayList<>()).add(name));
        byFolder.values().stream().filter(names -> names.size() > 1).forEach(names -> names.forEach(name -> {
            if (rejected.add(name)) {
                issues.add(new Issue(name, fileNames(samples.get(name).files()),
                        "the sample name differs only in case (or in its Unicode form) from another one",
                        String.join(", ", names) + " would share a results folder; rename one"));
            }
        }));
    }

    /**
     * Names Windows keeps for its devices: no folder can have one, whatever the extension after it. COM and LPT
     * with 0 to 9 or a superscript 1, 2 or 3 (as Microsoft lists them), and the console's own two names.
     */
    private static final Set<String> WINDOWS_DEVICES = windowsDevices();

    private static Set<String> windowsDevices() {
        Set<String> names = new HashSet<>(Set.of("con", "prn", "aux", "nul", "conin$", "conout$"));
        for (String port : List.of("com", "lpt")) {
            for (char digit : "0123456789\u00b9\u00b2\u00b3".toCharArray()) {
                names.add(port + digit);
            }
        }
        return Set.copyOf(names);
    }

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

    /**
     * A file named {@code .fasta}, {@code ..fasta} or {@code ...fasta} makes the sample "", "." or "..": its
     * results went to the report folder itself, or to the output folder above it.
     */
    private static void rejectNamesThatAreNoName(Map<String, Sample> samples, List<Issue> issues, Set<String> rejected) {
        samples.forEach((name, sample) -> {
            if ((name.isBlank() || name.equals(".") || name.equals("..")) && rejected.add(name)) {
                issues.add(new Issue(name, fileNames(sample.files()), "the file's name makes no sample name",
                        "the name before the extension is empty, . or ..; rename the file"));
            } else if (name.codePoints().anyMatch(InputCheck::invisible) && rejected.add(name)) {
                // A line break wrote lines of its own in QAlog.txt (made-up samples on reopening it), an escape
                // sequence took over the terminal, a zero-width or right-to-left mark made two names look alike.
                issues.add(new Issue(name, fileNames(sample.files()), "the file's name holds a control or invisible"
                        + " character", "rename the file"));
            }
        });
    }

    private static List<String> fileNames(List<Path> files) {
        return files.stream().map(file -> file.getFileName().toString()).toList();
    }

    /**
     * What to say when {@code input} holds no input file: a mistyped -i, or a folder of per-sample folders.
     * The run and the window say the same ({@code InputValidator}).
     *
     * @return the message, or {@code null} when it holds one
     */
    public static String noInputFiles(Path input) {
        if (!candidates(input).isEmpty()) {
            return null;
        }
        List<Path> subfolders = subfoldersWithSequenceFiles(input, null);
        if (!subfolders.isEmpty()) {
            return "No FASTA or FASTQ file directly in " + input + ": its subfolders " + subfolders.stream()
                    .map(folder -> folder.getFileName().toString()).collect(java.util.stream.Collectors.joining(", "))
                    + " hold some, but subfolders are not read. Give one of them as the input, or move their files up"
                    + " into one folder.";
        }
        // Most likely a mistyped -i: a run of nothing would end as if it had worked (exit 0).
        return "No FASTA or FASTQ file in " + input + ": nothing to analyse (input files are named "
                + String.join(", ", SequenceFileUtils.ACCEPTED_EXTENSIONS) + ")";
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
        // Daemon threads: a window closed during the check must not leave the program running. Not closed in a
        // try-with-resources: close() waits for every file, and a cancelled run must not (shutdownNow, below).
        //noinspection resource
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
            return new FileFault("the file cannot be read", SequenceFileUtils.why(e), 0);
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
