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
 * @param bbtoolsClassPath BBTools' classes: {@code bbtools.jar}, or an installed BBTools' {@code current} folder
 * @param childMemoryGiB heap given to each BBTools child process
 * @param threads        threads given to each BBTools child process
 */
record BbtoolsCommands(String javaCommand, Path bbtoolsClassPath, int childMemoryGiB, int threads) {
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

        /** The program, as the run's messages name it: the BBTools class it runs ({@code jgi.BBDuk}: BBDuk). */
        String program() {
            for (String argument : arguments) {
                if (argument.matches("[a-z]+\\.[A-Z][A-Za-z0-9]*")) {
                    return argument.substring(argument.indexOf('.') + 1);
                }
            }
            throw new IllegalStateException("No BBTools program in " + arguments);
        }

        void run(RunMonitor monitor) {
            monitor.runProgram(directory, arguments, program());
        }
    }

    /** Commands for the bundled BBTools, each child with this heap and these threads. */
    static BbtoolsCommands of(RuntimeDependencies dependencies, int childMemoryGiB, int threads) {
        return new BbtoolsCommands(dependencies.javaCommand(), dependencies.bbtoolsClassPath(), childMemoryGiB, threads);
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
                : "all it had. Give the BBTools programs more with --memory <GB> (in the application, under"
                        + " FASTQ settings), as much as the computer can spare, up to " + ChildJavaMemory.MAX_CHILD_GIB
                        + " GB each, or run on a computer with more memory.";
        // Exit code 3 is any OutOfMemoryError; this one is not about the heap.
        return program + " ran out of memory" + where + " with -Xmx" + memoryGiB + "g, " + advice
                + " If its output says \"unable to create native thread\", use fewer threads (-t, or Threads in the"
                + " application) instead.";
    }

    BbtoolsCommands {
        Objects.requireNonNull(javaCommand, "javaCommand");
        Objects.requireNonNull(bbtoolsClassPath, "bbtoolsClassPath");
        if (childMemoryGiB < 1) {
            throw new IllegalArgumentException("childMemoryGiB must be >= 1");
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
    }

    /** The shortest k the first bait uses: below it, k-mers of a primer match unrelated reads too often. */
    static final int MIN_BAIT_K = 16;

    /**
     * The first bait's k: the shortest oligo's length less the 5' bases a hit may leave unaligned
     * ({@code --5p-truncation}), so that a primer whose 5' bases do not match the reads still shares a
     * k-mer with them, as a hit on an assembly would still be accepted; not below {@link #MIN_BAIT_K} plus
     * the substitutions allowed ({@code mismatches}), nor above the shortest oligo. A k as long as the
     * primer could not match across an indel either: the read's k-mer then spans one base more of the
     * template than the primer has. Each substitution allowed keeps one more base: 3 in a 16-mer matched
     * random reads, which baited 68 times the reads of a run at -m 3 and ran 3 times as long. When indels
     * are allowed ({@code mismatches} above 0), at most one base shorter than the shortest oligo, so that
     * a deletion in a read can be matched, even where the floor would ask for the whole oligo.
     */
    static int firstBaitK(int shortestOligo, int fivePrimeTruncation, int mismatches) {
        int longest = mismatches > 0 ? shortestOligo - 1 : shortestOligo;
        return Math.min(longest, Math.max(MIN_BAIT_K + mismatches, shortestOligo - fivePrimeTruncation));
    }

    /** The most insertions and deletions BBDuk matches k-mers within ({@code edist}): it refuses more. */
    static final int MAX_BAIT_INDELS = 2;
    /**
     * The most substitutions BBDuk matches k-mers within ({@code hdist}): it refuses more, and {@code -m 4}
     * failed every run with reads. In the first bait it adds nothing: BBDuk expands its k-mers by
     * {@code edist} alone when that is above 0, as it is whenever {@code hdist} is, so that bait matches
     * within {@code min(-m, 2)} edits (docs/wiki/Limitations.md, "Known gaps").
     */
    static final int MAX_BAIT_SUBSTITUTIONS = 3;

    /**
     * First pass: keep reads sharing a k-mer with any primer within {@code mismatches} edits, at most
     * {@link #MAX_BAIT_INDELS} ({@code edist}): substitutions, insertions and one deletion, the most BBDuk
     * matches in a k-mer (it has one base past the k-mer to shift in). {@code -m} counts all three, so that
     * what a hit on an assembly would accept also baits reads. BBDuk ignores the {@code hdist} also given
     * when {@code edist} is set: at {@code -m 3} a k-mer still matches within 2 edits, not 3 substitutions.
     */
    Command firstBait(Path primerDatabase, List<Path> reads, Path output, int kLength, int mismatches) {
        Command command = bbduk(primerDatabase, reads, output, mismatches);
        return withOption(withOption(command, "edist=" + Math.min(mismatches, MAX_BAIT_INDELS)), "k=" + kLength);
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
                "out=" + outputArgument(directory, merged),
                "outu=" + outputArgument(directory, unmerged),
                "overwrite=t",
                SAME_ORDER,
                "threads=" + threads));
        return new Command(directory, arguments);
    }

    /**
     * Cuts adapter read-through (BBTools' own adapter list, {@code ref=adapters}) and poly-G tails off
     * {@code reads}, one file, pairs one mate after the other when {@code paired}: the bait anchors, before
     * they bait (an adapter in one baited every adapter dimer of the library). Small files: a fraction of a
     * second.
     */
    Command trimAdapters(Path reads, boolean paired, Path output) {
        Path directory = directoryOf(output);
        List<String> arguments = start("jgi.BBDuk");
        arguments.addAll(List.of(
                "in=" + argument(directory, reads),
                "out=" + outputArgument(directory, output),
                "ref=adapters",
                // Each mate on its own (not tpe, and rieb=f): an adapter dimer's mate cut to nothing took its
                // mate, a genuine anchor holding the primer site, with it.
                "ktrim=r", "k=23", "mink=11", "hdist=1", paired ? "tbo" : "tbo=f", "rieb=f",
                "trimpolyg=10",
                "interleaved=" + (paired ? "t" : "f"),
                SAME_ORDER,
                "overwrite=t",
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

    /**
     * {@link #recruit} from reads a recruit already baited ({@code baited}, one file; pairs one mate after the
     * other when {@code paired}), with a reference that is part of that recruit's: the same reads, in the same
     * order, as {@code recruit} from the sample's files gives, for a pass over a few thousand reads.
     */
    Command recruitFrom(Path ref, Path baited, boolean paired, Path output, int k) {
        Command command = recruit(ref, List.of(baited), output, k);
        List<String> arguments = new java.util.ArrayList<>(command.arguments());
        arguments.replaceAll(argument -> argument.equals("interleaved=f") ? "interleaved=" + (paired ? "t" : "f")
                : argument);
        return new Command(command.directory(), arguments);
    }

    /**
     * The mini-assembly, its contigs built on one thread: built on several, Tadpole's contigs differed between
     * runs of the same reads (a circle starting elsewhere, contigs numbered otherwise, one in a thousand lost or
     * joined), and so did the report. Its k-mers are loaded on every thread ({@code buildthreads=1} keeps the
     * contigs the same, as Tadpole's help says), into tables of {@code ways=31}: on 16 threads Tadpole made
     * 41 of them, and 13 of 1,159 circles came out turned. The contigs were those of {@code threads=1} on every
     * run tried (9 of 9), in 112 s rather than 305 on 9.7 million baited reads, 43 rather than 86 on 1.7 million.
     */
    Command assemble(Path reads, Path output, TadpoleOptions tadpole) {
        Path directory = directoryOf(output);
        List<String> arguments = start("assemble.Tadpole");
        arguments.addAll(List.of(
                "in=" + argument(directory, reads),
                "out=" + outputArgument(directory, output),
                "overwrite=t",
                "threads=" + threads,
                "buildthreads=1",
                "ways=31"));
        arguments.addAll(tadpole.arguments());
        return new Command(directory, arguments);
    }

    /**
     * Extends each seed both ways, by up to {@code reach} bases, through the k-mers of
     * {@code reads}. At a branch the better-supported path is always followed
     * ({@code branchmult=1}): Tadpole's defaults stop there, which is what breaks the
     * mini-assembly inside a multi-copy locus, where near-balanced variants are the rule.
     */
    Command extend(Path seeds, List<Path> reads, Path output, int k, int reach) {
        Path directory = directoryOf(output);
        List<String> arguments = start("assemble.Tadpole");
        arguments.addAll(List.of(
                "in=" + argument(directory, seeds),
                "out=" + outputArgument(directory, output),
                "mode=extend",
                "el=" + reach,
                "er=" + reach,
                "extra=" + String.join(",", reads.stream().map(file -> argument(directory, file)).toList()),
                "k=" + k));
        arguments.addAll(EXTEND_OPTIONS);
        arguments.addAll(List.of("overwrite=t", "threads=" + threads));
        return new Command(directory, arguments);
    }

    /**
     * The Java runtime running {@code program} of BBTools (its jar or its classes folder): directly, or through
     * {@link BbtoolsLauncher} when that path has a letter outside ASCII.
     */
    private List<String> start(String program) {
        List<String> arguments = new ArrayList<>(List.of(javaCommand, "-ea", "-Xmx" + childMemoryGiB + "g",
                EXIT_ON_OUT_OF_MEMORY));
        if (BbtoolsLauncher.needed(bbtoolsClassPath)) {
            BbtoolsLauncher.checkUsable(bbtoolsClassPath);
            arguments.addAll(List.of("-cp", launcherClassPath(), BbtoolsLauncher.class.getName(),
                    bbtoolsClassPath.toAbsolutePath().normalize().toString(), program));
        } else {
            arguments.addAll(List.of("-cp", bbtoolsClassPath.toString(), program));
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
     * when that has a comma or an equals sign, its path relative to the folder. The relative path is worked out
     * from both as they are on disk, symbolic links resolved: the system resolves {@code ..} from
     * the real folder a program runs in, not from the path it was reached by.
     *
     * @throws PipelineException when it still has a comma or an equals sign, which BBTools would split it at
     */
    static String argument(Path directory, Path file) {
        return argument(directory, file, false);
    }

    /**
     * As {@link #argument(Path, Path)}, for a file BBTools writes: one whose path has a {@code #} is given relative
     * to the folder too. In a name it writes, BBTools reads {@code #} as the place of the mate's number: it wrote
     * {@code /data/run#2/x.fq} as {@code /data/run12/x.fq} and {@code /data/run22/x.fq}, and the run failed.
     *
     * @throws PipelineException when the file's own name has a {@code #}
     */
    static String outputArgument(Path directory, Path file) {
        return argument(directory, file, true);
    }

    private static String argument(Path directory, Path file, boolean written) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!splitByBbtools(absolute.toString(), written)) {
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
            throw new UnusablePath("BBTools reads a comma in a file name as a list of files, and cannot read "
                    + absolute + ": remove the comma from the name of the file or of its folder");
        }
        if (argument.contains("=")) {
            // in1=/data/run=3/a.fq: BBTools reads the path from the last '=', "3/a.fq".
            throw new UnusablePath("BBTools reads an equals sign in a file name as the start of the value, and"
                    + " cannot read " + absolute + ": remove the '=' from the name of the file or of its folder");
        }
        if (written && argument.indexOf('#') >= 0) {
            throw new UnusablePath("BBTools reads a # in the name of a file it writes as the place of a mate's"
                    + " number, and cannot write " + absolute + ": remove the # from the name of the read file");
        }
        return argument;
    }

    /** A file BBTools cannot be given, its path holding a comma or an equals sign. */
    static final class UnusablePath extends PipelineException {
        UnusablePath(String message) {
            super(message);
        }
    }

    /**
     * Whether BBTools would read {@code path} as something else: a list (a comma), a value from its '=', or, in a
     * file it writes, two files, one per mate (a '#').
     */
    private static boolean splitByBbtools(String path, boolean written) {
        return path.indexOf(',') >= 0 || path.indexOf('=') >= 0 || written && path.indexOf('#') >= 0;
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
                "hdist=" + Math.min(hdist, MAX_BAIT_SUBSTITUTIONS),
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
        arguments.add("outm=" + outputArgument(directory, output));
        return new Command(directory, arguments);
    }
}
