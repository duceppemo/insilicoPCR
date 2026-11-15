package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.TadpoleOptions;
import ca.canada.inspection.util.InputCheck;
import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
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

        // Only what is given is set: the defaults are the builders' own, the application's too.
        AmpliconOptions.Builder amplicon = AmpliconOptions.builder();
        ifGiven(cmd, "mismatches", amplicon::mismatches);
        ifGiven(cmd, "probe-mismatches", amplicon::probeMismatches);
        ifGiven(cmd, "3p-window", amplicon::threePrimeWindow);
        ifGiven(cmd, "3p-penalty", amplicon::threePrimePenalty);
        ifGiven(cmd, "5p-truncation", amplicon::fivePrimeTruncation);
        ifGiven(cmd, "min-length", amplicon::minLength);
        ifGiven(cmd, "max-length", amplicon::maxLength);

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
            config.evalue(parseDouble(cmd, "evalue"));
        }
        if (cmd.hasOption("read-min-fraction")) {
            config.readMinFraction(parseFraction(cmd, "read-min-fraction"));
        }
        if (cmd.hasOption("read-extension")) {
            config.readExtension(readExtension(cmd.getOptionValue("read-extension")));
        }
        return config.build();
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
            return new TadpoleOptions(
                    parseNonNegative(cmd, "tadpole-k", TadpoleOptions.DEFAULT_K),
                    parseNonNegative(cmd, "tadpole-min-seed", TadpoleOptions.DEFAULT_MIN_COUNT_SEED),
                    parseNonNegative(cmd, "tadpole-min-cov", TadpoleOptions.DEFAULT_MIN_COVERAGE),
                    parseNonNegative(cmd, "tadpole-min-contig", TadpoleOptions.DEFAULT_MIN_CONTIG)
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
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static int parseInt(CommandLine cmd, String option) {
        String value = cmd.getOptionValue(option);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be an integer, got: " + value);
        }
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
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + option + " must be an integer, got: " + value);
        }
        if (parsed < 0) {
            throw new IllegalArgumentException("--" + option + " must be >= 0, got: " + value);
        }
        return parsed;
    }
}
