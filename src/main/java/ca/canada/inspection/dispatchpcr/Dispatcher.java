package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.commandpcr.CommandMain;
import ca.canada.inspection.insilicopcr.MainRun;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public final class Dispatcher {

    public static final String VERSION = "0.7.0";

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
            e.printStackTrace(System.err);
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
                .desc("Allowed mismatches+indels per primer (unaligned 3' bases count); default: 0").build());
        options.addOption(Option.builder("e").longOpt("evalue").hasArg().argName("value")
                .desc("blastn e-value; default: 1e5").build());
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
