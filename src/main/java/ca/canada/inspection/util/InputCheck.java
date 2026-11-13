package ca.canada.inspection.util;

import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
     * @param files   file names, one or the two mates
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
     */
    public record Result(Map<String, Sample> samples, List<Issue> issues, Path input, int files) {

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
            if (files == 0) {
                lines.add("No FASTA or FASTQ file in " + input + ": nothing to analyse.");
            } else if (samples.isEmpty()) {
                lines.add("No sample of " + input + " can be analysed.");
            }
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
        return new Result(kept, List.copyOf(issues), input, files.size());
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
            throw new IllegalStateException("Unable to list input directory: " + input, e);
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
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while checking the input files", e);
        } catch (ExecutionException e) {
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
                ? fastq(file, depth) : fasta(file, depth);
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

    /**
     * A FASTA file must start with a header and hold at least one line of sequence. A
     * gzipped one is read to its end at {@link Depth#FULL}: assemblies are small, and a
     * stream cut short would otherwise stop the run at the BLAST search.
     */
    private static FileFault fasta(Path file, Depth depth) {
        boolean gzipped = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        boolean sequenceSeen = false;
        try {
            if (Files.size(file) == 0) {
                return new FileFault("the file holds no sequence", "the file is empty", 0);
            }
            // Read a block at a time: a contig written on one line would otherwise be built
            // as one string, for every file being checked at once.
            try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(file)) {
                char[] block = new char[1 << 16];
                boolean first = true;
                boolean atLineStart = true;
                boolean inHeader = false;
                int read;
                scan:
                while ((read = reader.read(block)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        return new FileFault("the file cannot be read", "the check was interrupted", 0);
                    }
                    for (int i = 0; i < read; i++) {
                        char c = block[i];
                        if (first) {
                            first = false;
                            if (c != '>') {
                                return new FileFault("not a FASTA file", "the first line does not start with >", 0);
                            }
                        }
                        if (c == '\n' || c == '\r') {
                            atLineStart = true;
                            inHeader = false;
                        } else if (atLineStart && c == '>') {
                            atLineStart = false;
                            inHeader = true;
                        } else {
                            atLineStart = false;
                            if (!inHeader && !Character.isWhitespace(c)) {
                                sequenceSeen = true;
                                if (!gzipped || depth == Depth.QUICK) {
                                    break scan;
                                }
                                // A gzipped file is read on to its end, to see that the stream is whole.
                                break;
                            }
                        }
                    }
                }
                if (first) {
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
        return sequenceSeen ? FileFault.NONE
                : new FileFault("the file holds no sequence", "header lines only", 0);
    }
}
