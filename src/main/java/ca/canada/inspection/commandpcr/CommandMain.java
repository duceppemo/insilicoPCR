package ca.canada.inspection.commandpcr;

import ca.canada.inspection.dispatchpcr.CliConfig;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pipeline.InSilicoPcrPipeline;
import ca.canada.inspection.pipeline.PipelineConfig;

import java.nio.file.Path;

/** Backwards-compatible command entry point that delegates to the modern pipeline. */
public final class CommandMain {

	private final PipelineConfig config;

	public CommandMain(Path inputFile, Path outDir, Path primerFile, int threads, int mismatches, double evalue) {
		this(new PipelineConfig(inputFile, outDir, primerFile, threads, evalue, AmpliconOptions.withMismatches(mismatches), false));
	}

	private CommandMain(PipelineConfig config) {
		this.config = config;
	}

	public static CommandMain from(CliConfig config) {
		return new CommandMain(new PipelineConfig(
				config.input(),
				config.output(),
				config.primers(),
				config.threads(),
				config.evalue(),
				config.ampliconOptions(),
				config.gel(),
				config.tadpole(),
				config.readMinFraction(),
				config.inputCheck()
		));
	}

	public void run() {
		new InSilicoPcrPipeline(config).run();
	}
}
