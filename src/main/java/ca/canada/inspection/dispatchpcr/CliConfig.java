package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable, validated command-line configuration. */
public record CliConfig(
        Path input,
        Path output,
        Path primers,
        int threads,
        double evalue,
        AmpliconOptions ampliconOptions
) {
    public static final double DEFAULT_EVALUE = 1e5;

    public CliConfig {
        input = input.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        primers = primers.toAbsolutePath().normalize();
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (!(evalue > 0.0)) {
            throw new IllegalArgumentException("evalue must be > 0");
        }
    }

    public static CliConfig from(CommandLine cmd) {
        Path input = requiredPath(cmd, "input");
        Path output = requiredPath(cmd, "output");
        Path primers = requiredPath(cmd, "primers");

        if (!Files.exists(input)) {
            throw new IllegalArgumentException("Input does not exist: " + input);
        }
        if (!Files.isRegularFile(primers)) {
            throw new IllegalArgumentException("Primer file does not exist or is not a regular file: " + primers);
        }

        AmpliconOptions options = new AmpliconOptions(
                parseNonNegative(cmd, "mismatches", 0),
                parseNonNegative(cmd, "3p-window", AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW),
                parseNonNegative(cmd, "3p-penalty", AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY),
                AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION,
                parseNonNegative(cmd, "min-length", AmpliconOptions.DEFAULT_MIN_AMPLICON_LENGTH),
                parseNonNegative(cmd, "max-length", AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH)
        );

        return new CliConfig(
                input,
                output,
                primers,
                parseThreads(cmd.getOptionValue("threads")),
                parseEvalue(cmd.getOptionValue("evalue")),
                options
        );
    }

    private static Path requiredPath(CommandLine cmd, String option) {
        String value = cmd.getOptionValue(option);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required option: --" + option);
        }
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static int parseThreads(String value) {
        int threads = value == null ? Runtime.getRuntime().availableProcessors() : Integer.parseInt(value);
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        return threads;
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

    private static double parseEvalue(String value) {
        if (value == null) {
            return DEFAULT_EVALUE;
        }
        double evalue = Double.parseDouble(value);
        if (!(evalue > 0.0)) {
            throw new IllegalArgumentException("evalue must be > 0");
        }
        return evalue;
    }
}
