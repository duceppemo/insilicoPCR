package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.TmGate;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.TadpoleOptions;
import ca.canada.inspection.util.InputCheck;
import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Reads the run a command line asks for. The defaults and the checks of the settings are
 * {@link PipelineConfig}'s ({@link PipelineConfig#builder}); this class reads the options and
 * checks what only the command line can get wrong: a missing option, a value that is not a
 * number, a path that does not exist.
 */
public final class CliConfig {
    private CliConfig() {
    }

    /** The memory option; {@link #MEMORY_BEFORE_1_0} is the same, under its old name (it sizes BLAST too, since 0.15.3). */
    static final String MEMORY = "memory";
    static final String MEMORY_BEFORE_1_0 = "bbtools-memory";

    /** @throws IllegalArgumentException for a wrong command line, with a message saying what is wrong */
    public static PipelineConfig from(CommandLine cmd) {
        Path input = requiredPath(cmd, "input");
        Path output = requiredPath(cmd, "output");
        Path primers = requiredPath(cmd, "primers");

        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input);
        }
        if (!Files.isRegularFile(primers)) {
            throw new IllegalArgumentException("Primer file does not exist or is not a regular file: " + primers);
        }
        if (Files.exists(output) && !Files.isDirectory(output)) {
            // A command line that cannot be used (exit 2), as a missing input is: it exited 1, as a failed run.
            throw new IllegalArgumentException("The output folder is a file: " + output
                    + ". Choose a folder, or a name that is not taken.");
        }

        // Only what is given is set: the defaults are the builders' own (TadpoleOptions' for Tadpole), the application's too.
        AmpliconOptions.Builder amplicon = AmpliconOptions.builder();
        ifGiven(cmd, "3p-window", amplicon::threePrimeWindow);
        ifGiven(cmd, "3p-penalty", amplicon::threePrimePenalty);
        ifGiven(cmd, "min-length", amplicon::minLength);
        ifGiven(cmd, "max-length", amplicon::maxLength);
        amplicon.tmGate(tmGate(cmd));

        PipelineConfig.Builder config = PipelineConfig.builder(input, output, primers)
                .ampliconOptions(amplicon.build())
                .gel(cmd.hasOption("gel"))
                .tadpole(tadpoleOptions(cmd));
        if (cmd.hasOption("input-check")) {
            config.inputCheck(InputCheck.Depth.parse(cmd.getOptionValue("input-check")));
        }
        if (cmd.hasOption("threads")) {
            config.threads(parseInt(cmd, "threads"));
        }
        if (cmd.hasOption("evalue")) {
            double evalue = parseDouble(cmd, "evalue");
            if (PipelineConfig.tooSmallForANumber(cmd.getOptionValue("evalue"), evalue)) {
                // 1e-400 reads as 0: the message said "got: 0.0", not what was given.
                throw new IllegalArgumentException("--evalue " + PipelineConfig.tooSmallMessage(cmd.getOptionValue("evalue")));
            }
            if (!PipelineConfig.validEvalue(evalue)) {
                // As given, and named as the option: "evalue must be ..., got: -1.0".
                throw new IllegalArgumentException("--evalue must be a number greater than 0, e.g. "
                        + PipelineConfig.DEFAULT_EVALUE_TEXT + ", got: " + cmd.getOptionValue("evalue"));
            }
            config.evalue(evalue);
        }
        if (cmd.hasOption("read-min-fraction")) {
            config.readMinFraction(parseFraction(cmd, "read-min-fraction"));
        }
        String memory = cmd.hasOption(MEMORY) ? MEMORY : cmd.hasOption(MEMORY_BEFORE_1_0) ? MEMORY_BEFORE_1_0 : null;
        if (cmd.hasOption(MEMORY) && cmd.hasOption(MEMORY_BEFORE_1_0)) {
            throw new IllegalArgumentException("--" + MEMORY + " and --" + MEMORY_BEFORE_1_0 + " are one option: give it"
                    + " once");
        }
        if (memory != null) {
            int gib = parseInt(cmd, memory);
            if (gib < 1) {
                throw new IllegalArgumentException("--" + memory + " must be at least 1 GB; leave it out for automatic");
            }
            config.bbtoolsMemory(gib);
        }
        if (cmd.hasOption("read-extension")) {
            config.readExtension(readExtension(cmd.getOptionValue("read-extension")));
        }
        return config.build();
    }

    /** The Tm rule the command line asks for: every setting not given at its default. */
    private static TmGate tmGate(CommandLine cmd) {
        return new TmGate(doubleOr(cmd, "anneal-temp", TmGate.DEFAULT_ANNEAL_TEMP),
                doubleOr(cmd, "tm-margin", TmGate.DEFAULT_MARGIN),
                doubleOr(cmd, "na", TmGate.DEFAULT_MONOVALENT_MM),
                doubleOr(cmd, "mg", TmGate.DEFAULT_DIVALENT_MM),
                doubleOr(cmd, "dntp", TmGate.DEFAULT_DNTP_MM),
                doubleOr(cmd, "oligo-conc", TmGate.DEFAULT_OLIGO_NM));
    }

    /** The options of 1.x that 2.0 removed: primer sites are judged by Tm, not counted mismatches. */
    static final List<String> REMOVED_IN_2_0 = List.of("-m", "--mismatches", "--probe-mismatches", "--5p-truncation");

    /**
     * What to say of {@code error} when it is an option {@link #REMOVED_IN_2_0}: "Unrecognized option" told a 1.x
     * command line nothing of what to give instead.
     */
    static java.util.Optional<String> removedOption(Exception error) {
        if (error instanceof org.apache.commons.cli.UnrecognizedOptionException unknown) {
            String option = unknown.getOption() == null ? "" : unknown.getOption().split("=", 2)[0];
            if (REMOVED_IN_2_0.contains(option)) {
                return java.util.Optional.of(option + " was removed in 2.0: a primer or probe site is accepted when its"
                        + " Tm reaches --anneal-temp (default " + number(TmGate.DEFAULT_ANNEAL_TEMP) + " °C) less"
                        + " --tm-margin (default " + number(TmGate.DEFAULT_MARGIN) + " °C), whatever its mismatches."
                        + " Leave " + option + " out; lower --anneal-temp or raise --tm-margin to accept weaker sites");
            }
        }
        return java.util.Optional.empty();
    }

    private static String number(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static double doubleOr(CommandLine cmd, String option, double defaultValue) {
        return cmd.hasOption(option) ? parseDouble(cmd, option) : defaultValue;
    }

    private static void ifGiven(CommandLine cmd, String option, IntConsumer set) {
        if (cmd.hasOption(option)) {
            set.accept(parseNonNegative(cmd, option, 0));
        }
    }

    private static ReadExtensionCaller.Mode readExtension(String value) {
        try {
            return ReadExtensionCaller.Mode.parse(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--read-extension must be off, unreported or target, got: " + value);
        }
    }

    private static double parseFraction(CommandLine cmd, String option) {
        String value = cmd.getOptionValue(option);
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be a number between 0 and 1, got: " + value);
        }
        if (!(parsed >= 0.0 && parsed <= 1.0)) {
            throw new IllegalArgumentException("--" + option + " must be between 0 and 1, got: " + value);
        }
        return parsed;
    }

    private static TadpoleOptions tadpoleOptions(CommandLine cmd) {
        try {
            // Their bounds are TadpoleOptions' (the window's too): checked here as well, -1 was told ">= 0" and 0
            // ">= 1".
            return new TadpoleOptions(
                    integerOr(cmd, "tadpole-k", TadpoleOptions.DEFAULT_K),
                    integerOr(cmd, "tadpole-min-seed", TadpoleOptions.DEFAULT_MIN_COUNT_SEED),
                    integerOr(cmd, "tadpole-min-cov", TadpoleOptions.DEFAULT_MIN_COVERAGE),
                    integerOr(cmd, "tadpole-min-contig", TadpoleOptions.DEFAULT_MIN_CONTIG)
            );
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Tadpole setting: " + e.getMessage(), e);
        }
    }

    private static Path requiredPath(CommandLine cmd, String option) {
        String value = cmd.getOptionValue(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: --" + option);
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            throw new IllegalArgumentException(notAPath(option, value), e);
        }
    }

    /**
     * Why {@code value}, given to {@code --option}, is not a path: on Windows, a quote kept by a backslash
     * before it, or a letter the Java runtime received as {@code ?}; otherwise, that it is not one.
     */
    static String notAPath(String option, String value) {
        if (value.indexOf('"') >= 0) {
            return "--" + option + " " + value + ": a quote is in the path. On Windows, a backslash just before a"
                    + " closing quote keeps the quote, and what follows, in the path (\"C:\\my data\\\" -p ...):"
                    + " leave out the last backslash (\"C:\\my data\")";
        }
        if (value.indexOf('?') >= 0) {
            return "--" + option + " " + value + ": not a path. On Windows, a letter outside the computer's code page"
                    + " (such as \u6570 or \u0394) reaches the Java runtime as ?: use a folder and file whose names have"
                    + " none, or a path that does not name them (a relative path from inside such a folder)";
        }
        return "--" + option + " " + value + ": not a valid path";
    }

    /**
     * @throws IllegalArgumentException on Windows, when a path given on the command line holds a quote,
     *                                  which no path there can ({@link #notAPath})
     */
    static void checkQuotes(CommandLine cmd) {
        if (!ca.canada.inspection.pipeline.AppPaths.isWindows()) {
            return;
        }
        for (String option : List.of("input", "output", "primers")) {
            String value = cmd.getOptionValue(option);
            if (value != null && value.indexOf('"') >= 0) {
                throw new IllegalArgumentException(notAPath(option, value));
            }
        }
    }

    /**
     * @throws IllegalArgumentException when an option is given more than once: -t 2 -t 3 ran with 2, the first,
     *                                  without a word (a command line built from a script's defaults and a user's
     *                                  own setting is the usual way to get there)
     */
    static void checkGivenOnce(CommandLine cmd) {
        java.util.Map<String, Integer> times = new java.util.TreeMap<>();
        for (org.apache.commons.cli.Option option : cmd.getOptions()) {
            times.merge(option.getLongOpt() != null ? option.getLongOpt() : option.getOpt(), 1, Integer::sum);
        }
        times.forEach((option, count) -> {
            if (count > 1) {
                throw new IllegalArgumentException("--" + option + " is given " + count + " times: give it once");
            }
        });
    }

    private static int parseInt(CommandLine cmd, String option) {
        return integer(option, cmd.getOptionValue(option));
    }

    /** {@code value} of {@code --option} as an integer; one too large for it is said to be so, not "not an integer". */
    static int integer(String option, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            if (value != null && value.matches("[+-]?\\d+")) {
                boolean negative = value.startsWith("-");
                throw new IllegalArgumentException("--" + option + (negative ? " is too small" : " is too large")
                        + ", got: " + value + (negative ? " (at least " + Integer.MIN_VALUE : " (at most "
                        + Integer.MAX_VALUE) + ")");
            }
            throw new IllegalArgumentException("--" + option + " must be an integer, got: " + value);
        }
    }

    private static int integerOr(CommandLine cmd, String option, int defaultValue) {
        return cmd.hasOption(option) ? parseInt(cmd, option) : defaultValue;
    }

    private static double parseDouble(CommandLine cmd, String option) {
        String value = cmd.getOptionValue(option);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be a number, got: " + value);
        }
    }

    private static int parseNonNegative(CommandLine cmd, String option, int defaultValue) {
        String value = cmd.getOptionValue(option);
        if (value == null) {
            return defaultValue;
        }
        int parsed = integer(option, value);
        if (parsed < 0) {
            throw new IllegalArgumentException("--" + option + " must be >= 0, got: " + value);
        }
        return parsed;
    }
}
