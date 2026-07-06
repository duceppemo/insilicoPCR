package ca.canada.inspection.insilicopcr.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Utility methods that isolate JavaFX FileChooser/File drag APIs from Path-based application code. */
public final class PathFieldBinder {
    private PathFieldBinder() {}

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

    public static void chooseFileOrDirectory(Window owner, TextField field, Supplier<Boolean> directoryMode, Consumer<Path> onPathSelected) {
        if (directoryMode.get()) {
            chooseDirectory(owner, field, onPathSelected);
        } else {
            chooseFile(owner, field, onPathSelected);
        }
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
