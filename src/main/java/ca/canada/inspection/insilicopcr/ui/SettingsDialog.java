package ca.canada.inspection.insilicopcr.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.stage.Window;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Shared layout and behavior of the settings dialogs: labelled rows with tooltips,
 * a "Defaults" button that refills the fields, and validation on OK that keeps the
 * dialog open with a message when a value is invalid.
 */
final class SettingsDialog {

    private static final ButtonType RESET = new ButtonType("Defaults", ButtonBar.ButtonData.LEFT);

    /** Suffix shown on a button whose settings differ from the defaults. */
    static final String CHANGED_MARK = " *";

    private final GridPane grid = new GridPane();
    private int row;

    SettingsDialog() {
        grid.setHgap(10);
        grid.setVgap(8);
    }

    void section(String title) {
        var label = new Label(title);
        label.setStyle("-fx-font-weight: bold;");
        grid.add(label, 0, row++, 2, 1);
    }

    TextField field(String label, String value, String tooltip) {
        var field = new TextField(value);
        field.setPrefColumnCount(6);
        var name = new Label(label);
        var tip = new Tooltip(tooltip);
        tip.setWrapText(true);
        tip.setMaxWidth(380);
        name.setTooltip(tip);
        field.setTooltip(tip);
        grid.add(name, 0, row);
        grid.add(field, 1, row++);
        return field;
    }

    /**
     * @param parse reads the fields; throws {@link IllegalArgumentException} with a
     *              user-facing message when a value is invalid
     * @param reset refills the fields with the defaults
     * @return the parsed settings, or empty when cancelled
     */
    <T> Optional<T> show(Window owner, String title, String header, Supplier<T> parse, Runnable reset) {
        var dialog = new Dialog<T>();
        dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(header);

        var error = new Text();
        error.setStyle("-fx-fill: red;");
        var content = new VBox(10, grid, error);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(RESET, ButtonType.OK, ButtonType.CANCEL);

        dialog.getDialogPane().lookupButton(RESET).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            reset.run();
            error.setText("");
        });
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                parse.get();
                error.setText("");
            } catch (IllegalArgumentException e) {
                error.setText(e.getMessage());
                event.consume();
            }
        });
        dialog.setResultConverter(button -> button == ButtonType.OK ? parse.get() : null);
        return dialog.showAndWait();
    }

    static int parseInt(TextField field, String label, int minimum) {
        int value;
        try {
            value = Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " must be a whole number.");
        }
        if (value < minimum) {
            throw new IllegalArgumentException(label + " must be at least " + minimum + ".");
        }
        return value;
    }
}
