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

import java.nio.file.Files;
import java.nio.file.Path;

public final class Dispatcher {

	public static final String version = "0.6.0-SNAPSHOT";

	private Dispatcher() {
	}

	static void main(String[] args) {
		if (args.length == 0) {
			MainRun.main(args);
			return;
		}

		Options options = options();
		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();

		try {
			CommandLine cmd = parser.parse(options, args);
			if (cmd.hasOption("help")) {
				printHelp(formatter, options);
				return;
			}
			CliConfig config = CliConfig.from(cmd);
			new CommandMain(config.input(), config.output(), config.primers(), config.threads(), config.mismatches(), config.evalue()).run();
		} catch (ParseException | IllegalArgumentException e) {
			System.err.println(e.getMessage());
			printHelp(formatter, options);
			System.exit(2);
		} catch (RuntimeException e) {
			e.printStackTrace(System.err);
			System.exit(1);
		}
	}

	private static Options options() {
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
				.desc("Allowed primer mismatches; default: 0").build());
		options.addOption(Option.builder("e").longOpt("evalue").hasArg().argName("value")
				.desc("blastn e-value; default: 1e5").build());
		options.addOption(Option.builder("h").longOpt("help").desc("Print help and usage").build());
		return options;
	}

	private static void printHelp(HelpFormatter formatter, Options options) {
		formatter.printHelp("java -jar insilicoPCR.jar -i <input> -o <output> -p <primers> [-t n] [-m n] [-e value]", options);
	}

	private record CliConfig(Path input, Path output, Path primers, int threads, int mismatches, double evalue) {
		static CliConfig from(CommandLine cmd) {
			Path input = requiredPath(cmd, "input");
			Path output = requiredPath(cmd, "output");
			Path primers = requiredPath(cmd, "primers");

			if (!Files.exists(input)) {
				throw new IllegalArgumentException("Input does not exist: " + input);
			}
			if (!Files.exists(primers)) {
				throw new IllegalArgumentException("Primer file does not exist: " + primers);
			}

			int threads = positiveInt(cmd.getOptionValue("threads"), Runtime.getRuntime().availableProcessors(), "threads");
			int mismatches = nonNegativeInt(cmd.getOptionValue("mismatches"), 0, "mismatches");
			double evalue = positiveDouble(cmd.getOptionValue("evalue"), 1e5, "evalue");
			return new CliConfig(input, output, primers, threads, mismatches, evalue);
		}

		private static Path requiredPath(CommandLine cmd, String option) {
			String value = cmd.getOptionValue(option);
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Missing required option: --" + option);
			}
			return Path.of(value).toAbsolutePath().normalize();
		}

		private static int positiveInt(String value, int defaultValue, String name) {
			int parsed = value == null ? defaultValue : Integer.parseInt(value);
			if (parsed < 1) {
				throw new IllegalArgumentException(name + " must be >= 1");
			}
			return parsed;
		}

		private static int nonNegativeInt(String value, int defaultValue, String name) {
			int parsed = value == null ? defaultValue : Integer.parseInt(value);
			if (parsed < 0) {
				throw new IllegalArgumentException(name + " must be >= 0");
			}
			return parsed;
		}

		private static double positiveDouble(String value, double defaultValue, String name) {
			double parsed = value == null ? defaultValue : Double.parseDouble(value);
			if (!(parsed > 0.0)) {
				throw new IllegalArgumentException(name + " must be > 0");
			}
			return parsed;
		}
	}
}
