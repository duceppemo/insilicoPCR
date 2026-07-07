package ca.canada.inspection.pipeline;

import ca.canada.inspection.commandpcr.CommandMethods;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PrimerService {
    public Map<String, String> loadAndPrepare(PipelineConfig config, PipelineDirectories directories) {
        HashMap<String, String> primers = CommandMethods.parseFastaToDictionary(config.primers());
        CommandMethods.processPrimers(primers, directories.output(), java.io.File.separator);
        return new LinkedHashMap<>(primers);
    }
}
