package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the input-file list back from a run's QAlog.txt (used to reopen a previous run). */
public final class QaLogInputs {
    static final String INPUTS_MARKER = "Input File(s) :";
    /** Lines written after the input list since 0.10.0; logs from 0.10.0 have no blank line before them. */
    private static final String FASTQ_SECTION_PREFIX = "FASTQ mode";

    private QaLogInputs() {
    }

    /**
     * @return the input paths listed after {@code Input File(s) :}, up to the first blank
     *         line or FASTQ-mode line, in file order
     */
    public static List<String> read(Path qaLog) {
        List<String> inputs = new ArrayList<>();
        boolean readingInputs = false;
        try {
            for (String line : Files.readAllLines(qaLog, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!readingInputs) {
                    readingInputs = trimmed.equals(INPUTS_MARKER);
                    continue;
                }
                if (trimmed.isEmpty() || trimmed.startsWith(FASTQ_SECTION_PREFIX)) {
                    break;
                }
                inputs.add(trimmed);
            }
        } catch (IOException e) {
            throw new PipelineException("Unable to read QAlog input list: " + qaLog, e);
        }
        return inputs;
    }
}
