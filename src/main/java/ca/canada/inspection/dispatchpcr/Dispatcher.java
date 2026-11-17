package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.insilicopcr.MainRun;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pipeline.ChildJavaMemory;
import ca.canada.inspection.pipeline.ExternalProcessTracker;
import ca.canada.inspection.pipeline.InSilicoPcrPipeline;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.pipeline.RunMonitor;
import ca.canada.inspection.pipeline.TadpoleOptions;
import ca.canada.inspection.util.Version;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.PrintStream;
import java.io.PrintWriter;

/** The entry point: the application without arguments, the command line with them. */
public final class Dispatcher {

    private Dispatcher() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            MainRun.main(args);
            return;
        }
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Runs the command line.
     *
     * @return the exit code: 0 done, 1 the run started and failed (a primer file refused for what it holds, no
     *         sample that can be analysed, a program that failed), 2 the command line cannot be used as given (an
     *         option unknown, given twice, or with a wrong value; a path that does not exist; an output folder that
     *         is the input folder or a file): nothing was started
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Options options = options();
        PipelineConfig config;
        // Only the command line is checked here: a failure of the run is not a usage error.
        try {
            CommandLine cmd = new DefaultParser().parse(options, args);
            // First: -v -v printed the version and exited 0, as an option given twice exits 2.
            CliConfig.checkGivenOnce(cmd);
            if (cmd.hasOption("help")) {
                printHelp(out, options);
                return 0;
            }
            if (cmd.hasOption("version")) {
                out.println("insilicoPCR " + Version.VERSION);
                return 0;
            }
            // "C:\my data\" -p ...: the backslash kept the quote, and -i took the rest of the line.
            CliConfig.checkQuotes(cmd);
            if (!cmd.getArgList().isEmpty()) {
                // A shell pattern (-i reads/*.fastq.gz) gives -i its first file and leaves the
                // others here: run, the run would quietly analyse one file.
                throw new IllegalArgumentException("Unexpected argument(s): " + String.join(" ", cmd.getArgList())
                        + ". -i takes one file or one folder: give the folder that holds the files, and quote a"
                        + " path with spaces.");
            }
            config = CliConfig.from(cmd);
        } catch (ParseException | IllegalArgumentException e) {
            // The message and where to look, on standard error: the 82 lines of the help on standard output buried
            // it, and went into the output a script reads.
            err.println("Error: " + e.getMessage());
            err.println("Run insilicoPCR --help for the options.");
            return 2;
        }
        try {
            RUNNING.set(true);
            try {
                new InSilicoPcrPipeline(config, consoleMonitor()).run();
            } finally {
                RUNNING.set(false);
            }
            return 0;
        } catch (java.util.concurrent.CancellationException e) {
            // Killed (kill, Ctrl-C, a workflow manager stopping its job): its programs are stopped, which is
            // no fault to show a trace of.
            err.println("Run cancelled");
            return 1;
        } catch (RuntimeException e) {
            // A failure the user can act on is its message; anything else is a bug and
            // keeps the stack trace a report of it needs.
            if (RunFailure.isForTheUser(e)) {
                RunFailure.lines(e).forEach(err::println);
            } else {
                e.printStackTrace(err);
            }
            return 1;
        }
    }

    /** Whether a run is under way: a run stopped by a signal says so ({@link #consoleMonitor}). */
    private static final java.util.concurrent.atomic.AtomicBoolean RUNNING = new java.util.concurrent.atomic.AtomicBoolean();
    /** What a run stopped by a signal says. */
    static final String STOPPED = "insilicoPCR was stopped before the run ended: its programs are stopped, and its"
            + " reports are missing or incomplete (QAlog.txt is written only by a run that ends)";

    /**
     * The console, with the external programs tracked: killed with this program (kill, a workflow
     * manager stopping its job, Task Manager), which left them running and writing, holding their
     * memory, with nothing to stop them.
     */
    static RunMonitor consoleMonitor() {
        ExternalProcessTracker programs = new ExternalProcessTracker();
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("stop external programs").unstarted(() -> {
            programs.destroyAll();
            if (RUNNING.get()) {
                // Ctrl-C or kill: the run ended with nothing said, its reports missing or cut short.
                System.err.println(STOPPED);
            }
        }));
        return new RunMonitor() {
            @Override
            public ExternalProcessTracker processes() {
                return programs;
            }

            // Once killed, a program that stops is the run being stopped, not a failure to report
            // ("Tadpole failed with exit code 137") nor a step to go on from.
            @Override
            public boolean isCancelled() {
                return programs.isShutDown();
            }
        };
    }

    /** Package-private for unit tests. */
    static Options options() {
        Options options = new Options();
        options.addOption(Option.builder("i").longOpt("input").hasArg().argName("path")
                .desc("Input file, or folder of .fasta/.fastq files (the files directly in it: subfolders are not read)").build());
        options.addOption(Option.builder("o").longOpt("output").hasArg().argName("dir")
                .desc("Directory for output files").build());
        options.addOption(Option.builder("p").longOpt("primers").hasArg().argName("file")
                .desc("Primer FASTA file").build());
        options.addOption(Option.builder("t").longOpt("threads").hasArg().argName("n")
                .desc("Number of worker threads; default: available processors").build());
        options.addOption(Option.builder("m").longOpt("mismatches").hasArg().argName("n")
                .desc("Allowed mismatches+indels per primer (3' bases that do not pair with the template count); default: "
                        + AmpliconOptions.DEFAULT_MISMATCHES).build());
        options.addOption(Option.builder("e").longOpt("evalue").hasArg().argName("value")
                .desc("blastn e-value; deliberately permissive, since primer hits are filtered by the mismatch and 3'/5' rules instead, and lowering it (e.g. to 1e-5) drops valid hits; default: "
                        + PipelineConfig.DEFAULT_EVALUE_TEXT).build());
        options.addOption(Option.builder("l").longOpt("max-length").hasArg().argName("bp")
                .desc("Maximum amplicon length in bp, 0 = unlimited; default: "
                        + AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH).build());
        options.addOption(Option.builder().longOpt("min-length").hasArg().argName("bp")
                .desc("Minimum amplicon length in bp; default: " + AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH)
                .build());
        options.addOption(Option.builder().longOpt("3p-window").hasArg().argName("n")
                .desc("Bases at the primer 3' end checked with the weighted 3' penalty; default: "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW).build());
        options.addOption(Option.builder().longOpt("3p-penalty").hasArg().argName("score")
                .desc("Maximum weighted 3'-end penalty (mismatch: 3 at terminus, 2 at -1/-2, 1 beyond;"
                        + " +1 if a G/C pair is broken near the terminus; an indel scores as a mismatch at its place, +1); default: "
                        + AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY).build());
        options.addOption(Option.builder().longOpt("probe-mismatches").hasArg().argName("n")
                .desc("Allowed mismatches+indels per hydrolysis probe; probes bind hotter than primers"
                        + " and tolerate roughly one more mismatch; default: same as -m").build());
        options.addOption(Option.builder().longOpt("5p-truncation").hasArg().argName("n")
                .desc("Bases at a primer's harmless 5' end that may differ from the template (or be left"
                        + " out of BLAST's alignment) without counting as mismatches; default: "
                        + AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION).build());
        options.addOption(Option.builder().longOpt("tadpole-k").hasArg().argName("n")
                .desc("FASTQ mode: Tadpole k-mer length; default: " + TadpoleOptions.DEFAULT_K).build());
        options.addOption(Option.builder().longOpt("tadpole-min-seed").hasArg().argName("n")
                .desc("FASTQ mode: minimum k-mer count to seed a contig (Tadpole mincountseed); default: "
                        + TadpoleOptions.DEFAULT_MIN_COUNT_SEED).build());
        options.addOption(Option.builder().longOpt("tadpole-min-cov").hasArg().argName("n")
                .desc("FASTQ mode: minimum average contig coverage (Tadpole mincov); default: "
                        + TadpoleOptions.DEFAULT_MIN_COVERAGE).build());
        options.addOption(Option.builder().longOpt("tadpole-min-contig").hasArg().argName("bp")
                .desc("FASTQ mode: minimum contig length kept (Tadpole mincontig); must not exceed the"
                        + " amplicons you expect; default: " + TadpoleOptions.DEFAULT_MIN_CONTIG).build());
        options.addOption(Option.builder().longOpt("read-min-fraction").hasArg().argName("0-1")
                .desc("FASTQ mode: share of informative reads (carrying a primer at the product's locus and long"
                        + " enough) that must hold a complete amplicon to call a product the mini-assembly"
                        + " missed from reads;"
                        + " default: " + ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION).build());
        options.addOption(Option.builder().longOpt("read-extension").hasArg().argName("mode")
                .desc("FASTQ mode: which assays the reads carrying both primers are extended for, through the"
                        + " reads baited in rounds, to find products longer than a read or merged pair. One of: unreported"
                        + " (assays found in no contig and in no read), target (those, and assays that declare"
                        + " size= and show no product of that size yet), off (none); default: "
                        + ReadExtensionCaller.Mode.DEFAULT).build());
        options.addOption(Option.builder().longOpt(CliConfig.MEMORY).hasArg().argName("GB")
                .desc("Memory in GB that the BBTools programs running at once share in FASTQ mode (" + ChildJavaMemory.limits()
                        + "), and that the BLAST searches running at once share, about 2 GB each, for assemblies too."
                        + " Set it on a computer shared with others; default: " + ChildJavaMemory.automatic()).build());
        options.addOption(Option.builder().longOpt(CliConfig.MEMORY_BEFORE_1_0).hasArg().argName("GB")
                .desc("Same as --" + CliConfig.MEMORY + " (its name before 1.0)").build());
        options.addOption(Option.builder().longOpt("input-check").hasArg().argName("full|quick")
                .desc("How the input files are checked before the run. full: every read of every FASTQ file"
                        + " is read, and the two files of a pair must hold the same number of reads. quick: the"
                        + " first read only, faster on large files but blind to a file cut short. A sample with"
                        + " a faulty file is left out with a warning and the others are run; default: "
                        + PipelineConfig.DEFAULT_INPUT_CHECK.option()).build());
        options.addOption(Option.builder("g").longOpt("gel").desc("Also write the synthetic gel image as PNG"
                + " (requires a graphical environment)").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Print help and usage").build());
        options.addOption(Option.builder("v").longOpt("version").desc("Print version").build());
        return options;
    }

    private static void printHelp(PrintStream out, Options options) {
        HelpFormatter formatter = new HelpFormatter();
        PrintWriter writer = new PrintWriter(out);
        formatter.printHelp(writer, formatter.getWidth(),
                "java -jar insilicoPCR.jar -i <input> -o <output> -p <primers> [-t n] [-m n] [-e value] [-l bp]",
                null, options, formatter.getLeftPadding(), formatter.getDescPadding(), null);
        writer.flush();
    }
}
