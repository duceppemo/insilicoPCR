package ca.canada.inspection.dispatchpcr;

import org.apache.commons.cli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;

/** Immutable, validated command-line configuration. */
public record CliConfig(
        Path input,
        Path output,
        Path primers,
        int threads,
        int mismatches,
        double evalue
) {
    public static final double DEFAULT_EVALUE = 1e5;

    public CliConfig {
        input = input.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        primers = primers.toAbsolutePath().normalize();
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1");
        }
        if (mismatches < 0) {
            throw new IllegalArgumentException("mismatches must be >= 0");
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

        return new CliConfig(
                input,
                output,
                primers,
                parseThreads(cmd.getOptionValue("threads")),
                parseMismatches(cmd.getOptionValue("mismatches")),
                parseEvalue(cmd.getOptionValue("evalue"))
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

    private static int parseMismatches(String value) {
        if (value == null) {
            return 0;
        }
        int mismatches = Integer.parseInt(value);
        if (mismatches < 0) {
            throw new IllegalArgumentException("mismatches must be >= 0");
        }
        return mismatches;
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
