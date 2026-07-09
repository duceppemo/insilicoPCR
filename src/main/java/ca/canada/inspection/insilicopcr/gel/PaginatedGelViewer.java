package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.stream.Collectors;

/** Paginated synthetic gel viewer for large runs. */
public final class PaginatedGelViewer {
    public static final int DEFAULT_SAMPLES_PER_GEL = 100;

    private static final int GEL_MIN_BP = 0;
    private static final int GEL_MAX_BP = 25_000;
    private static final double GEL_LOG_OFFSET_BP = 50.0;
    private static final double LABEL_FONT_SIZE = 12.0;
    private static final double LADDER_FONT_SIZE = 10.0;
    private static final double ROTATED_LABEL_MARGIN = 36.0;
    private static final int[] LADDER_SIZES = {20_000, 10_000, 7_000, 5_000, 4_000, 3_000, 2_000, 1_500, 1_000, 700, 500, 400, 300, 200, 100};
    private static final String[] LADDER_LABELS = {"20kb", "10kb", "7kb", "5kb", "4kb", "3kb", "2kb", "1.5kb", "1kb", "700", "500", "400", "300", "200", "100"};
    private static final Color[] GENE_PALETTE = {
            Color.rgb(31, 119, 180), Color.rgb(214, 39, 40), Color.rgb(44, 160, 44),
            Color.rgb(255, 127, 14), Color.rgb(148, 103, 189), Color.rgb(23, 190, 207),
            Color.rgb(140, 86, 75), Color.rgb(227, 119, 194), Color.rgb(188, 189, 34),
            Color.rgb(127, 127, 127)
    };

    private PaginatedGelViewer() {
    }

