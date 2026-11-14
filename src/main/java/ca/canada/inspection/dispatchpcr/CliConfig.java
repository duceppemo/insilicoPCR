package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.TadpoleOptions;
import ca.canada.inspection.util.InputCheck;
import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

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

        int mismatches = parseNonNegative(cmd, "mismatches", 0);
        AmpliconOptions options = new AmpliconOptions(
                mismatches,
                parseNonNegative(cmd, "probe-mismatches", mismatches),
                parseNonNegative(cmd, "3p-window", AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW),
                parseNonNegative(cmd, "3p-penalty", AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY),
                parseNonNegative(cmd, "5p-truncation", AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION),
                parseNonNegative(cmd, "min-length", AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH),
                parseNonNegative(cmd, "max-length", AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH)
        );

        PipelineConfig.Builder config = PipelineConfig.builder(input, output, primers)
                .ampliconOptions(options)
                .gel(cmd.hasOption("gel"))
                .tadpole(tadpoleOptions(cmd))
                .inputCheck(InputCheck.Depth.parse(cmd.getOptionValue("input-check", "full")));
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
