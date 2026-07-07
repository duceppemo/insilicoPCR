package ca.canada.inspection.pipeline;

import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;

import java.nio.file.Path;

public record PcrRunConfig(
        Path inputFile,
        Path outDir,
        Path primerFile,
        int threads,
        int mismatches,
        double evalue,
        TextArea outputField,
        ProgressBar blastProgress
) {}
