package ca.canada.inspection.insilicopcr.ui;

import javafx.scene.control.TextField;
import javafx.scene.input.TransferMode;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
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

    /**
     * Typed text as an absolute path: a leading {@code ~} is the home folder (as in a
     * shell; Java would otherwise create a folder literally named {@code ~}), and a
     * relative path is resolved now, so logs record where the run really read or wrote.
     */
    static Path toPath(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String path = unquoted(text.strip());
        if (path.isEmpty()) {
            return null;
        }
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * {@code path} without the double quotes around it. Windows Explorer's "Copy as path" puts them
     * there, and a path pasted with them was refused on Windows (a quote cannot be in a file name),
     * and on Linux named a file that does not exist.
     */
    private static String unquoted(String path) {
        if (path.length() >= 2 && path.startsWith("\"") && path.endsWith("\"")) {
            return path.substring(1, path.length() - 1).strip();
        }
        return path;
    }

    /**
     * The path a drop of {@code files} gives a field, or {@code null}: a field holds one file or folder.
     * Dropping both files of a read pair on the input field kept the first, which was then run alone
     * as single-end reads; such a drop is not taken (the pointer says so while dragging).
     */
    static Path dropped(List<File> files) {
        return files != null && files.size() == 1 ? files.getFirst().toPath() : null;
    }

    public static void bindDropTarget(TextField field, Consumer<Path> onPathSelected) {
        field.setOnDragOver(event -> {
            if (event.getGestureSource() != field && event.getDragboard().hasFiles()
                    && dropped(event.getDragboard().getFiles()) != null) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
            }
            event.consume();
        });

        field.setOnDragDropped(event -> {
            Path path = event.getDragboard().hasFiles() ? dropped(event.getDragboard().getFiles()) : null;
            if (path != null) {
                onPathSelected.accept(path);
                field.setText(path.toString());
            }
            event.setDropCompleted(path != null);
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
