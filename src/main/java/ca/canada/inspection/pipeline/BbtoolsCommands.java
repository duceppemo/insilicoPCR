package ca.canada.inspection.pipeline;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds the BBTools command lines of FASTQ mode: baiting and recruiting (BBDuk),
 * merging (BBMerge), assembly and read extension (Tadpole).
 *
 * @param javaCommand    Java launcher used for the BBTools child processes
 * @param bbtoolsJar     bundled {@code bbtools.jar}
 * @param childMemoryGiB heap given to each BBTools child process
 * @param threads        threads given to each BBTools child process
 */
record BbtoolsCommands(String javaCommand, Path bbtoolsJar, int childMemoryGiB, int threads) {
    /**
     * Makes BBDuk and BBMerge write reads in the order they read them. With several threads
     * they otherwise write them as each thread finishes, a different order every run, and
     * the read-level search numbers the reads in the order it finds them ({@code read_<n>}),
     * so the read a call names in the report would change from run to run.
     */
    private static final String SAME_ORDER = "ordered=t";
    /**
     * Makes a child whose Java runtime runs out of heap exit with {@link #OUT_OF_MEMORY_EXIT}.
     * Without it the exit code is that of any other error (BBTools catches the error only to
     * stop), and an error in a worker thread may even leave the program running on. It is
     * one of two signals: BBTools also stops itself, with exit code 1, when a k-mer table is
     * full ({@link #OUT_OF_MEMORY_LINE}); {@link #ranOutOfMemory} takes both.
     */
    private static final String EXIT_ON_OUT_OF_MEMORY = "-XX:+ExitOnOutOfMemoryError";
    /** Exit code of the Java runtime when {@code -XX:+ExitOnOutOfMemoryError} stops it. */
    static final int OUT_OF_MEMORY_EXIT = 3;
    /**
     * What BBTools prints when it stops for lack of memory ({@code KillSwitch.memKill}, then
     * exit code 1). Its k-mer tables are sized from the heap and throw their own
     * {@code OutOfMemoryError} when full, which the Java runtime does not see: Tadpole on a
     * large sample stops this way with heap to spare.
     */
    static final String OUT_OF_MEMORY_LINE = "This program ran out of memory.";
    /**
     * How the read extension's Tadpole follows the reads: at a branch, always the better-supported
     * path ({@code branchmult=1}); a base is added when at least two reads hold it.
     */
    static final List<String> EXTEND_OPTIONS = List.of("branchmult1=1", "branchmult2=1", "mincountextend=2");

    /**
     * A BBTools command and the folder it runs in: the folder of the file it writes. Its file
     * arguments are absolute paths, except one with a comma, which BBTools would read as a list
     * of files: that one is given relative to the folder, leaving out the folders above that the
     * file and the folder share (see {@link #argument}).
     */
    record Command(Path directory, List<String> arguments) {
        Command {
            arguments = List.copyOf(arguments);
        }

        void run(RunMonitor monitor, String program) {
            monitor.runProgram(directory, arguments, program);
        }
    }

    /** Commands for the bundled BBTools, each child with this heap and these threads. */
    static BbtoolsCommands of(RuntimeDependencies dependencies, int childMemoryGiB, int threads) {
        return new BbtoolsCommands(dependencies.javaCommand(), dependencies.bbtoolsJar(), childMemoryGiB, threads);
    }

    /** Whether a BBTools child stopped because it ran out of memory. */
    static boolean ranOutOfMemory(Throwable error) {
        return error instanceof ProgramFailedException failed
                && (failed.exitCode() == OUT_OF_MEMORY_EXIT || failed.saidOutOfMemory());
    }

