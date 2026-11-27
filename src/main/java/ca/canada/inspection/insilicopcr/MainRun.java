package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.util.Version;
import ca.canada.inspection.gel.InteractiveGelViewer;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.insilicopcr.ui.PathFieldBinder;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.PreviousRun;
import ca.canada.inspection.pipeline.RunResult;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
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

    /** How long closing the window waits for a cancelled run to stop and write its log. */
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(20);

    private final AtomicBoolean currentlyRunning = new AtomicBoolean(false);
    private PcrPipelineTask runningTask;
    private Thread runningThread;
    private FastqSettings fastqSettings = FastqSettings.DEFAULTS;
    /** What the last finished run wrote; {@code null} before one finishes. */
    private RunResult lastResult;
    /** Reads reports for the gel viewer ({@link #openGel}); made with the window. */
    private GelLoader<InteractiveGelViewer.Loaded> gelLoader;
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
        var annealField = addAnnealTempControls(pane);
        var evalueField = addEvalueControls(pane);
        addSettingsButtons(primaryStage, pane);
        addOutputLog(pane);
        addBottomControls(pane, alertText, threadField, annealField, evalueField);

        scene = new Scene(pane, 800, 500);
        primaryStage.setScene(scene);
        primaryStage.setTitle("InSilico PCR " + Version.VERSION);
        // Smaller, labels lay over fields and the buttons read "...".
        primaryStage.setMinWidth(720);
        primaryStage.setMinHeight(460);

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
        browse.setTooltip(new Tooltip("Choose one sequence file, or a folder of FASTA/FASTQ files (the files directly in"
                + " it: its subfolders are not read). "
                + "You can also drag a file or folder onto the field."));
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(browse, 31, 4, 5, 2);
    }

    private void addPrimerControls(Stage stage, GridPane pane, TextField primerField) {
        addPrompt(pane, "Input a primer reference file in fasta format", 2, 7, 20, 2);
        primerField.setTooltip(new Tooltip("A FASTA of primers named <assay>-F, <assay>-R and, for qPCR, <assay>-P; see Primer FASTA Format in the wiki"));
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
        addPrompt(pane, "Output directory", 2, 12, 20, 2);
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
        // One processor fewer than the command line's default, so the window stays responsive.
        int maxThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        for (var i = 1; i <= maxThreads; i++) {
            field.getItems().add(i);
        }
        field.getSelectionModel().selectLast();
        field.setVisibleRowCount(3);
        pane.add(field, 44, 4, 4, 3);
        return field;
    }

    private TextField addAnnealTempControls(GridPane pane) {
        var prompt = promptText("Anneal (°C)");
        prompt.setTextAlignment(TextAlignment.LEFT);
        var box = new HBox(10, prompt);
        box.setAlignment(Pos.TOP_LEFT);
        pane.add(box, 38, 10, 5, 1);

        var field = new TextField(ca.canada.inspection.util.Numbers.plain(ca.canada.inspection.pcr.TmGate.DEFAULT_ANNEAL_TEMP));
        var tip = new Tooltip("Annealing temperature of the reaction (--anneal-temp). A primer or probe site is accepted"
                + " when its Tm on the template reaches it less the Tm margin (Amplicon settings), and a primer's 3'"
                + " end pairs. An assay whose primer header gives ta=<°C> is judged at that temperature instead. Lower it to"
                + " accept weaker sites; report_no_product.tsv and the HTML report say why an assay gave no product.");
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        field.setTooltip(tip);
        field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(field, 44, 9, 4, 3);
        return field;
    }

    private TextField addEvalueControls(GridPane pane) {
        var prompt = promptText("E-value");
        prompt.setTextAlignment(TextAlignment.LEFT);
        var box = new HBox(10, prompt);
        box.setAlignment(Pos.TOP_LEFT);
        pane.add(box, 38, 15, 5, 1);

        var field = new TextField(PipelineConfig.DEFAULT_EVALUE_TEXT);
        var tip = new Tooltip("BLAST e-value. Deliberately permissive: primer hits are filtered by their Tm and the "
                + "3'-end rule instead. A short primer's e-value grows with contig length, so a strict value "
                + "such as 1e-5 drops valid hits. Default " + PipelineConfig.DEFAULT_EVALUE_TEXT + " (-e on the command line).");
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
                                   TextField annealField,
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

        // Run, and while a run goes on, Stop: the window stays open, unlike closing it.
        runButton = new Button("Run");
        runButton.setMaxWidth(Double.MAX_VALUE);
        runButton.setOnAction(event -> {
            if (currentlyRunning.get()) {
                confirmStop();
            } else {
                startRun(alertText, threadField, annealField, evalueField);
            }
        });

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

        // Wrapped to the window: a message holding a long path pushed Run and the buttons beside it out of it.
        alertText.wrappingWidthProperty().bind(pane.widthProperty().subtract(80));
        mainProgress.setTooltip(new javafx.scene.control.Tooltip("Steps of the run done"));
        blastProgress.setTooltip(new javafx.scene.control.Tooltip("Samples done in the step running"));
        runButton.setDefaultButton(true);
        VBox bottomPanel = new VBox(5, alertText, mainProgress, buttons, blastProgress);
        bottomPanel.setAlignment(Pos.CENTER);
        bottomPanel.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(bottomPanel, 2, 41, 46, 8);
    }

    private void startRun(Text alertText,
                          ComboBox<Integer> threadField,
                          TextField annealField,
                          TextField evalueField) {
        if (currentlyRunning.get()) {
            return; // the button is Stop while running; ignore a stray second trigger
        }
        // Every typed value is checked BEFORE any UI state changes, so a bad entry cannot leave the
        // window stuck in the "running" state.
        RunForm.Outcome outcome = new RunForm(inputFile, outDir, primerFile,
                threadField.getSelectionModel().getSelectedItem(), annealField.getText(),
                evalueField.getText(), ampliconSettings, fastqSettings).toConfig();
        if (outcome instanceof RunForm.Outcome.Invalid invalid) {
            alertText.setText(invalid.message());
            return;
        }
        var config = ((RunForm.Outcome.Run) outcome).config();

        alertText.setText("");
        outputField.clear();
        currentlyRunning.set(true);
        runButton.setText("Stop");
        gelButton.setDisable(true);
        htmlButton.setDisable(true);
        showProgressBars(true);
        mainProgress.progressProperty().unbind();
        mainProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        blastProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        // lastResult stays the last run that finished: a run that fails or is stopped leaves its gel and
        // report to view, as the buttons say (they were turned off for good).

        runningTask = new PcrPipelineTask(config, outputField, blastProgress);
        mainProgress.progressProperty().bind(runningTask.progressProperty());
        runningTask.setOnCancelled(event -> {
            // Cancelled is said at once; the run then stops its programs and writes its log. Run comes
            // back once it has: a run started sooner would find its output folder still held.
            showProgressBars(false);
            runButton.setText("Stopping…");
            runButton.setDisable(true);
            Thread run = runningThread;
            PcrPipelineTask stopped = runningTask;
            Thread.ofPlatform().daemon().name("after-stop").start(() -> {
                try {
                    run.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.runLater(() -> {
                    // Stopped after it had finished: its reports are complete, and the buttons show them.
                    if (stopped.finishedResult() != null) {
                        lastResult = stopped.finishedResult();
                    }
                    currentlyRunning.set(false);
                    runButton.setText("Run");
                    runButton.setDisable(false);
                    gelButton.setDisable(lastResult == null);
                    htmlButton.setDisable(lastResult == null);
                });
            });
        });
        runningTask.setOnSucceeded(event -> {
            lastResult = runningTask.getValue();
            runButton.setText("Run");
            currentlyRunning.set(false);
            gelButton.setDisable(false);
            htmlButton.setDisable(false);
            showProgressBars(false);
        });
        runningTask.setOnFailed(event -> {
            runButton.setText("Run");
            currentlyRunning.set(false);
            gelButton.setDisable(lastResult == null);
            htmlButton.setDisable(lastResult == null);
            showProgressBars(false);
            // The task has shown why, and written it to log.txt.
        });

        // A daemon: a run that does not stop within CLOSE_WAIT of the window closing does
        // not keep the program running.
        runningThread = new Thread(runningTask, "insilico-pcr-run");
        runningThread.setDaemon(true);
        runningThread.start();
    }

    private void showProgressBars(boolean visible) {
        mainProgress.setVisible(visible);
        mainProgress.setManaged(visible);
        blastProgress.setVisible(visible);
        blastProgress.setManaged(visible);
    }

    /** Stops the run on show, once asked: its programs end, and it writes no report. */
    private void confirmStop() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Stop the run? Its programs are stopped, and it writes no report; what this window showed of it"
                        + " is added to log.txt in its output folder.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.initOwner(primaryStage);
        confirm.setTitle("Stop the run");
        confirm.setHeaderText(null);
        // The run may have ended while the question was asked: then there is nothing to stop.
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK && !runningTask.isDone()) {
            runningTask.cancel(true);
        }
    }

    private boolean confirmClose() {
        if (currentlyRunning.get()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Are you sure you want to exit? The run will be stopped.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(primaryStage);
            confirm.setTitle("Exit InSilico PCR");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return false;
            }
            // Stops the run and its programs; the run then writes its log.txt and ends. The window
            // closes at once: the wait is on a thread of its own, not the window's, and that thread
            // (not a daemon) keeps the program running until the run has stopped.
            runningTask.cancel(true);
            Thread run = runningThread;
            Thread wait = new Thread(() -> {
                try {
                    if (!run.join(CLOSE_WAIT)) {
                        System.err.println("The run did not stop within " + CLOSE_WAIT.toSeconds()
                                + " seconds of the window closing; its log.txt may be incomplete.");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                Platform.exit();
            }, "close-after-run");
            wait.start();
            return true;
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
        RunResult run;
        try {
            run = finishedRun();
        } catch (Exception e) {
            showError("Unable to create gel image:\n" + e.getMessage());
            return;
        }
        openGel(() -> InteractiveGelViewer.load(run.report(), run.samples()), "Unable to create gel image:\n");
    }

    /** Reads a report for the gel viewer off the window's thread, then opens the viewer ({@link GelLoader}). */
    private void openGel(java.util.concurrent.Callable<InteractiveGelViewer.Loaded> load, String failure) {
        if (gelLoader == null) {
            gelLoader = new GelLoader<>(primaryStage.getScene(), InteractiveGelViewer::show, this::showError);
        }
        gelLoader.open(load, failure);
    }

    /** Opens the last run's HTML report in the system browser. */
    private void viewHtmlReport() {
        try {
            Path html = finishedRun().html();
            if (!Files.isRegularFile(html)) {
                throw new IllegalStateException("HTML report not found: " + html);
            }
            getHostServices().showDocument(html.toUri().toString());
        } catch (Exception e) {
            showError("Unable to open the HTML report:\n" + e.getMessage());
        }
    }

    /** The last run of this window that finished; the buttons that need one are disabled before. */
    private RunResult finishedRun() {
        if (lastResult == null) {
            throw new IllegalStateException("No run has finished in this window.");
        }
        return lastResult;
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

        Path folder = selected.toPath();
        openGel(() -> {
            PreviousRun run = PreviousRun.open(folder);
            return InteractiveGelViewer.load(run.report(), run.samples());
        }, "Unable to open previous run:\n");
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

        Path report = selected.toPath();
        openGel(() -> InteractiveGelViewer.load(report, List.of()), "Unable to open report TSV:\n");
    }

    public static void main(String[] args) {
        Application.launch(MainRun.class);
    }

    /** An error message in a dialog owned by the window. */
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(primaryStage);
        alert.setHeaderText(null);
        alert.showAndWait();
    }
}