    public static void show(Scene ownerScene,
                            Path consolidatedReport,
                            HashMap<String, Sample> sampleDict) {
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(consolidatedReport, sampleDict);
        if (lanes.size() <= DEFAULT_SAMPLES_PER_GEL) {
            InteractiveGelViewer.show(ownerScene, consolidatedReport, sampleDict);
            return;
        }

        PageModel model = new PageModel(ownerScene, lanes, DEFAULT_SAMPLES_PER_GEL);
        showPagedGel(model);
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

        VBox toolbarRows = new VBox(searchToolbar, exportToolbar, pageToolbar);
        root.setTop(toolbarRows);

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
                statusLabel.setText(model.sampleNames.size() + " samples | Page " + (model.pageIndex + 1) + "/" + model.totalPages()
                        + " | " + model.samplesPerGel + " samples/page | Zoom " + Math.round(model.zoom * 100) + "%");
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
            PageSearchResult result = model.pageContaining(searchField.getText());
            if (result.pageIndex() >= 0) {
                model.pageIndex = result.pageIndex();
                matchLabel.setText(result.matches() + " match" + (result.matches() == 1 ? "" : "es"));
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
        int laneCount = samples.size() + 1;
        double leftLabelWidth = 82;
        double topInset = 18;
        double longestLabelWidth = longestLabelWidth(samples);
        double rotatedLabelFootprint = rotatedLabelFootprint(longestLabelWidth);
        double rightInset = Math.max(48, rotatedLabelFootprint + ROTATED_LABEL_MARGIN);
        double labelAreaHeight = Math.max(190, Math.min(460, rotatedLabelFootprint + ROTATED_LABEL_MARGIN));
        double laneWidth = Math.max(74, (Math.max(model.ownerScene.getWidth(), 800) - leftLabelWidth - rightInset) / Math.max(laneCount, 11));
        double gelHeight = Math.max(540, Math.max(model.ownerScene.getHeight(), 500) * 1.10);
        double gelLeft = leftLabelWidth;
        double gelWidth = laneWidth * laneCount;
        double gelBottom = topInset + gelHeight;
        double paneWidth = Math.max(900, gelLeft + gelWidth + rightInset);
        double paneHeight = gelBottom + labelAreaHeight;
        double labelY = gelBottom + 42;

        Pane pane = new Pane();
        pane.setPrefSize(paneWidth, paneHeight);
        pane.setMinSize(paneWidth, paneHeight);
        pane.setStyle("-fx-background-color: white;");

        Rectangle background = new Rectangle(gelLeft, topInset, gelWidth, gelHeight);
        background.setFill(Color.rgb(238, 238, 238));
        pane.getChildren().add(background);

        for (int i = 0; i < laneCount; i++) {
            double x = gelLeft + i * laneWidth;
            Rectangle lane = new Rectangle(x, topInset, laneWidth, gelHeight);
            lane.setFill(i % 2 == 0 ? Color.rgb(244, 244, 244) : Color.rgb(232, 232, 232));
            pane.getChildren().add(lane);
            Rectangle separator = new Rectangle(x, topInset, 3, gelHeight);
            separator.setFill(Color.WHITE);
            pane.getChildren().add(separator);
        }

        drawLadder(pane, gelLeft, topInset, gelHeight, laneWidth);
        drawLadderLabels(pane, gelLeft - 8, topInset, gelHeight);

        int laneIndex = 1;
        for (String sampleName : samples) {
            double x = gelLeft + laneIndex * laneWidth;
            Map<Integer, List<GelBand>> bySize = new TreeMap<>(GelReportReader.groupByRoundedSize(model.lanes.getOrDefault(sampleName, List.of())));
            for (Map.Entry<Integer, List<GelBand>> entry : bySize.entrySet()) {
                int roundedSize = entry.getKey();
                List<GelBand> bands = entry.getValue();
                double y = ladderY(topInset, gelHeight, roundedSize);
                double intensity = Math.min(1.0, 0.45 + Math.max(0, bands.size() - 1) * 0.12);
                Color color = colorByGene ? model.geneColors.getOrDefault(bands.getFirst().geneName(), Color.BLACK) : Color.BLACK;
                addBand(pane, x, y, laneWidth, 2.0 + intensity, intensity, color, tooltipText(bands));
            }
            addRotatedLabel(pane, sampleName, gelLeft + laneIndex * laneWidth + laneWidth / 2, labelY);
            laneIndex++;
        }
        addRotatedLabel(pane, "Ladder", gelLeft + laneWidth / 2, labelY);

        Rectangle border = new Rectangle(gelLeft, topInset, gelWidth, gelHeight);
        border.setFill(Color.TRANSPARENT);
        border.setStroke(Color.BLACK);
        border.setStrokeWidth(3);
        border.setMouseTransparent(true);
        pane.getChildren().add(border);
        return pane;
    }

    private static double longestLabelWidth(List<String> samples) {
        return Math.max("Ladder".length(), samples.stream().mapToInt(String::length).max().orElse(10)) * LABEL_FONT_SIZE * 0.62;
    }

    private static double rotatedLabelFootprint(double labelWidth) {
        return (labelWidth + LABEL_FONT_SIZE) / Math.sqrt(2.0);
    }

    private static void drawLadder(Pane pane, double gelLeft, double gelTop, double gelHeight, double laneWidth) {
        for (int size : LADDER_SIZES) {
            addBand(pane, gelLeft, ladderY(gelTop, gelHeight, size), laneWidth, 2.0, 0.45, Color.BLACK, null);
        }
    }

    private static void drawLadderLabels(Pane pane, double labelRightX, double gelTop, double gelHeight) {
        for (int i = 0; i < LADDER_SIZES.length; i++) {
            Text text = new Text(LADDER_LABELS[i]);
            text.setFont(Font.font("Verdana", LADDER_FONT_SIZE));
            text.setFill(Color.BLACK);
            text.setX(labelRightX - text.getText().length() * 6.1);
            text.setY(ladderY(gelTop, gelHeight, LADDER_SIZES[i]) + 4);
            text.setMouseTransparent(true);
            pane.getChildren().add(text);
        }
    }

    private static void addBand(Pane pane, double laneX, double centerY, double laneWidth, double height,
                                double intensity, Color color, String popupText) {
        double width = Math.max(4, laneWidth * 0.66);
        double x = laneX + (laneWidth - width) / 2.0;
        Rectangle band = new Rectangle(x, centerY - height / 2.0, width, height);
        band.setFill(Color.color(color.getRed(), color.getGreen(), color.getBlue(), intensity));
        pane.getChildren().add(band);
        if (popupText != null && !popupText.isBlank()) {
            Rectangle hitBox = new Rectangle(x - 5, centerY - 7, width + 10, 14);
            hitBox.setFill(Color.rgb(255, 255, 255, 0.01));
            hitBox.setCursor(Cursor.HAND);
            Popup popup = popup(popupText);
            hitBox.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
                band.setStroke(Color.rgb(30, 144, 255));
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
        text.setFont(Font.font("Verdana", LABEL_FONT_SIZE));
        text.setFill(Color.BLACK);
        text.setX(centerX);
        text.setY(y);
        text.getTransforms().add(new Rotate(45, centerX, y));
        text.setMouseTransparent(true);
        pane.getChildren().add(text);
    }

    private static String tooltipText(List<GelBand> bands) {
        if (bands.size() == 1) {
            GelBand band = bands.getFirst();
            return "Sample: " + band.sampleName() + '\n'
                    + "Gene: " + band.geneName() + '\n'
                    + "Amplicon size: " + band.ampliconSize() + " bp";
        }
        return "Sample: " + bands.getFirst().sampleName() + '\n'
                + "Co-migrating amplicons: " + bands.size() + '\n'
                + bands.stream().map(b -> b.geneName() + " — " + b.ampliconSize() + " bp").collect(Collectors.joining("\n"));
    }

    private static void chooseAndSavePng(PageModel model, Pane pane) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page PNG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName("synthetic_gel_page_" + String.format("%03d", model.pageIndex + 1) + ".png");
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            savePaneAsPng(pane, selected.toPath());
        }
    }