    /**
     * What to tell the user about a child that ran out of memory with {@code memoryGiB}.
     *
     * @param where where it ran out, such as {@code " on sample x"}, or empty
     */
    static String outOfMemoryMessage(String program, String where, int memoryGiB) {
        String advice = memoryGiB >= ChildJavaMemory.MAX_CHILD_GIB
                ? "the most insilicoPCR gives a BBTools program."
                : "all it had. Give the BBTools programs more with --bbtools-memory <GB> (in the application, under"
                        + " FASTQ settings), as much as the computer can spare, up to " + ChildJavaMemory.MAX_CHILD_GIB
                        + " GB each, or run on a computer with more memory.";
        // Exit code 3 is any OutOfMemoryError; this one is not about the heap.
        return program + " ran out of memory" + where + " with -Xmx" + memoryGiB + "g, " + advice
                + " If its output says \"unable to create native thread\", use fewer threads (-t) instead.";
    }

    BbtoolsCommands {
        Objects.requireNonNull(javaCommand, "javaCommand");
        Objects.requireNonNull(bbtoolsJar, "bbtoolsJar");
        if (childMemoryGiB < 1) {
            throw new IllegalArgumentException("childMemoryGiB must be >= 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
    }

    /**
     * First pass: keep reads sharing a k-mer with any primer, allowing
     * {@code hdist} mismatches so primers with tolerated mismatches still bait.
     */
    Command firstBait(Path primerDatabase, List<Path> reads, Path output, int kLength, int hdist) {
        Command command = bbduk(primerDatabase, reads, output, hdist);
        return withOption(command, "k=" + kLength);
    }

    /**
     * Merges overlapping first-pass pairs so a read-level search can find amplicons
     * longer than one read (up to the fragment length).
     */
    Command mergePairs(Path interleavedPairs, Path merged, Path unmerged) {
        Path directory = directoryOf(merged);
        List<String> arguments = start("jgi.BBMerge");
        arguments.addAll(List.of(
                "in=" + argument(directory, interleavedPairs),
                "interleaved=t",
                "out=" + argument(directory, merged),
                "outu=" + argument(directory, unmerged),
                "overwrite=t",
                SAME_ORDER,
                "threads=" + threads));
        return new Command(directory, arguments);
    }

    /**
     * Second pass: recruit reads overlapping the first-pass matches. Exact k-mer
     * matching suffices here; expanding this much larger reference by hdist grows
     * the k-mer table by ~hdist^k-fold (tens of billions of entries at hdist=2 on a
     * large match set) and exhausts the child heap.
     */
    Command secondBait(Path firstPassMatches, List<Path> reads, Path output) {
        return bbduk(firstPassMatches, reads, output, 0);
    }

    /**
     * Third pass, on the sample's own reads: keep every read, with its mate, sharing a
     * k-mer with a candidate product of the read extension. The baited reads do not
     * hold them all: far from the primers they thin out, and the reads of a second copy
     * of a repeat that say where the repeat ends are among those missing.
     */
    Command recruit(Path candidates, List<Path> reads, Path output, int k) {
        return withOption(bbduk(candidates, reads, output, 0), "k=" + k);
    }

    Command assemble(Path reads, Path output, TadpoleOptions tadpole) {
        Path directory = directoryOf(output);
        List<String> arguments = start("assemble.Tadpole");
        arguments.addAll(List.of(
                "in=" + argument(directory, reads),
                "out=" + argument(directory, output),
                "overwrite=t",
                "threads=" + threads));
        arguments.addAll(tadpole.arguments());
        return new Command(directory, arguments);
    }

    /**
     * Extends each seed both ways, by up to {@code reach} bases, through the k-mers of
     * {@code reads}. At a branch the better-supported path is always followed
     * ({@code branchmult=1}): Tadpole's defaults stop there, which is what breaks the
     * mini-assembly inside a multi-copy locus, where near-balanced variants are the rule.
     */
    Command extend(Path seeds, Path reads, Path output, int k, int reach) {
        Path directory = directoryOf(output);
        List<String> arguments = start("assemble.Tadpole");
        arguments.addAll(List.of(
                "in=" + argument(directory, seeds),
                "out=" + argument(directory, output),
                "mode=extend",
                "el=" + reach,
                "er=" + reach,
                "extra=" + argument(directory, reads),
                "k=" + k));
        arguments.addAll(EXTEND_OPTIONS);
        arguments.addAll(List.of("overwrite=t", "threads=" + threads));
        return new Command(directory, arguments);
    }

    /**
     * The Java runtime running {@code program} of the BBTools jar: directly, or through
     * {@link BbtoolsLauncher} when the jar's path has a letter outside ASCII.
     */
    private List<String> start(String program) {
        List<String> arguments = new ArrayList<>(List.of(javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g",
                EXIT_ON_OUT_OF_MEMORY));
        if (BbtoolsLauncher.needed(bbtoolsJar)) {
            arguments.addAll(List.of("-cp", launcherClassPath(), BbtoolsLauncher.class.getName(),
                    bbtoolsJar.toAbsolutePath().normalize().toString(), program));
        } else {
            arguments.addAll(List.of("-cp", bbtoolsJar.toString(), program));
        }
        return arguments;
    }

    /** Where {@link BbtoolsLauncher} is: insilicoPCR's jar, or its classes folder while developing. */
    static String launcherClassPath() {
        try {
            return Path.of(BbtoolsLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize().toString();
        } catch (java.net.URISyntaxException | RuntimeException e) {
            throw new IllegalStateException("Unable to find insilicoPCR's own classes, to start BBTools from them", e);
        }
    }

    private static Command withOption(Command command, String option) {
        List<String> arguments = new ArrayList<>(command.arguments());
        arguments.add(option);
        return new Command(command.directory(), arguments);
    }

    /** The folder a command writing {@code output} runs in. */
    private static Path directoryOf(Path output) {
        return output.toAbsolutePath().normalize().getParent();
    }

    /**
     * {@code file} as an argument of a command running in {@code directory}: its absolute path, or,
     * when that has a comma, its path relative to the folder. The relative path is worked out
     * from both as they are on disk, symbolic links resolved: the system resolves {@code ..} from
     * the real folder a program runs in, not from the path it was reached by.
     *
     * @throws PipelineException when it still has a comma, which BBTools would split it at
     */
    static String argument(Path directory, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.toString().indexOf(',') < 0) {
            return absolute.toString();
        }
        Path real = onDisk(absolute);
        String argument;
        try {
            argument = onDisk(directory.toAbsolutePath().normalize()).relativize(real).toString();
        } catch (IllegalArgumentException otherRoot) {
            argument = real.toString();
        }
        if (argument.contains(",")) {
            throw new PipelineException("BBTools reads a comma in a file name as a list of files, and cannot read "
                    + absolute + ": remove the comma from the name of the file or of its folder");
        }
        return argument;
    }

    /** {@code path} with its symbolic links resolved, as far as it exists (an output may not, yet). */
    private static Path onDisk(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException notThere) {
            Path parent = path.getParent();
            return parent == null ? path : onDisk(parent).resolve(path.getFileName());
        }
    }

    private Command bbduk(Path ref, List<Path> reads, Path output, int hdist) {
        if (reads.isEmpty() || reads.size() > 2) {
            throw new IllegalArgumentException("Expected one or two read files, got " + reads.size());
        }
        Path directory = directoryOf(output);
        List<String> arguments = start("jgi.BBDuk");
        arguments.addAll(List.of(
                "ref=" + argument(directory, ref),
                "hdist=" + hdist,
                "threads=" + threads,
                "overwrite=t",
                SAME_ORDER));
        if (reads.size() == 2) {
            arguments.add("in1=" + argument(directory, reads.get(0)));
            arguments.add("in2=" + argument(directory, reads.get(1)));
            arguments.add("interleaved=t");
        } else {
            // Single-end reads: told so, or BBDuk takes consecutive reads for the mates of a pair
            // (it then stops on an odd count, and bait each matching read's unrelated neighbour).
            arguments.add("in=" + argument(directory, reads.getFirst()));
            arguments.add("interleaved=f");
        }
        arguments.add("outm=" + argument(directory, output));
        return new Command(directory, arguments);
    }
}
