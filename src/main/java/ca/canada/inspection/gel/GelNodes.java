package ca.canada.inspection.gel;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Draws a {@link GelScene} as JavaFX nodes, for the gel viewers. */
final class GelNodes {
    private GelNodes() {
    }

    /**
     * A band's four rectangles in a group; the viewers restyle {@code body} and {@code halo}
     * when the band is hovered, selected or found.
     */
    record BandShape(Group group, Rectangle halo, Rectangle body) {
    }

    /**
     * A pane holding {@code scene}.
     *
     * @param overGel    nodes drawn over the gel's lanes and under its bands (the viewer's lane
     *                   highlights)
     * @param sampleBand the node of each sample band, made by the viewer, which handles its
     *                   mouse events; built from {@link #band}
     */
    static Pane pane(GelScene scene, List<? extends Node> overGel, Function<GelScene.Band, Node> sampleBand) {
        Pane pane = new Pane();
        pane.setPrefSize(scene.width(), scene.height());
        pane.setMinSize(scene.width(), scene.height());
        pane.setMaxSize(scene.width(), scene.height());
        scene.background().forEach(box -> pane.getChildren().add(rectangle(box)));
        pane.getChildren().addAll(overGel);
        scene.ladder().forEach(band -> pane.getChildren().add(band(band).group()));
        scene.ladderLabels().forEach(label -> pane.getChildren().add(text(label)));
        scene.bands().forEach(band -> pane.getChildren().add(sampleBand.apply(band)));
        pane.getChildren().add(rectangle(scene.border()));
        scene.laneLabels().forEach(label -> pane.getChildren().add(text(label)));
        scene.legendBoxes().forEach(box -> pane.getChildren().add(rectangle(box)));
        scene.legendLabels().forEach(label -> pane.getChildren().add(text(label)));
        return pane;
    }

    /** The rectangles of a band, which take no mouse events. */
    static BandShape band(GelScene.Band band) {
        Rectangle halo = rectangle(band.halo());
        Rectangle body = rectangle(band.body());
        Group group = new Group(halo, body, rectangle(band.highlight()), rectangle(band.shadow()));
        group.setMouseTransparent(true);
        return new BandShape(group, halo, body);
    }

    /** The colour key shown beside a coloured gel: {@code title}, then each entry and its colour. */
    static VBox legend(String title, Map<String, Color> entries) {
        VBox legend = new VBox(5);
        legend.setPadding(new Insets(10));
        legend.setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-border-color: #bbbbbb;");
        Label heading = new Label(title);
        heading.setStyle("-fx-font-weight: bold;");
        legend.getChildren().add(heading);
        for (Map.Entry<String, Color> entry : entries.entrySet()) {
            HBox row = new HBox(6, new Rectangle(12, 12, entry.getValue()), new Label(entry.getKey()));
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        return legend;
    }

    private static Rectangle rectangle(GelScene.Box box) {
        Rectangle rectangle = new Rectangle(box.x(), box.y(), box.width(), box.height());
        rectangle.setFill(box.fill() == null ? Color.TRANSPARENT : box.fill());
        if (box.stroke() != null) {
            rectangle.setStroke(box.stroke());
            rectangle.setStrokeWidth(box.strokeWidth());
        }
        rectangle.setMouseTransparent(true);
        return rectangle;
    }

    private static Text text(GelScene.Label label) {
        Text text = new Text(label.text());
        text.setFont(Font.font(GelScene.FONT, label.fontSize()));
        text.setFill(Color.BLACK);
        text.setY(label.y());
        if (label.anchor() == GelScene.Anchor.END) {
            text.setX(label.x() - text.getLayoutBounds().getWidth());
        } else if (label.anchor() == GelScene.Anchor.START) {
            text.setX(label.x());
        } else {
            text.setX(label.x());
            text.getTransforms().add(new Rotate(45, label.x(), label.y()));
        }
        text.setMouseTransparent(true);
        return text;
    }
}
