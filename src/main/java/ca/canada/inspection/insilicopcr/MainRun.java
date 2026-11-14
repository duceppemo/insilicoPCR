package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.util.Version;
import ca.canada.inspection.gel.PaginatedGelViewer;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog;
import ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings;
import ca.canada.inspection.insilicopcr.ui.PathFieldBinder;
import ca.canada.inspection.pipeline.InputValidator;
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

        var field = new TextField(PipelineConfig.DEFAULT_EVALUE_TEXT);
        var tip = new Tooltip("BLAST e-value. Deliberately permissive: primer hits are filtered by the mismatch and "
                + "3'/5'-end rules instead. A short primer's e-value grows with contig length, so a strict value "
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

        lastResult = null;
        // The window has its own gel viewer, and checks every read as the command line does
        // by default.
        var config = PipelineConfig.builder(inputFile, outDir, primerFile)
                .threads(threadField.getSelectionModel().getSelectedItem())
                .evalue(evalue)
                .ampliconOptions(ampliconOptions)
                .tadpole(fastqSettings.tadpole())
                .readMinFraction(fastqSettings.readMinFraction())
                .readExtension(fastqSettings.readExtension())
                .build();

        runningTask = new PcrPipelineTask(config, outputField, blastProgress);
        mainProgress.progressProperty().bind(runningTask.progressProperty());
        runningTask.setOnCancelled(event -> {
            currentlyRunning.set(false);
            runButton.setDisable(false);
            showProgressBars(false);
        });
        runningTask.setOnSucceeded(event -> {
            lastResult = runningTask.getValue();
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
        try {
            PaginatedGelViewer.show(scene, finishedRun().report(), finishedRun().samples());
        } catch (Exception e) {
            showError("Unable to create gel image:\n" + e.getMessage());
        }
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

        try {
            PreviousRun run = PreviousRun.open(selected.toPath());
            PaginatedGelViewer.show(scene, run.report(), run.samples());
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
