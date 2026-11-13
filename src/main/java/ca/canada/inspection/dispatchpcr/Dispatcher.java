package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.commandpcr.CommandMain;
import ca.canada.inspection.insilicopcr.MainRun;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.pipeline.TadpoleOptions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public final class Dispatcher {

    public static final String VERSION = "0.12.1";

    /**
     * Compatibility alias for older code paths and release branches.
     * Prefer {@link #VERSION} in new code.
     */
    @Deprecated(since = "0.6.1", forRemoval = false)
    public static final String version = VERSION;

    private Dispatcher() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            MainRun.main(args);
            return;
        }

        Options options = options();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLineParser parser = new DefaultParser();
            CommandLine cmd = parser.parse(options, args);
            if (cmd.hasOption("help")) {
                printHelp(formatter, options);
                return;
            }
            if (cmd.hasOption("version")) {
                System.out.println("insilicoPCR " + VERSION);
                return;
            }
            CommandMain.from(CliConfig.from(cmd)).run();
        } catch (ParseException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printHelp(formatter, options);
            System.exit(2);
        } catch (RuntimeException e) {
            // A failure the user can act on is its message; anything else is a bug and
            // keeps the stack trace a report of it needs.
            if (RunFailure.isForTheUser(e)) {
                RunFailure.lines(e).forEach(System.err::println);
            } else {
                e.printStackTrace(System.err);
            }
            System.exit(1);
        }
    }

    /** Package-private for unit tests. */
    static Options options() {
        Options options = new Options();
        options.addOption(Option.builder("i").longOpt("input").hasArg().argName("path")
                .desc("Input file/directory containing .fasta/.fastq sequence(s)").build());
        options.addOption(Option.builder("o").longOpt("output").hasArg().argName("dir")
                .desc("Directory for output files").build());
        options.addOption(Option.builder("p").longOpt("primers").hasArg().argName("file")
                .desc("Primer FASTA file").build());
        options.addOption(Option.builder("t").longOpt("threads").hasArg().argName("n")
                .desc("Number of worker threads; default: available processors").build());
        options.addOption(Option.builder("m").longOpt("mismatches").hasArg().argName("n")
                .desc("Allowed mismatches+indels per primer (3' bases that do not pair with the template count); default: 0").build());
        options.addOption(Option.builder("e").longOpt("evalue").hasArg().argName("value")
                .desc("blastn e-value; deliberately permissive, since primer hits are filtered by the mismatch and 3'/5' rules instead, and lowering it (e.g. to 1e-5) drops valid hits; default: 1e5").build());
        options.addOption(Option.builder("l").longOpt("max-length").hasArg().argName("bp")
                .desc("Maximum amplicon length in bp, 0 = unlimited; default: 5000").build());
        options.addOption(Option.builder().longOpt("min-length").hasArg().argName("bp")
                .desc("Minimum amplicon length in bp; default: 0").build());
        options.addOption(Option.builder().longOpt("3p-window").hasArg().argName("n")
                .desc("Bases at the primer 3' end checked with the weighted 3' penalty; default: 5").build());
        options.addOption(Option.builder().longOpt("3p-penalty").hasArg().argName("score")
                .desc("Maximum weighted 3'-end penalty (mismatch: 3 at terminus, 2 at -1/-2, 1 beyond;"
                        + " +1 if a G/C pair is broken near the terminus; +1 for indels); default: 0").build());
        options.addOption(Option.builder().longOpt("probe-mismatches").hasArg().argName("n")
                .desc("Allowed mismatches+indels per hydrolysis probe; probes bind hotter than primers"
                        + " and tolerate roughly one more mismatch; default: same as -m").build());
        options.addOption(Option.builder().longOpt("5p-truncation").hasArg().argName("n")
                .desc("Maximum primer bases allowed to be unaligned at the harmless 5' end"
                        + " (one 5'-proximal mismatch makes BLAST trim the whole tail); default: 5").build());
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
                        + " baited reads, to find products longer than a read or merged pair. One of: unreported"
                        + " (assays found in no contig and in no read), target (those, and assays that declare"
                        + " size= and show no product of that size yet), off (none); default: "
                        + ReadExtensionCaller.Mode.DEFAULT).build());
        options.addOption(Option.builder().longOpt("input-check").hasArg().argName("full|quick")
                .desc("How the input files are checked before the run. full: every read of every FASTQ file"
                        + " is read, and the two files of a pair must hold the same number of reads. quick: the"
                        + " first read only, faster on large files but blind to a file cut short. A sample with"
                        + " a faulty file is left out with a warning and the others are run; default: full").build());
        options.addOption(Option.builder("g").longOpt("gel").desc("Also write the synthetic gel image as PNG"
                + " (requires a graphical environment)").build());
        options.addOption(Option.builder("h").longOpt("help").desc("Print help and usage").build());
        options.addOption(Option.builder("v").longOpt("version").desc("Print version").build());
        return options;
    }

    private static void printHelp(HelpFormatter formatter, Options options) {
        formatter.printHelp("java -jar insilicoPCR.jar -i <input> -o <output> -p <primers> [-t n] [-m n] [-e value] [-l bp]", options);
    }
}
