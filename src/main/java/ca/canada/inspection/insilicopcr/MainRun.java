package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.util.Version;
import ca.canada.inspection.gel.PaginatedGelViewer;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.report.HtmlReport;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.insilicopcr.ui.PathFieldBinder;
import ca.canada.inspection.pipeline.ExternalProcessTracker;
import ca.canada.inspection.pipeline.InputValidator;
import ca.canada.inspection.pipeline.LogFiles;
import ca.canada.inspection.pipeline.PcrPipelineTask;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.QaLogInputs;
import ca.canada.inspection.pipeline.RunFailure;
import ca.canada.inspection.pipeline.RuntimeDependencies;
import ca.canada.inspection.util.InputCheck;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainRun extends Application {

    private static final String PROMPT_STYLE = "-fx-font-family: Arial;";

    private Path inputFile;
    private Path outDir;
    private Path primerFile;
    private TextArea outputField;
    private ProgressBar mainProgress;
    private ProgressBar blastProgress;
    private Button gelButton;
    private Button htmlButton;
    private Button runButton;
    private Scene scene;
    private Stage primaryStage;

    private final ExternalProcessTracker processTracker = new ExternalProcessTracker();
    private final AtomicBoolean currentlyRunning = new AtomicBoolean(false);
    private PcrPipelineTask runningTask;
    private FastqSettings fastqSettings = FastqSettings.DEFAULTS;
    /** Paths of the last run started; the fields may have been edited since. */
    private Path lastRunInput;
    private Path lastRunPrimer;
    private Path lastRunOutDir;
    private AmpliconSettings ampliconSettings = AmpliconSettings.DEFAULTS;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        var pane = buildGrid();

        var inputField = new TextField();
        var primerField = new TextField();
        var outputDirField = new TextField();
        outputField = new TextArea();
        var alertText = new Text();

        addInputControls(primaryStage, pane, inputField);
        addPrimerControls(primaryStage, pane, primerField);
        addOutputControls(primaryStage, pane, outputDirField);
        var threadField = addThreadControls(pane);
        var mismatchField = addMismatchControls(pane);
        var evalueField = addEvalueControls(pane);
        addSettingsButtons(primaryStage, pane);
        addOutputLog(pane);
        addBottomControls(pane, alertText, threadField, mismatchField, evalueField);

        scene = new Scene(pane, 800, 500);
        primaryStage.setScene(scene);
        primaryStage.setTitle("InSilico PCR " + Version.VERSION);

        // A build without the icon still opens its window, with the default icon.
        try (var icon = MainRun.class.getResourceAsStream("/icons/insilicoPCR-icon.png")) {
            if (icon != null) {
                primaryStage.getIcons().add(new Image(icon));
            }
        } catch (java.io.IOException ignored) {
            // Closing a resource stream does not fail in practice; the icon is cosmetic anyway.
        }

        primaryStage.setOnCloseRequest(event -> {
            if (!confirmClose()) {
                event.consume();
            }
        });
        primaryStage.show();
    }

    private GridPane buildGrid() {
        var pane = new GridPane();
        var column = new ColumnConstraints();
        column.setPercentWidth(2);
        pane.getColumnConstraints().addAll(Collections.nCopies(50, column));

        var row = new RowConstraints();
        row.setPercentHeight(2);
        pane.getRowConstraints().addAll(Collections.nCopies(50, row));
        return pane;
    }

    private void addInputControls(Stage stage, GridPane pane, TextField inputField) {
        addPrompt(pane, "Input fasta/fastq file or directory containing fasta/fastq files", 2, 2, 20, 2);
        inputField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        inputField.setEditable(true);
        pane.add(inputField, 2, 4, 28, 2);
        PathFieldBinder.bindDropTarget(inputField, path -> inputFile = path);
        PathFieldBinder.bindTypedPath(inputField, path -> inputFile = path);

        // Files and directories are told apart when the run starts; only the chooser
        // dialog has to be one or the other, so "Select" offers both.
        var selectFile = new MenuItem("File…");
        selectFile.setOnAction(event -> PathFieldBinder.chooseFile(stage, inputField, path -> inputFile = path));
        var selectFolder = new MenuItem("Folder…");
        selectFolder.setOnAction(event -> PathFieldBinder.chooseDirectory(stage, inputField, path -> inputFile = path));
        var browse = new MenuButton("Select", null, selectFile, selectFolder);
        browse.setTooltip(new Tooltip("Choose one sequence file, or a folder of FASTA/FASTQ files. "
                + "You can also drag a file or folder onto the field."));
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(browse, 31, 4, 5, 2);
    }

    private void addPrimerControls(Stage stage, GridPane pane, TextField primerField) {
        addPrompt(pane, "Input a primer reference file in fasta format", 2, 7, 20, 2);
        primerField.setTooltip(new Tooltip("Please refer to the custom_primer_guide.txt for instructions on how to create a valid primer file"));
        primerField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        primerField.setEditable(true);
        pane.add(primerField, 2, 9, 28, 2);
        PathFieldBinder.bindDropTarget(primerField, path -> primerFile = path);
        PathFieldBinder.bindTypedPath(primerField, path -> primerFile = path);

        var browse = new Button("Select");
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        browse.setOnAction(event -> PathFieldBinder.chooseFile(stage, primerField, path -> primerFile = path));
        pane.add(browse, 31, 9, 5, 2);
    }

    private void addOutputControls(Stage stage, GridPane pane, TextField outputDirField) {
        addPrompt(pane, "Output directory. Path MUST NOT contain spaces", 2, 12, 20, 2);
        outputDirField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        outputDirField.setEditable(true);
        pane.add(outputDirField, 2, 14, 28, 2);
        PathFieldBinder.bindDropTarget(outputDirField, path -> outDir = path);
        PathFieldBinder.bindTypedPath(outputDirField, path -> outDir = path);

        var browse = new Button("Select");
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        browse.setOnAction(event -> PathFieldBinder.chooseDirectory(stage, outputDirField, path -> outDir = path));
        pane.add(browse, 31, 14, 5, 2);
    }

    private ComboBox<Integer> addThreadControls(GridPane pane) {
        var prompt = promptText("Threads");
        prompt.setTextAlignment(TextAlignment.LEFT);
        var box = new HBox(10, prompt);
        box.setAlignment(Pos.TOP_LEFT);
        pane.add(box, 38, 5, 5, 1);

        var field = new ComboBox<Integer>();
        field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        for (var i = 1; i <= maxThreads; i++) {
            field.getItems().add(i);
        }
        field.getSelectionModel().selectLast();
        field.setVisibleRowCount(3);
        pane.add(field, 44, 4, 4, 3);
        return field;
    }

    private ComboBox<Integer> addMismatchControls(GridPane pane) {
        var prompt = promptText("Mismatches");
        prompt.setTextAlignment(TextAlignment.LEFT);
        var box = new HBox(10, prompt);
        box.setAlignment(Pos.TOP_LEFT);
        pane.add(box, 38, 10, 5, 1);

        var field = new ComboBox<Integer>();
        field.setTooltip(new Tooltip("Allowed mismatches + indels per primer. Bases at the primer 3' end that do not "
                + "pair with the template also count. Independently, the primer 3' end is checked separately "
                + "(see Amplicon settings)."));
        field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        field.getItems().addAll(0, 1, 2, 3);
        field.getSelectionModel().selectFirst();
        pane.add(field, 44, 9, 4, 3);
        return field;
    }

    private TextField addEvalueControls(GridPane pane) {
        var prompt = promptText("Evalue");
        prompt.setTextAlignment(TextAlignment.LEFT);
        var box = new HBox(10, prompt);
        box.setAlignment(Pos.TOP_LEFT);
        pane.add(box, 38, 15, 5, 1);

        var field = new TextField("1e5");
        var tip = new Tooltip("BLAST e-value. Deliberately permissive: primer hits are filtered by the mismatch and "
                + "3'/5'-end rules instead. A short primer's e-value grows with contig length, so a strict value "
                + "such as 1e-5 drops valid hits. Default 1e5 (-e on the command line).");
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        field.setTooltip(tip);
        field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        field.setEditable(true);
        pane.add(field, 44, 14, 4, 3);
        return field;
    }

    /** Amplicon and FASTQ settings buttons, side by side above the log under the parameter column. */
    private void addSettingsButtons(Stage stage, GridPane pane) {
        var amplicon = new Button(AmpliconSettingsDialog.buttonLabel(ampliconSettings));
        amplicon.setTooltip(new Tooltip(AmpliconSettingsDialog.summary(ampliconSettings)));
        amplicon.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        amplicon.setOnAction(event -> AmpliconSettingsDialog.show(stage, ampliconSettings).ifPresent(settings -> {
            ampliconSettings = settings;
            amplicon.setText(AmpliconSettingsDialog.buttonLabel(settings));
            amplicon.setTooltip(new Tooltip(AmpliconSettingsDialog.summary(settings)));
        }));

        var fastq = new Button(FastqSettingsDialog.buttonLabel(fastqSettings));
        fastq.setTooltip(new Tooltip(FastqSettingsDialog.summary(fastqSettings)));
        fastq.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        fastq.setOnAction(event -> FastqSettingsDialog.show(stage, fastqSettings).ifPresent(settings -> {
            fastqSettings = settings;
            fastq.setText(FastqSettingsDialog.buttonLabel(settings));
            fastq.setTooltip(new Tooltip(FastqSettingsDialog.summary(settings)));
        }));

        // Vertical margins keep the buttons off the Evalue field above and the log below.
        GridPane.setMargin(amplicon, new Insets(6, 3, 6, 0));
        GridPane.setMargin(fastq, new Insets(6, 0, 6, 3));
        pane.add(amplicon, 29, 17, 10, 4);
        pane.add(fastq, 39, 17, 9, 4);
    }

    private void addOutputLog(GridPane pane) {
        outputField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        outputField.setEditable(false);
        pane.add(outputField, 2, 21, 46, 20);
    }

    private void addBottomControls(GridPane pane,
                                   Text alertText,
                                   ComboBox<Integer> threadField,
                                   ComboBox<Integer> mismatchField,
                                   TextField evalueField) {
        alertText.setStyle("-fx-fill: red;");

        mainProgress = new ProgressBar();
        mainProgress.setMaxWidth(Double.MAX_VALUE);
        mainProgress.setVisible(false);
        mainProgress.setManaged(false);

        blastProgress = new ProgressBar();
        blastProgress.setMaxWidth(Double.MAX_VALUE);
        blastProgress.setVisible(false);
        blastProgress.setManaged(false);

        gelButton = new Button("View Gel Image");
        gelButton.setMaxWidth(Double.MAX_VALUE);
        gelButton.setDisable(true);
        gelButton.setOnAction(event -> displayGelImage());

        htmlButton = new Button("View HTML Report");
        htmlButton.setMaxWidth(Double.MAX_VALUE);
        htmlButton.setDisable(true);
        htmlButton.setOnAction(event -> viewHtmlReport());

        var previousRunButton = new Button("Open Previous Run");
        previousRunButton.setMaxWidth(Double.MAX_VALUE);
        previousRunButton.setOnAction(event -> openPreviousRun());

        runButton = new Button("Run");
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setOnAction(event -> startRun(alertText, threadField, mismatchField, evalueField));

        var reportButton = new Button("Open Report TSV");
        reportButton.setMaxWidth(Double.MAX_VALUE);
        reportButton.setOnAction(event -> openReportOnly());

        HBox buttons = new HBox(8, gelButton, htmlButton, previousRunButton, runButton, reportButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setPrefHeight(34);
        HBox.setHgrow(gelButton, Priority.ALWAYS);
        HBox.setHgrow(htmlButton, Priority.ALWAYS);
        HBox.setHgrow(previousRunButton, Priority.ALWAYS);
        HBox.setHgrow(runButton, Priority.ALWAYS);
        HBox.setHgrow(reportButton, Priority.ALWAYS);

        VBox bottomPanel = new VBox(5, alertText, mainProgress, buttons, blastProgress);
        bottomPanel.setAlignment(Pos.CENTER);
        bottomPanel.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(bottomPanel, 2, 41, 46, 8);
    }

    private void startRun(Text alertText,
                          ComboBox<Integer> threadField,
                          ComboBox<Integer> mismatchField,
                          TextField evalueField) {
        if (currentlyRunning.get()) {
            return; // the button is disabled while running; ignore a stray second trigger
        }
        var validationMessage = InputValidator.validate(inputFile, outDir, primerFile);
        if (!validationMessage.isBlank()) {
            alertText.setText(validationMessage);
            return;
        }

        // Validate every typed value BEFORE touching any UI state, so a bad entry
        // cannot leave the window stuck in the "running" state.
        double evalue;
        try {
            evalue = Double.parseDouble(evalueField.getText().trim());
            if (!(evalue > 0.0)) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            alertText.setText("E-value must be a number greater than 0, e.g. 1e5.");
            return;
        }

        // The Amplicon settings dialog only accepts valid values, so this cannot fail.
        AmpliconOptions ampliconOptions = ampliconSettings.toOptions(mismatchField.getSelectionModel().getSelectedItem());

        alertText.setText("");
        outputField.clear();
        currentlyRunning.set(true);
        runButton.setDisable(true);
        gelButton.setDisable(true);
        htmlButton.setDisable(true);
        showProgressBars(true);
        mainProgress.progressProperty().unbind();
        mainProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        blastProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        lastRunInput = inputFile;
        lastRunPrimer = primerFile;
        lastRunOutDir = outDir;
        // The window has its own gel viewer, and checks every read as the command line does
        // by default.
        var config = new PipelineConfig(
                inputFile,
                outDir,
                primerFile,
                threadField.getSelectionModel().getSelectedItem(),
                evalue,
                ampliconOptions,
                false,
                fastqSettings.tadpole(),
                fastqSettings.readMinFraction(),
                fastqSettings.readExtension(),
                InputCheck.Depth.FULL
        );

        runningTask = new PcrPipelineTask(config, outputField, blastProgress, processTracker);
        mainProgress.progressProperty().bind(runningTask.progressProperty());
        runningTask.setOnCancelled(event -> {
            currentlyRunning.set(false);
            runButton.setDisable(false);
            showProgressBars(false);
        });
        runningTask.setOnSucceeded(event -> {
            runButton.setDisable(false);
            currentlyRunning.set(false);
            gelButton.setDisable(false);
            htmlButton.setDisable(false);
            showProgressBars(false);
        });
        runningTask.setOnFailed(event -> {
            runButton.setDisable(false);
            currentlyRunning.set(false);
            gelButton.setDisable(true);
            htmlButton.setDisable(true);
            showProgressBars(false);

            Throwable error = runningTask.getException();
            if (error == null) {
                logMessage("Run failed");
                return;
            }

            // The console always gets the stack trace. The window gets the message of a
            // failure the user can act on, and the stack trace of anything else (a bug).
            error.printStackTrace();

            if (RunFailure.isForTheUser(error)) {
                logMessage("Run failed\n" + String.join("\n", RunFailure.lines(error)));
                return;
            }
            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            logMessage("Run failed:\n" + sw);
        });

        var thread = new Thread(runningTask, "insilico-pcr-run");
        thread.setDaemon(true);
        thread.start();
    }

    private void showProgressBars(boolean visible) {
        mainProgress.setVisible(visible);
        mainProgress.setManaged(visible);
        blastProgress.setVisible(visible);
        blastProgress.setManaged(visible);
    }

    private boolean confirmClose() {
        // Logs belong to the last run started, in the folder it used: the path fields
        // may since hold another, half-typed or mistyped path. No run, no logs.
        if (lastRunOutDir == null) {
            Platform.exit();
            return true;
        }

        if (currentlyRunning.get()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to exit? If the program is running, output may become corrupted",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(primaryStage);
            confirm.setTitle("Exit InSilico PCR");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
            if (runningTask != null) {
                // Cancel first so no stage starts a new process, then kill the running ones.
                runningTask.cancel(true);
                runningTask.shutdownNow();
            }
        }

        try {
            LogFiles.ensureQaLog(lastRunOutDir, lastRunInput, lastRunPrimer, RuntimeDependencies.discover());
            LogFiles.appendRunLog(lastRunOutDir, outputField.getText());
        } catch (RuntimeException e) {
            logMessage("Unable to write shutdown logs: " + e.getMessage());
        }

        Platform.exit();
        return true;
    }

    private void addPrompt(GridPane pane, String text, int column, int row, int colspan, int rowspan) {
        var box = new HBox(10, promptText(text));
        box.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(box, column, row, colspan, rowspan);
    }

    private static Text promptText(String text) {
        var prompt = new Text(text);
        prompt.setStyle(PROMPT_STYLE);
        return prompt;
    }

    private void displayGelImage() {
        try {
            PaginatedGelViewer.show(scene, latestConsolidatedReport(), List.of());
        } catch (Exception e) {
            showError("Unable to create gel image:\n" + e.getMessage());
        }
    }

    /** Opens the last run's HTML report in the system browser. */
    private void viewHtmlReport() {
        try {
            Path html = HtmlReport.pathFor(latestConsolidatedReport());
            if (!Files.isRegularFile(html)) {
                throw new IllegalStateException("HTML report not found: " + html);
            }
            getHostServices().showDocument(html.toUri().toString());
        } catch (Exception e) {
            showError("Unable to open the HTML report:\n" + e.getMessage());
        }
    }

    private void openPreviousRun() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Open Previous insilicoPCR Run Folder");
        if (outDir != null && Files.isDirectory(outDir)) {
            chooser.setInitialDirectory(outDir.toFile());
        }
        var selected = chooser.showDialog(primaryStage);
        if (selected == null) {
            return;
        }

        try {
            Path runDir = selected.toPath();
            Path report = latestConsolidatedReport(runDir);
            PaginatedGelViewer.show(scene, report, samplesFromQaLog(runDir.resolve("QAlog.txt")));
        } catch (Exception e) {
            showError("Unable to open previous run:\n" + e.getMessage());
        }
    }

    private void openReportOnly() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open Consolidated Report TSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("TSV report", "*.tsv"));
        if (outDir != null && Files.isDirectory(outDir)) {
            chooser.setInitialDirectory(outDir.toFile());
        }
        var selected = chooser.showOpenDialog(primaryStage);
        if (selected == null) {
            return;
        }

        try {
            PaginatedGelViewer.show(scene, selected.toPath(), List.of());
        } catch (Exception e) {
            showError("Unable to open report TSV:\n" + e.getMessage());
        }
    }

    /** The last run's report: the output field may have been edited since the run. */
    private Path latestConsolidatedReport() {
        if (lastRunOutDir == null) {
            throw new IllegalStateException("No run has been started in this window.");
        }
        return latestConsolidatedReport(lastRunOutDir);
    }

    private Path latestConsolidatedReport(Path runDir) {
        Path reportDir = runDir.resolve("consolidated_report");
        if (!Files.isDirectory(reportDir)) {
            throw new IllegalStateException("Consolidated report directory not found: " + reportDir);
        }

        try (var reports = Files.list(reportDir)) {
            return reports
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("report(\\(\\d+\\))?\\.tsv"))
                    .max(Comparator.comparingLong(this::lastModifiedMillis))
                    .orElseThrow(() -> new IllegalStateException("No consolidated report TSV found in: " + reportDir));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to list consolidated reports in: " + reportDir, e);
        }
    }

    /** The samples a past run analysed: the gel's lanes, with or without a band. */
    private static Set<String> samplesFromQaLog(Path qaLog) {
        if (!Files.isRegularFile(qaLog)) {
            throw new IllegalStateException("QAlog.txt not found: " + qaLog);
        }
        Set<String> samples = new TreeSet<>(QaLogInputs.sampleNames(QaLogInputs.read(qaLog)));
        // A sample left out for a faulty file is not a lane without a band.
        samples.removeAll(QaLogInputs.samplesNotAnalysed(qaLog));
        if (samples.isEmpty()) {
            throw new IllegalStateException("No input samples were found in QAlog.txt: " + qaLog);
        }
        return samples;
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read modification time for: " + path, e);
        }
    }

    public static void main(String[] args) {
        Application.launch(MainRun.class);
    }

    /** Appends a message to the run log, from any thread. */
    private void logMessage(String message) {
        Platform.runLater(() -> outputField.appendText("\n" + message + "\n"));
    }

    /** An error message in a dialog owned by the window. */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(primaryStage);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
