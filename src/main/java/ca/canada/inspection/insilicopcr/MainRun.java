package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.ui.PathFieldBinder;
import ca.canada.inspection.pipeline.DependencyContext;
import ca.canada.inspection.pipeline.ExternalProcessTracker;
import ca.canada.inspection.pipeline.InputValidator;
import ca.canada.inspection.pipeline.LogFiles;
import ca.canada.inspection.pipeline.PcrPipelineTask;
import ca.canada.inspection.pipeline.PcrRunConfig;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.RowConstraints;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import javafx.scene.image.Image;

import javax.swing.JOptionPane;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Comparator;
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
    private Scene scene;

    private final ExternalProcessTracker processTracker = new ExternalProcessTracker();
    private final AtomicBoolean currentlyRunning = new AtomicBoolean(false);
    private PcrPipelineTask runningTask;
    private DependencyContext dependencies;

    @Override
    public void start(Stage primaryStage) {
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
        addOutputLog(pane, alertText);
        addRunButton(pane, alertText, threadField, mismatchField, evalueField);

        scene = new Scene(pane, 800, 500);
        primaryStage.setScene(scene);
        primaryStage.setTitle("InSilico PCR " + Dispatcher.VERSION);

        primaryStage.getIcons().add(
                new Image(MainRun.class.getResourceAsStream("/icons/insilicoPCR-icon.png"))
        );

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

        var isDirectory = new RadioButton("Input is a directory");
        isDirectory.setSelected(false);
        isDirectory.setAlignment(Pos.CENTER);
        pane.add(isDirectory, 3, 7, 10, 2);

        var browse = new Button("Select");
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        browse.setOnAction(event -> PathFieldBinder.chooseFileOrDirectory(stage, inputField, isDirectory::isSelected, path -> inputFile = path));
        pane.add(browse, 31, 4, 4, 2);
    }

    private void addPrimerControls(Stage stage, GridPane pane, TextField primerField) {
        addPrompt(pane, "Input a primer reference file in fasta format", 2, 10, 20, 2);
        primerField.setTooltip(new Tooltip("Please refer to the custom_primer_guide.txt for instructions on how to create a valid primer file"));
        primerField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        primerField.setEditable(true);
        pane.add(primerField, 2, 12, 28, 2);
        PathFieldBinder.bindDropTarget(primerField, path -> primerFile = path);

        var browse = new Button("Select");
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        browse.setOnAction(event -> PathFieldBinder.chooseFile(stage, primerField, path -> primerFile = path));
        pane.add(browse, 31, 12, 4, 2);
    }

    private void addOutputControls(Stage stage, GridPane pane, TextField outputDirField) {
        addPrompt(pane, "Output directory. Path MUST NOT contain spaces", 2, 15, 20, 2);
        outputDirField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        outputDirField.setEditable(false);
        pane.add(outputDirField, 2, 17, 28, 2);
        PathFieldBinder.bindDropTarget(outputDirField, path -> outDir = path);

        var browse = new Button("Select");
        browse.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        browse.setOnAction(event -> PathFieldBinder.chooseDirectory(stage, outputDirField, path -> outDir = path));
        pane.add(browse, 31, 17, 4, 2);
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
        field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        field.setEditable(true);
        pane.add(field, 44, 14, 4, 3);
        return field;
    }

    private void addOutputLog(GridPane pane, Text alertText) {
        outputField.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        outputField.setEditable(false);
        pane.add(outputField, 2, 21, 46, 22);

        alertText.setStyle("-fx-fill: red;");
        var alertBox = new HBox(10, alertText);
        alertBox.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        alertBox.setAlignment(Pos.CENTER);
        pane.add(alertBox, 1, 43, 48, 2);
    }

    private void addRunButton(GridPane pane,
                              Text alertText,
                              ComboBox<Integer> threadField,
                              ComboBox<Integer> mismatchField,
                              TextField evalueField) {
        gelButton = new Button("View Gel Image");
        gelButton.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        gelButton.setOnAction(event -> displayGelImage());

        var runButton = new Button("Run");
        runButton.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        runButton.setOnAction(event -> startRun(pane, alertText, threadField, mismatchField, evalueField));
        pane.add(runButton, 22, 45, 5, 3);
    }

    private void startRun(GridPane pane,
                          Text alertText,
                          ComboBox<Integer> threadField,
                          ComboBox<Integer> mismatchField,
                          TextField evalueField) {
        var validationMessage = InputValidator.validate(inputFile, outDir, primerFile);
        if (!validationMessage.isBlank()) {
            alertText.setText(validationMessage);
            return;
        }

        alertText.setText("");
        outputField.clear();
        currentlyRunning.set(true);

        if (mainProgress != null && mainProgress.getParent() != null) {
            ((Pane) mainProgress.getParent()).getChildren().remove(mainProgress);
        }

        if (blastProgress != null && blastProgress.getParent() != null) {
            ((Pane) blastProgress.getParent()).getChildren().remove(blastProgress);
        }
        mainProgress = new ProgressBar();
        mainProgress.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(mainProgress, 2, 43, 46, 2);

        blastProgress = new ProgressBar();
        blastProgress.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
        pane.add(blastProgress, 30, 46, 18, 2);

        var config = new PcrRunConfig(
                inputFile,
                outDir,
                primerFile,
                threadField.getSelectionModel().getSelectedItem(),
                mismatchField.getSelectionModel().getSelectedItem(),
                Double.parseDouble(evalueField.getText()),
                outputField,
                blastProgress
        );

        runningTask = new PcrPipelineTask(config, processTracker);
        mainProgress.progressProperty().bind(runningTask.progressProperty());
        runningTask.setOnSucceeded(event -> {
            currentlyRunning.set(false);
            dependencies = runningTask.dependencies();
            if (gelButton.getParent() != null) {
                ((Pane) gelButton.getParent()).getChildren().remove(gelButton);
            }
            pane.add(gelButton, 2, 45, 14, 3);
        });
        runningTask.setOnFailed(event -> {
            currentlyRunning.set(false);

            Throwable error = runningTask.getException();
            if (error == null) {
                Methods.logMessage(outputField, "Run failed");
                return;
            }

            error.printStackTrace();

            StringWriter sw = new StringWriter();
            error.printStackTrace(new PrintWriter(sw));
            Methods.logMessage(outputField, "Run failed:\n" + sw);
        });

        var thread = new Thread(runningTask, "insilico-pcr-run");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean confirmClose() {
        if (inputFile == null || outDir == null || primerFile == null) {
            Platform.exit();
            return true;
        }

        if (currentlyRunning.get()) {
            var choice = JOptionPane.showConfirmDialog(null,
                    "Are you sure you want to exit? If the program is running, output may become corrupted",
                    "Exit InSilico PCR",
                    JOptionPane.OK_CANCEL_OPTION);
            if (choice != JOptionPane.OK_OPTION) {
                return false;
            }
            if (runningTask != null) {
                runningTask.shutdownNow();
                runningTask.cancel(true);
            }
        }

        try {
            if (dependencies == null) {
                dependencies = DependencyContext.discover(outputField);
            }
            LogFiles.ensureQaLog(outDir, inputFile, primerFile, dependencies);
            LogFiles.appendRunLog(outDir, outputField);
        } catch (RuntimeException e) {
            Methods.logMessage(outputField, "Unable to write shutdown logs: " + e.getMessage());
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
            Methods.makeSyntheticGel(scene, latestConsolidatedReport());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Unable to create gel image:\n" + e.getMessage());
        }
    }

    private Path latestConsolidatedReport() {
        Path reportDir = outDir.resolve("consolidated_report");
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
            throw new IllegalStateException("Unable to list consolidated reports in: " + reportDir, e);
        }
    }

    private long lastModifiedMillis(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read modification time for: " + path, e);
        }
    }

    public static void main(String[] args) {
        Application.launch(MainRun.class);
    }
}
