package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.insilicopcr.gel.LaneBand.BandRect;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Paginated synthetic gel viewer for large runs. */
public final class PaginatedGelViewer {
    public static final int DEFAULT_SAMPLES_PER_GEL = 100;

    private static final double LADDER_FONT_SIZE = 10.0;

    private PaginatedGelViewer() {
    }

    public static void show(Scene ownerScene, Path consolidatedReport, HashMap<String, Sample> sampleDict) {
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(consolidatedReport, sampleDict);
        if (lanes.size() <= DEFAULT_SAMPLES_PER_GEL) {
            InteractiveGelViewer.show(ownerScene, consolidatedReport, sampleDict);
            return;
        }

        showPagedGel(new PageModel(ownerScene, lanes, DEFAULT_SAMPLES_PER_GEL));
    }

    private static void showPagedGel(PageModel model) {
        Stage stage = new Stage();
        BorderPane root = new BorderPane();
        Group zoomGroup = new Group();
        ScrollPane scrollPane = new ScrollPane(zoomGroup);
        scrollPane.setPannable(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setFitToHeight(false);
        root.setCenter(scrollPane);

        Label pageLabel = new Label();
        Label rangeLabel = new Label();
        Label matchLabel = new Label();
        Label statusLabel = new Label();
        TextField searchField = new TextField();
        searchField.setPromptText("Search all samples/genes/sizes");
        CheckBox colorByGene = new CheckBox("Color by gene");
        CheckBox showLegend = new CheckBox("Show legend");
        showLegend.setSelected(true);
        showLegend.setDisable(true);
        Button savePng = new Button("Save PNG...");
        Button saveSvg = new Button("Save SVG...");
        Button savePdf = new Button("Save PDF...");
        Button zoomOut = new Button("−");
        Button zoomReset = new Button("100%");
        Button zoomIn = new Button("+");
        Button previous = new Button("◀ Previous Gel");
        Button next = new Button("Next Gel ▶");
        Spinner<Integer> pageSpinner = new Spinner<>(1, model.totalPages(), 1);
        pageSpinner.setEditable(true);
        pageSpinner.setPrefWidth(88);

        HBox searchToolbar = new HBox(8, new Label("Search:"), searchField, matchLabel,
                new Label("Zoom:"), zoomOut, zoomReset, zoomIn);
        searchToolbar.setAlignment(Pos.CENTER_LEFT);
        searchToolbar.setPadding(new Insets(8, 8, 4, 8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox exportToolbar = new HBox(8, savePng, saveSvg, savePdf, colorByGene, showLegend);
        exportToolbar.setAlignment(Pos.CENTER_LEFT);
        exportToolbar.setPadding(new Insets(4, 8, 4, 8));

        HBox pageToolbar = new HBox(8, previous, new Label("Page"), pageSpinner, pageLabel, next, rangeLabel);
        pageToolbar.setAlignment(Pos.CENTER_LEFT);
        pageToolbar.setPadding(new Insets(4, 8, 8, 8));
        root.setTop(new VBox(searchToolbar, exportToolbar, pageToolbar));

        VBox legend = buildLegend(model.geneColors);
        legend.setVisible(false);
        legend.setManaged(false);
        root.setRight(legend);

        HBox statusBar = new HBox(statusLabel);
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 8, 4, 8));
        statusBar.setStyle("-fx-background-color: #f4f4f4; -fx-border-color: #d0d0d0 transparent transparent transparent;");
        root.setBottom(statusBar);

        final boolean[] rendering = {false};
        Runnable render = () -> {
            rendering[0] = true;
            try {
                Pane pagePane = drawPage(model, colorByGene.isSelected());
                pagePane.setScaleX(model.zoom);
                pagePane.setScaleY(model.zoom);
                zoomGroup.getChildren().setAll(pagePane);
                pageLabel.setText("of " + model.totalPages());
                rangeLabel.setText(model.currentRangeText());
                previous.setDisable(model.pageIndex == 0);
                next.setDisable(model.pageIndex >= model.totalPages() - 1);
                zoomReset.setText(Math.round(model.zoom * 100) + "%");
                legend.setVisible(colorByGene.isSelected() && showLegend.isSelected());
                legend.setManaged(colorByGene.isSelected() && showLegend.isSelected());
                showLegend.setDisable(!colorByGene.isSelected());
                if (pageSpinner.getValue() == null || pageSpinner.getValue() != model.pageIndex + 1) {
                    pageSpinner.getValueFactory().setValue(model.pageIndex + 1);
                }
                statusLabel.setText(model.pages.sampleNames().size() + " samples | Page " + (model.pageIndex + 1) + "/" + model.totalPages()
                        + " | " + model.pages.samplesPerGel() + " samples/page | Zoom " + Math.round(model.zoom * 100) + "%");
                stage.setTitle("Synthetic Gel - page " + (model.pageIndex + 1) + " of " + model.totalPages());
            } finally {
                rendering[0] = false;
            }
        };

        Runnable goPrevious = () -> {
            if (model.pageIndex > 0) {
                model.pageIndex--;
                render.run();
            }
        };
        Runnable goNext = () -> {
            if (model.pageIndex < model.totalPages() - 1) {
                model.pageIndex++;
                render.run();
            }
        };
        Runnable saveCurrentPng = () -> chooseAndSavePng(model, currentPane(zoomGroup));
        Runnable saveCurrentSvg = () -> chooseAndSaveSvg(model, colorByGene.isSelected());
        Runnable saveCurrentPdf = () -> chooseAndSavePdf(model, currentPane(zoomGroup));
        Runnable resetZoom = () -> {
            model.zoom = 1.0;
            render.run();
        };
        Runnable increaseZoom = () -> {
            model.zoom = Math.min(3.0, model.zoom * 1.15);
            render.run();
        };
        Runnable decreaseZoom = () -> {
            model.zoom = Math.max(0.45, model.zoom / 1.15);
            render.run();
        };

        previous.setOnAction(event -> goPrevious.run());
        next.setOnAction(event -> goNext.run());
        colorByGene.setOnAction(event -> render.run());
        showLegend.setOnAction(event -> render.run());
        zoomOut.setOnAction(event -> decreaseZoom.run());
        zoomIn.setOnAction(event -> increaseZoom.run());
        zoomReset.setOnAction(event -> resetZoom.run());
        pageSpinner.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (!rendering[0] && newValue != null) {
                int requested = Math.clamp(newValue, 1, model.totalPages());
                if (requested != model.pageIndex + 1) {
                    model.pageIndex = requested - 1;
                    render.run();
                }
            }
        });
        searchField.setOnAction(event -> {
            GelPages.Search result = model.pages.find(model.lanes, searchField.getText());
            if (result.pageIndex() >= 0) {
                model.pageIndex = result.pageIndex();
                matchLabel.setText(GelText.matches(result.matches()));
                render.run();
            } else {
                matchLabel.setText("No matches");
            }
        });
        savePng.setOnAction(event -> saveCurrentPng.run());
        saveSvg.setOnAction(event -> saveCurrentSvg.run());
        savePdf.setOnAction(event -> saveCurrentPdf.run());

