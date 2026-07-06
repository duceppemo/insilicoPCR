package ca.canada.inspection.insilicopcr;

import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.pipeline.DependencyContext;
import ca.canada.inspection.insilicopcr.pipeline.ExternalProcessTracker;
import ca.canada.inspection.insilicopcr.pipeline.InputValidator;
import ca.canada.inspection.insilicopcr.pipeline.LogFiles;
import ca.canada.inspection.insilicopcr.pipeline.PcrPipelineTask;
import ca.canada.inspection.insilicopcr.pipeline.PcrRunConfig;
import ca.canada.inspection.insilicopcr.ui.PathFieldBinder;
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
import javafx.scene.layout.RowConstraints;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import javax.swing.JOptionPane;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainRun extends Application {

	private Path inputFile;
	private Path outDir;
	private Path primerFile;
	private TextArea outputField;
	private ProgressBar mainProgress;
	private ProgressBar blastProgress;
	private Button gelButton;

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

		var scene = new Scene(pane, 800, 500);
		scene.getStylesheets().add("MainRun.css");
		primaryStage.setScene(scene);
		primaryStage.setTitle("InSilico PCR " + Dispatcher.version);
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
		var prompt = new Text("Threads");
		prompt.getStyleClass().add("prompt");
		prompt.setTextAlignment(TextAlignment.LEFT);
		var box = new HBox(10, prompt);
		box.setAlignment(Pos.TOP_LEFT);
		pane.add(box, 38, 5, 5, 1);

		var field = new ComboBox<Integer>();
		field.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
		for (var i = 1; i < Runtime.getRuntime().availableProcessors(); i++) {
			field.getItems().add(i);
		}
		field.getSelectionModel().selectLast();
		field.setVisibleRowCount(3);
		pane.add(field, 44, 4, 4, 3);
		return field;
	}

	private ComboBox<Integer> addMismatchControls(GridPane pane) {
		var prompt = new Text("Mismatches");
		prompt.getStyleClass().add("prompt");
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
		var prompt = new Text("Evalue");
		prompt.getStyleClass().add("prompt");
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
			pane.add(gelButton, 30, 45, 7, 3);
		});
		runningTask.setOnFailed(event -> {
			currentlyRunning.set(false);
			var error = runningTask.getException();
			Methods.logMessage(outputField, error == null ? "Run failed" : "Run failed: " + error.getMessage());
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
		var prompt = new Text(text);
		prompt.getStyleClass().add("prompt");
		var box = new HBox(10, prompt);
		box.setPrefSize(Double.MAX_VALUE, Double.MAX_VALUE);
		pane.add(box, column, row, colspan, rowspan);
	}

	public static void displayGelImage() {
		// Placeholder retained from the original application.
	}

	public static void main(String[] args) {
		Application.launch(MainRun.class);
	}
}
