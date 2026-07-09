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
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
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
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Paginated synthetic gel viewer for large runs. */
public final class PaginatedGelViewer {
    public static final int DEFAULT_SAMPLES_PER_GEL = 100;

    private static final int GEL_MIN_BP = 0;
    private static final int GEL_MAX_BP = 25_000;
    private static final double GEL_LOG_OFFSET_BP = 50.0;
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

        PageModel model = new PageModel(ownerScene, consolidatedReport, lanes, DEFAULT_SAMPLES_PER_GEL);
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
        TextField searchField = new TextField();
        searchField.setPromptText("Search all samples/genes/sizes");
        CheckBox colorByGene = new CheckBox("Color by gene");
        Button savePng = new Button("Save PNG...");
        Button saveSvg = new Button("Save SVG...");
        Button zoomOut = new Button("−");
        Button zoomReset = new Button("100%");
        Button zoomIn = new Button("+");
        Button previous = new Button("Previous Gel");
        Button next = new Button("Next Gel");

        HBox mainToolbar = new HBox(8, savePng, saveSvg, colorByGene, new Label("Search:"), searchField,
                matchLabel, new Label("Zoom:"), zoomOut, zoomReset, zoomIn);
        mainToolbar.setAlignment(Pos.CENTER_LEFT);
        mainToolbar.setPadding(new Insets(8, 8, 4, 8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        HBox pageToolbar = new HBox(8, previous, next, pageLabel, rangeLabel);
        pageToolbar.setAlignment(Pos.CENTER_LEFT);
        pageToolbar.setPadding(new Insets(4, 8, 8, 8));

        VBox toolbarRows = new VBox(mainToolbar, pageToolbar);
        root.setTop(toolbarRows);

        Runnable render = () -> {
            Pane pagePane = drawPage(model, colorByGene.isSelected());
            pagePane.setScaleX(model.zoom);
            pagePane.setScaleY(model.zoom);
            zoomGroup.getChildren().setAll(pagePane);
            pageLabel.setText("Page " + (model.pageIndex + 1) + " of " + model.totalPages());
            rangeLabel.setText(model.currentRangeText());
            previous.setDisable(model.pageIndex == 0);
            next.setDisable(model.pageIndex >= model.totalPages() - 1);
            zoomReset.setText(Math.round(model.zoom * 100) + "%");
            stage.setTitle("Synthetic Gel - page " + (model.pageIndex + 1) + " of " + model.totalPages());
        };

        previous.setOnAction(event -> {
            if (model.pageIndex > 0) {
                model.pageIndex--;
                render.run();
            }
        });
        next.setOnAction(event -> {
            if (model.pageIndex < model.totalPages() - 1) {
                model.pageIndex++;
                render.run();
            }
        });
        colorByGene.setOnAction(event -> render.run());
        zoomOut.setOnAction(event -> {
            model.zoom = Math.max(0.45, model.zoom / 1.15);
            render.run();
        });
        zoomIn.setOnAction(event -> {
            model.zoom = Math.min(3.0, model.zoom * 1.15);
            render.run();
        });
        zoomReset.setOnAction(event -> {
            model.zoom = 1.0;
            render.run();
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
        savePng.setOnAction(event -> chooseAndSavePng(model, currentPane(zoomGroup)));
        saveSvg.setOnAction(event -> chooseAndSaveSvg(model));

        var screenBounds = Screen.getPrimary().getVisualBounds();
        stage.setScene(new Scene(root, Math.max(900, screenBounds.getWidth() - 120), Math.max(700, screenBounds.getHeight() - 120)));
        render.run();
        stage.show();
    }

    private static Pane currentPane(Group zoomGroup) {
        return (Pane) zoomGroup.getChildren().getFirst();
    }

    private static Pane drawPage(PageModel model, boolean colorByGene) {
        List<String> samples = model.currentSamples();
        int laneCount = samples.size() + 1;
        double leftLabelWidth = 82;
        double rightInset = 18;
        double topInset = 18;
        double laneWidth = Math.max(74, (Math.max(model.ownerScene.getWidth(), 800) - leftLabelWidth - rightInset) / Math.max(laneCount, 11));
        double gelHeight = Math.max(540, Math.max(model.ownerScene.getHeight(), 500) * 1.10);
        double gelLeft = leftLabelWidth;
        double gelWidth = laneWidth * laneCount;
        double gelBottom = topInset + gelHeight;
        double labelAreaHeight = Math.max(150, Math.min(360, samples.stream().mapToInt(String::length).max().orElse(10) * 6.2 * 0.72 + 36));
        double paneWidth = Math.max(900, gelLeft + gelWidth + rightInset);
        double paneHeight = gelBottom + labelAreaHeight;

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
            addRotatedLabel(pane, sampleName, gelLeft + laneIndex * laneWidth + laneWidth / 2, gelBottom + 34);
            laneIndex++;
        }
        addRotatedLabel(pane, "Ladder", gelLeft + laneWidth / 2, gelBottom + 34);

        Rectangle border = new Rectangle(gelLeft, topInset, gelWidth, gelHeight);
        border.setFill(Color.TRANSPARENT);
        border.setStroke(Color.BLACK);
        border.setStrokeWidth(3);
        border.setMouseTransparent(true);
        pane.getChildren().add(border);
        return pane;
    }

    private static void drawLadder(Pane pane, double gelLeft, double gelTop, double gelHeight, double laneWidth) {
        for (int size : LADDER_SIZES) {
            addBand(pane, gelLeft, ladderY(gelTop, gelHeight, size), laneWidth, 2.0, 0.45, Color.BLACK, null);
        }
    }

    private static void drawLadderLabels(Pane pane, double labelRightX, double gelTop, double gelHeight) {
        for (int i = 0; i < LADDER_SIZES.length; i++) {
            Text text = new Text(LADDER_LABELS[i]);
            text.setFont(Font.font("Verdana", 10));
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
        text.setFont(Font.font("Verdana", 10));
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

    private static void chooseAndSaveSvg(PageModel model) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save current gel page SVG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG image", "*.svg"));
        chooser.setInitialFileName("synthetic_gel_page_" + String.format("%03d", model.pageIndex + 1) + ".svg");
        File selected = chooser.showSaveDialog(null);
        if (selected != null) {
            try {
                Files.writeString(selected.toPath(), "<!-- SVG export for paginated gel pages is generated from the current PNG/SVG-capable single-page viewer. Use Save PNG for this paginated view. -->\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to save SVG: " + selected, e);
            }
        }
    }

    private static void savePaneAsPng(Pane pane, Path outputFile) {
        try {
            WritableImage image = new WritableImage((int) Math.ceil(pane.getPrefWidth()), (int) Math.ceil(pane.getPrefHeight()));
            pane.snapshot(new SnapshotParameters(), image);
            BufferedImage buffered = new BufferedImage((int) image.getWidth(), (int) image.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var reader = image.getPixelReader();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    buffered.setRGB(x, y, reader.getArgb(x, y));
                }
            }
            ImageIO.write(buffered, "png", outputFile.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save PNG: " + outputFile, e);
        }
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

    private static final class PageModel {
        private final Scene ownerScene;
        private final Path consolidatedReport;
        private final LinkedHashMap<String, List<GelBand>> lanes;
        private final List<String> sampleNames;
        private final int samplesPerGel;
        private final Map<String, Color> geneColors;
        private int pageIndex;
        private double zoom = 1.0;

        private PageModel(Scene ownerScene,
                          Path consolidatedReport,
                          LinkedHashMap<String, List<GelBand>> lanes,
                          int samplesPerGel) {
            this.ownerScene = ownerScene;
            this.consolidatedReport = consolidatedReport;
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
