package ca.canada.inspection.dispatchpcr;

import java.nio.file.Path;

import org.apache.commons.cli.*;

import ca.canada.inspection.commandpcr.CommandMain;
import ca.canada.inspection.insilicopcr.MainRun;

public class Dispatcher {

	public static final String version = "0.5";

	public static void main(String[] args) {

		Options options = new Options();

		Option input = new Option("i", "input", true, "The input file/directory containing the .fasta or .fastq sequence(s)");
		input.setRequired(false);
		options.addOption(input);

		Option output = new Option("o", "output", true, "The directory to contain the output");
		output.setRequired(false);
		options.addOption(output);

		Option primerInput = new Option("p", "primers", true, "The custom primer file containing the putative PCR primers");
		primerInput.setRequired(false);
		options.addOption(primerInput);

		Option numThreads = new Option("t", "threads", true, "The number of threads to use. Default is maximum number of processors available.");
		numThreads.setRequired(false);
		options.addOption(numThreads);

		Option numMismatches = new Option("m", "mismatches", true, "The number of mismatches permitted. Default is 0.");
		numMismatches.setRequired(false);
		options.addOption(numMismatches);

		Option numEvalue = new Option("e", "evalue", true, "Evalue for blastn. Default is 1e5.");
		numMismatches.setRequired(false);
		options.addOption(numEvalue);

		Option help = new Option("h", "help", true, "Print help message and usage");
		help.setRequired(false);
		options.addOption(help);

		CommandLineParser parser = new DefaultParser();
		HelpFormatter formatter = new HelpFormatter();
		CommandLine cmd = null;

		try {
			cmd = parser.parse(options, args);
		}catch(ParseException e) {
			System.out.println(e.getMessage());
			formatter.printHelp("java commandpcr/CommandMain -i () -o () -p () [-t ()] [-m ()]\n"
					+ "\tCan be used either in GUI or commandline formats"
					+ "\t- GUI can be obtained by running without arguments"
					+ "\t- commandline is used by providing at least -i -o and -p arguments", options);

			System.exit(-1);
		}

		if(args.length > 0) {
			try{
				Path inputFile = Path.of(cmd.getOptionValue("input"));
				Path outDir = Path.of(cmd.getOptionValue("output"));
				Path primerFile = Path.of(cmd.getOptionValue("primers"));
				int threads = Runtime.getRuntime().availableProcessors();
				int mismatches = 0;
				double evalue = Double.parseDouble("1e5");
				if(cmd.getOptionValue("threads") != null) {
					threads = Integer.parseInt(cmd.getOptionValue("threads"));
				}
				if(cmd.getOptionValue("mismatches") != null) {
					mismatches = Integer.parseInt(cmd.getOptionValue("mismatches"));
				}
				if(cmd.getOptionValue("evalue") != null) {
					evalue = Double.parseDouble(cmd.getOptionValue("evalue"));
				}

				CommandMain main = new CommandMain(inputFile, outDir, primerFile, threads, mismatches, evalue);
				main.run();
			}catch(NullPointerException e) {
				e.printStackTrace();
				System.out.println("If using the program with arguments from commandline or terminal, you must provide at least i, o, and p arguments");
				formatter.printHelp("java commandpcr/CommandMain -i () -o () -p () [-t ()] [-m ()] [-e ()]\n"
						+ "\tCan be used either in GUI or commandline formats"
						+ "\t- GUI can be obtained by running without arguments"
						+ "\t- commandline is used by providing at least -i -o and -p arguments", options);
				System.exit(-1);
			}
		}
		else {
			MainRun.main(args);
		}
	}
}
