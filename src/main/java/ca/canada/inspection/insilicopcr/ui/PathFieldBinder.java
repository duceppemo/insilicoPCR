package ca.canada.inspection.insilicopcr.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/** Utility methods that isolate JavaFX FileChooser/File drag APIs from Path-based application code. */
public final class PathFieldBinder {
    private PathFieldBinder() {}

    /**
     * Keeps the selected path in step with what is typed in {@code field}: without this,
     * editing the text after choosing or dropping a path left the old path in use. Blank
     * or unparsable text clears the selection ({@code null}).
     */
    public static void bindTypedPath(TextField field, Consumer<Path> onPathSelected) {
        field.textProperty().addListener((observable, oldText, newText) -> onPathSelected.accept(toPath(newText)));
    }

    static Path toPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Path.of(text.strip());
        } catch (InvalidPathException e) {
            return null;
        }
    }

    public static void bindDropTarget(TextField field, Consumer<Path> onPathSelected) {
        field.setOnDragOver(event -> {
            if (event.getGestureSource() != field && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        field.setOnDragDropped(event -> {
            var dragboard = event.getDragboard();
            var success = false;
            if (dragboard.hasFiles()) {
                var path = dragboard.getFiles().getFirst().toPath();
                onPathSelected.accept(path);
                field.setText(path.toString());
                success = true;
            }
            event.setDropCompleted(success);
            event.consume();
        });
    }

    public static void chooseFile(Window owner, TextField field, Consumer<Path> onPathSelected) {
        var chooser = new FileChooser();
        select(chooser.showOpenDialog(owner), field, onPathSelected);
    }

    public static void chooseDirectory(Window owner, TextField field, Consumer<Path> onPathSelected) {
        var chooser = new DirectoryChooser();
        select(chooser.showDialog(owner), field, onPathSelected);
    }

    private static void select(File selected, TextField field, Consumer<Path> onPathSelected) {
        Optional.ofNullable(selected)
                .map(File::toPath)
                .ifPresent(path -> {
                    onPathSelected.accept(path);
                    field.setText(path.toString());
                });
    }
}