    private static void chooseAndSaveSvg(PageModel model, boolean colorByGene) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page SVG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG image", "*.svg"));
        chooser.setInitialFileName("synthetic_gel_page_" + String.format("%03d", model.pageIndex + 1) + ".svg");
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            saveSvg(model, colorByGene, selected.toPath());
        }
    }

    private static void chooseAndSavePdf(PageModel model, Pane pane) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF document", "*.pdf"));
        chooser.setInitialFileName("synthetic_gel_page_" + String.format("%03d", model.pageIndex + 1) + ".pdf");
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            savePdf(pane, selected.toPath());
        }
    }

    private static void savePaneAsPng(Pane pane, Path outputFile) {
        try {
            WritableImage image = new WritableImage((int) Math.ceil(pane.getPrefWidth()), (int) Math.ceil(pane.getPrefHeight()));
            pane.snapshot(new SnapshotParameters(), image);
            ImageIO.write(toBufferedImage(image, false), "png", outputFile.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save PNG: " + outputFile, e);
        }
    }

    private static void savePdf(Pane pane, Path outputFile) {
        try {
            WritableImage image = new WritableImage((int) Math.ceil(pane.getPrefWidth()), (int) Math.ceil(pane.getPrefHeight()));
            pane.snapshot(new SnapshotParameters(), image);
            Files.write(outputFile, pdfWithImage(toBufferedImage(image, true)));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save PDF: " + outputFile, e);
        }
    }

    private static void saveSvg(PageModel model, boolean colorByGene, Path outputFile) {
        try {
            StringBuilder svg = new StringBuilder();
            List<String> samples = model.currentSamples();
            int laneCount = samples.size() + 1;
            double leftLabelWidth = 82;
            double topInset = 18;
            double longestLabelWidth = longestLabelWidth(samples);
            double rotatedLabelFootprint = rotatedLabelFootprint(longestLabelWidth);
            double rightInset = Math.max(48, rotatedLabelFootprint + ROTATED_LABEL_MARGIN);
            double labelAreaHeight = Math.max(190, Math.min(460, rotatedLabelFootprint + ROTATED_LABEL_MARGIN));
            double laneWidth = Math.max(74, (Math.max(model.ownerScene.getWidth(), 800) - leftLabelWidth - rightInset) / Math.max(laneCount, 11));
            double gelHeight = Math.max(540, Math.max(model.ownerScene.getHeight(), 500) * 1.10);
            double gelLeft = leftLabelWidth;
            double gelWidth = laneWidth * laneCount;
            double gelBottom = topInset + gelHeight;
            double width = Math.max(900, gelLeft + gelWidth + rightInset);
            double height = gelBottom + labelAreaHeight;
            double labelY = gelBottom + 42;

            svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(format(width)).append("\" height=\"").append(format(height)).append("\" viewBox=\"0 0 ")
                    .append(format(width)).append(' ').append(format(height)).append("\">\n");
            svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            for (int i = 0; i < laneCount; i++) {
                double x = gelLeft + i * laneWidth;
                svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(topInset)).append("\" width=\"").append(format(laneWidth)).append("\" height=\"").append(format(gelHeight)).append("\" fill=\"").append(i % 2 == 0 ? "#f4f4f4" : "#e8e8e8").append("\"/>\n");
                svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(topInset)).append("\" width=\"3\" height=\"").append(format(gelHeight)).append("\" fill=\"white\"/>\n");
            }
            for (int i = 0; i < LADDER_SIZES.length; i++) {
                double y = ladderY(topInset, gelHeight, LADDER_SIZES[i]);
                svgBand(svg, gelLeft + laneWidth * 0.17, y - 1.0, laneWidth * 0.66, 2.0, Color.BLACK, 0.45, "Ladder " + LADDER_LABELS[i]);
                svgText(svg, LADDER_LABELS[i], gelLeft - 8 - LADDER_LABELS[i].length() * 6.1, y + 4, LADDER_FONT_SIZE, null);
            }
            int laneIndex = 1;
            for (String sampleName : samples) {
                double laneX = gelLeft + laneIndex * laneWidth;
                for (Map.Entry<Integer, List<GelBand>> entry : new TreeMap<>(GelReportReader.groupByRoundedSize(model.lanes.getOrDefault(sampleName, List.of()))).entrySet()) {
                    List<GelBand> bands = entry.getValue();
                    double y = ladderY(topInset, gelHeight, entry.getKey());
                    double intensity = Math.min(1.0, 0.45 + Math.max(0, bands.size() - 1) * 0.12);
                    Color color = colorByGene ? model.geneColors.getOrDefault(bands.getFirst().geneName(), Color.BLACK) : Color.BLACK;
                    svgBand(svg, laneX + laneWidth * 0.17, y - 1.0, laneWidth * 0.66, 2.0 + intensity, color, intensity, tooltipText(bands));
                }
                double cx = gelLeft + laneIndex * laneWidth + laneWidth / 2;
                svgText(svg, sampleName, cx, labelY, LABEL_FONT_SIZE,
                        "rotate(45 " + format(cx) + " " + format(labelY) + ")");
                laneIndex++;
            }
            double ladderCx = gelLeft + laneWidth / 2;
            svgText(svg, "Ladder", ladderCx, labelY, LABEL_FONT_SIZE,
                    "rotate(45 " + format(ladderCx) + " " + format(labelY) + ")");
            svg.append("  <rect x=\"").append(format(gelLeft)).append("\" y=\"").append(format(topInset)).append("\" width=\"").append(format(gelWidth)).append("\" height=\"").append(format(gelHeight)).append("\" fill=\"none\" stroke=\"black\" stroke-width=\"3\"/>\n");
            svg.append("</svg>\n");
            Files.writeString(outputFile, svg.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save SVG: " + outputFile, e);
        }
    }

    private static void svgBand(StringBuilder svg, double x, double y, double width, double height, Color color, double opacity, String title) {
        svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" width=\"").append(format(width)).append("\" height=\"").append(format(height)).append("\" fill=\"").append(toHex(color)).append("\" opacity=\"").append(format(opacity)).append("\">");
        if (title != null && !title.isBlank()) {
            svg.append("<title>").append(escapeXml(title)).append("</title>");
        }
        svg.append("</rect>\n");
    }

    private static void svgText(StringBuilder svg, String text, double x, double y, double fontSize, String transform) {
        svg.append("  <text x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" font-family=\"Verdana\" font-size=\"").append(format(fontSize)).append("\" fill=\"black\"");
        if (transform != null) {
            svg.append(" transform=\"").append(transform).append("\"");
        }
        svg.append(">").append(escapeXml(text)).append("</text>\n");
    }

    private static BufferedImage toBufferedImage(WritableImage image, boolean whiteBackground) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, whiteBackground ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = reader.getArgb(x, y);
                if (whiteBackground && ((argb >>> 24) & 0xff) < 255) {
                    argb = 0xffffffff;
                }
                buffered.setRGB(x, y, argb);
            }
        }
        return buffered;
    }

    private static byte[] pdfWithImage(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] imageData = compressedRgb(image);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        writeAscii(pdf, "%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height + "] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>\nendobj\n");
        String content = "q\n" + width + " 0 0 " + height + " 0 0 cm\n/Im0 Do\nQ\n";
        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);
        offsets.add(pdf.size());
        writeAscii(pdf, "4 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        pdf.write(contentBytes);
        writeAscii(pdf, "endstream\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "5 0 obj\n<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + imageData.length + " >>\nstream\n");
        pdf.write(imageData);
        writeAscii(pdf, "\nendstream\nendobj\n");
        int xref = pdf.size();
        writeAscii(pdf, "xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        writeAscii(pdf, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private static byte[] compressedRgb(BufferedImage image) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(image.getWidth() * image.getHeight() * 3);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(raw)) {
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = image.getRGB(x, y);
                    compressed.write((rgb >> 16) & 0xff);
                    compressed.write((rgb >> 8) & 0xff);
                    compressed.write(rgb & 0xff);
                }
            }
        }
        return raw.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static double ladderY(double gelTop, double gelHeight, int basePairs) {
        return gelTop + gelHeight - (gelHeight * normalizedGelPosition(basePairs));
    }

    private static double normalizedGelPosition(int basePairs) {
        double clampedBasePairs = Math.max(GEL_MIN_BP, Math.min(GEL_MAX_BP, basePairs));
        double minLog = Math.log10(GEL_MIN_BP + GEL_LOG_OFFSET_BP);
        double maxLog = Math.log10(GEL_MAX_BP + GEL_LOG_OFFSET_BP);
        double valueLog = Math.log10(clampedBasePairs + GEL_LOG_OFFSET_BP);
        return Math.clamp((valueLog - minLog) / (maxLog - minLog), 0.0, 1.0);
    }

    private static Map<String, Color> assignGeneColors(LinkedHashMap<String, List<GelBand>> lanes) {
        Map<String, Color> colors = new LinkedHashMap<>();
        for (List<GelBand> bands : lanes.values()) {
            for (GelBand band : bands) {
                colors.computeIfAbsent(band.geneName(), gene -> GENE_PALETTE[colors.size() % GENE_PALETTE.length]);
            }
        }
        return colors;
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String toHex(Color color) {
        int r = (int) Math.round(color.getRed() * 255.0);
        int g = (int) Math.round(color.getGreen() * 255.0);
        int b = (int) Math.round(color.getBlue() * 255.0);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static final class PageModel {
        private final Scene ownerScene;
        private final LinkedHashMap<String, List<GelBand>> lanes;
        private final List<String> sampleNames;
        private final int samplesPerGel;
        private final Map<String, Color> geneColors;
        private int pageIndex;
        private double zoom = 1.0;

        private PageModel(Scene ownerScene,
                          LinkedHashMap<String, List<GelBand>> lanes,
                          int samplesPerGel) {
            this.ownerScene = ownerScene;
            this.lanes = lanes;
            this.sampleNames = new ArrayList<>(lanes.keySet());
            this.samplesPerGel = samplesPerGel;
            this.geneColors = assignGeneColors(lanes);
        }

        private int totalPages() {
            return Math.max(1, (int) Math.ceil((double) sampleNames.size() / samplesPerGel));
        }

        private String currentRangeText() {
            int start = startIndex();
            int end = endIndex();
            return "Samples " + (start + 1) + "–" + end + " of " + sampleNames.size()
                    + " (" + sampleNames.get(start) + " ... " + sampleNames.get(end - 1) + ")";
        }

        private PageSearchResult pageContaining(String query) {
            if (query == null || query.isBlank()) {
                return new PageSearchResult(-1, 0);
            }
            String normalized = query.strip().toLowerCase();
            int first = -1;
            int count = 0;
            for (int i = 0; i < sampleNames.size(); i++) {
                String sampleName = sampleNames.get(i);
                boolean matches = sampleName.toLowerCase().contains(normalized)
                        || lanes.getOrDefault(sampleName, List.of()).stream().anyMatch(b ->
                        b.geneName().toLowerCase().contains(normalized)
                                || Integer.toString(b.ampliconSize()).contains(normalized));
                if (matches) {
                    count++;
                    if (first < 0) {
                        first = i / samplesPerGel;
                    }
                }
            }
            return new PageSearchResult(first, count);
        }

        private List<String> currentSamples() {
            return sampleNames.subList(startIndex(), endIndex());
        }

        private int startIndex() {
            return pageIndex * samplesPerGel;
        }

        private int endIndex() {
            return Math.min(sampleNames.size(), startIndex() + samplesPerGel);
        }
    }

    private record PageSearchResult(int pageIndex, int matches) {
    }
}