        var screenBounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(root, Math.max(900, screenBounds.getWidth() - 120), Math.max(700, screenBounds.getHeight() - 120));
        installShortcuts(scene, searchField, goPrevious, goNext, resetZoom, increaseZoom, decreaseZoom, saveCurrentPng, saveCurrentSvg, saveCurrentPdf);
        stage.setScene(scene);
        render.run();
        stage.show();
    }

    private static void installShortcuts(Scene scene,
                                         TextField searchField,
                                         Runnable goPrevious,
                                         Runnable goNext,
                                         Runnable resetZoom,
                                         Runnable increaseZoom,
                                         Runnable decreaseZoom,
                                         Runnable savePng,
                                         Runnable saveSvg,
                                         Runnable savePdf) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.S) {
                saveSvg.run();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.S) {
                savePng.run();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.P) {
                savePdf.run();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.F) {
                searchField.requestFocus();
                searchField.selectAll();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.DIGIT0) {
                resetZoom.run();
                event.consume();
            } else if (event.isControlDown() && (event.getCode() == KeyCode.EQUALS || event.getCode() == KeyCode.ADD)) {
                increaseZoom.run();
                event.consume();
            } else if (event.isControlDown() && (event.getCode() == KeyCode.MINUS || event.getCode() == KeyCode.SUBTRACT)) {
                decreaseZoom.run();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT || event.getCode() == KeyCode.PAGE_UP) {
                goPrevious.run();
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.PAGE_DOWN) {
                goNext.run();
                event.consume();
            }
        });
    }

    private static VBox buildLegend(Map<String, Color> geneColors) {
        VBox legend = new VBox(5);
        legend.setPadding(new Insets(10));
        legend.setStyle("-fx-background-color: rgba(255,255,255,0.94); -fx-border-color: #bbbbbb;");
        Label title = new Label("Gene legend");
        title.setStyle("-fx-font-weight: bold;");
        legend.getChildren().add(title);
        for (Map.Entry<String, Color> entry : geneColors.entrySet()) {
            Rectangle swatch = new Rectangle(12, 12, entry.getValue());
            Label label = new Label(entry.getKey());
            HBox row = new HBox(6, swatch, label);
            row.setAlignment(Pos.CENTER_LEFT);
            legend.getChildren().add(row);
        }
        return legend;
    }

    private static Pane currentPane(Group zoomGroup) {
        return (Pane) zoomGroup.getChildren().getFirst();
    }

    private static Pane drawPage(PageModel model, boolean colorByGene) {
        List<String> samples = model.currentSamples();
        GelLayout layout = model.layout(samples);

        Pane pane = new Pane();
        pane.setPrefSize(layout.width(), layout.height());
        pane.setMinSize(layout.width(), layout.height());
        pane.setStyle("-fx-background-color: white;");

        drawBackground(pane, layout, samples.size() + 1);
        drawLadder(pane, layout);
        drawLadderLabels(pane, layout);

        int laneIndex = 1;
        for (String sampleName : samples) {
            for (LaneBand band : LaneBand.page(layout, laneIndex, model.lanes.getOrDefault(sampleName, List.of()))) {
                Color color = colorByGene ? model.geneColors.getOrDefault(band.gene(), Color.BLACK) : Color.BLACK;
                addBand(pane, band.rect(), band.centerY(), band.intensity(), color, band.tooltip());
            }
            addRotatedLabel(pane, sampleName, layout.laneCenterX(laneIndex), layout.labelY());
            laneIndex++;
        }
        addRotatedLabel(pane, "Ladder", layout.laneCenterX(0), layout.labelY());
        drawBorder(pane, layout);
        return pane;
    }

    private static void drawBackground(Pane pane, GelLayout layout, int laneCount) {
        Rectangle background = new Rectangle(layout.gelLeft(), layout.gelTop(), layout.gelWidth(), layout.gelHeight());
        background.setFill(BandPaint.GEL);
        pane.getChildren().add(background);
        for (int i = 0; i < laneCount; i++) {
            double x = layout.laneX(i);
            Rectangle lane = new Rectangle(x, layout.gelTop(), layout.laneWidth(), layout.gelHeight());
            lane.setFill(BandPaint.laneFill(i));
            pane.getChildren().add(lane);
            Rectangle separator = new Rectangle(x, layout.gelTop(), 3, layout.gelHeight());
            separator.setFill(Color.WHITE);
            pane.getChildren().add(separator);
        }
    }

    private static void drawBorder(Pane pane, GelLayout layout) {
        Rectangle border = new Rectangle(layout.gelLeft(), layout.gelTop(), layout.gelWidth(), layout.gelHeight());
        border.setFill(Color.TRANSPARENT);
        border.setStroke(Color.BLACK);
        border.setStrokeWidth(3);
        border.setMouseTransparent(true);
        pane.getChildren().add(border);
    }

    private static void drawLadder(Pane pane, GelLayout layout) {
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            double centerY = layout.bandY(ladderBand.size());
            addBand(pane, BandRect.inLane(layout.gelLeft(), centerY, layout.laneWidth(), 2.0), centerY, 0.45, Color.BLACK, null);
        }
    }

    private static void drawLadderLabels(Pane pane, GelLayout layout) {
        for (GelScale.LadderBand ladderBand : GelScale.LADDER) {
            Text text = new Text(ladderBand.label());
            text.setFont(Font.font("Verdana", LADDER_FONT_SIZE));
            text.setFill(Color.BLACK);
            text.setX(layout.gelLeft() - 8 - GelScale.approximateTextWidth(ladderBand.label()));
            text.setY(layout.bandY(ladderBand.size()) + 4);
            text.setMouseTransparent(true);
            pane.getChildren().add(text);
        }
    }

    private static void addBand(Pane pane, BandRect rect, double centerY, double intensity, Color color, String popupText) {
        Rectangle band = new Rectangle(rect.x(), rect.y(), rect.width(), rect.height());
        band.setFill(BandPaint.withOpacity(color, intensity));
        pane.getChildren().add(band);
        if (popupText != null && !popupText.isBlank()) {
            Rectangle hitBox = new Rectangle(rect.x() - 5, centerY - 7, rect.width() + 10, 14);
            hitBox.setFill(BandPaint.HIT_BOX);
            hitBox.setCursor(Cursor.HAND);
            Popup popup = popup(popupText);
            hitBox.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
                band.setStroke(BandPaint.HOVER);
                band.setStrokeWidth(1.25);
                popup.show(hitBox.getScene().getWindow(), event.getScreenX() + 14, event.getScreenY() + 12);
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
                popup.setX(event.getScreenX() + 14);
                popup.setY(event.getScreenY() + 12);
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
                band.setStroke(null);
                popup.hide();
            });
            pane.getChildren().add(hitBox);
        }
    }

    private static Popup popup(String text) {
        Label label = new Label(text);
        label.setStyle("""
                -fx-background-color: rgba(255,255,255,0.96);
                -fx-background-radius: 5;
                -fx-border-color: #444444;
                -fx-border-radius: 5;
                -fx-padding: 7 9 7 9;
                -fx-font-family: Verdana;
                -fx-font-size: 11px;
                -fx-text-fill: #111111;
                """);
        Popup popup = new Popup();
        popup.getContent().add(label);
        return popup;
    }

    private static void addRotatedLabel(Pane pane, String label, double centerX, double y) {
        Text text = new Text(label);
        text.setFont(Font.font("Verdana", GelLayout.PAGE_LABEL_FONT_SIZE));
        text.setFill(Color.BLACK);
        text.setX(centerX);
        text.setY(y);
        text.getTransforms().add(new Rotate(45, centerX, y));
        text.setMouseTransparent(true);
        pane.getChildren().add(text);
    }

    private static void chooseAndSavePng(PageModel model, Pane pane) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName(GelImages.pageFileName(model.pageIndex, "png"));
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            savePaneAsPng(pane, selected.toPath());
        }
    }

    private static void chooseAndSaveSvg(PageModel model, boolean colorByGene) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page SVG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG image", "*.svg"));
        chooser.setInitialFileName(GelImages.pageFileName(model.pageIndex, "svg"));
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            saveSvg(model, colorByGene, selected.toPath());
        }
    }

    private static void chooseAndSavePdf(PageModel model, Pane pane) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF document", "*.pdf"));
        chooser.setInitialFileName(GelImages.pageFileName(model.pageIndex, "pdf"));
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            savePdf(pane, selected.toPath());
        }
    }

    private static void savePaneAsPng(Pane pane, Path outputFile) {
        try {
            GelImages.writePng(GelImages.snapshot(pane, pane.getPrefWidth(), pane.getPrefHeight(), false), outputFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save PNG: " + outputFile, e);
        }
    }

    private static void savePdf(Pane pane, Path outputFile) {
        try {
            GelImages.writePdf(GelImages.snapshot(pane, pane.getPrefWidth(), pane.getPrefHeight(), true), outputFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save PDF: " + outputFile, e);
        }
    }

    private static void saveSvg(PageModel model, boolean colorByGene, Path outputFile) {
        try {
            List<String> samples = model.currentSamples();
            GelImages.writeText(GelSvg.page(model.layout(samples), samples, model.lanes, model.geneColors, colorByGene), outputFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save SVG: " + outputFile, e);
        }
    }

    private static final class PageModel {
        private final Scene ownerScene;
        private final LinkedHashMap<String, List<GelBand>> lanes;
        private final GelPages pages;
        private final Map<String, Color> geneColors;
        private int pageIndex;
        private double zoom = 1.0;

        private PageModel(Scene ownerScene, LinkedHashMap<String, List<GelBand>> lanes, int samplesPerGel) {
            this.ownerScene = ownerScene;
            this.lanes = lanes;
            this.pages = new GelPages(List.copyOf(lanes.keySet()), samplesPerGel);
            this.geneColors = GelColors.assignGeneColors(lanes);
        }

        private int totalPages() {
            return pages.totalPages();
        }

        private String currentRangeText() {
            return pages.rangeText(pageIndex);
        }

        private List<String> currentSamples() {
            return pages.samples(pageIndex);
        }

        private GelLayout layout(List<String> samples) {
            return GelLayout.page(ownerScene.getWidth(), ownerScene.getHeight(), samples);
        }
    }
}
