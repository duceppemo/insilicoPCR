package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.DeflaterOutputStream;
import java.util.stream.Collectors;

/** Interactive synthetic gel viewer backed by JavaFX scene-graph nodes. */
public final class InteractiveGelViewer {
    private static final int GEL_MIN_BP = 0;
    private static final int GEL_MAX_BP = 25_000;
    private static final double GEL_LOG_OFFSET_BP = 50.0;
    private static final double BAND_HOVER_TARGET_HEIGHT = 14.0;
    private static final double MIN_ZOOM = 0.45;
    private static final double MAX_ZOOM = 3.0;
    private static final Color[] GENE_PALETTE = {
            Color.rgb(31, 119, 180), Color.rgb(214, 39, 40), Color.rgb(44, 160, 44),
            Color.rgb(255, 127, 14), Color.rgb(148, 103, 189), Color.rgb(23, 190, 207),
            Color.rgb(140, 86, 75), Color.rgb(227, 119, 194), Color.rgb(188, 189, 34),
            Color.rgb(127, 127, 127)
    };
    private static final int[] LADDER_SIZES = {20_000, 10_000, 7_000, 5_000, 4_000, 3_000, 2_000, 1_500, 1_000, 700, 500, 400, 300, 200, 100};
    private static final String[] LADDER_LABELS = {"20kb", "10kb", "7kb", "5kb", "4kb", "3kb", "2kb", "1.5kb", "1kb", "700", "500", "400", "300", "200", "100"};

    private InteractiveGelViewer() {
    }

    public static void show(Scene ownerScene, Path consolidatedReport, HashMap<String, Sample> sampleDict) {
        if (ownerScene == null) {
            throw new IllegalArgumentException("Unable to draw synthetic gel because the application scene is not available.");
        }

        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(consolidatedReport, sampleDict);
        if (lanes.isEmpty()) {
            throw new IllegalStateException("No amplicons were found in consolidated report: " + consolidatedReport);
        }

        GelView gelView = buildGel(ownerScene, lanes);
        Pane gelPane = gelView.pane();
        Group zoomGroup = new Group(gelPane);
        Path automaticOutput = defaultSyntheticGelOutput(consolidatedReport);
        savePaneAsPng(gelPane, automaticOutput);

        Button savePngButton = new Button("Save PNG...");
        Runnable savePng = () -> chooseAndSavePng(gelPane, automaticOutput);
        savePngButton.setOnAction(event -> savePng.run());

        Button saveSvgButton = new Button("Save SVG...");
        Runnable saveSvg = () -> chooseAndSaveSvg(gelView, automaticOutput);
        saveSvgButton.setOnAction(event -> saveSvg.run());

        Button savePdfButton = new Button("Save PDF...");
        Runnable savePdf = () -> chooseAndSavePdf(gelView, automaticOutput);
        savePdfButton.setOnAction(event -> savePdf.run());

        Button zoomOutButton = new Button("−");
        Button zoomResetButton = new Button("100%");
        Button zoomInButton = new Button("+");
        Label selectedLabel = new Label("No band selected");
        Label matchLabel = new Label();
        TextField searchField = new TextField();
        searchField.setPromptText("Search sample, gene, or size");
        Button clearSearchButton = new Button("Clear");
        CheckBox colorByGene = new CheckBox("Color by gene");

        HBox toolbar = new HBox(8, savePngButton, saveSvgButton, savePdfButton, colorByGene,
                new Label("Search:"), searchField, clearSearchButton, matchLabel,
                new Label("Zoom:"), zoomOutButton, zoomResetButton, zoomInButton, selectedLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(zoomGroup);
        scrollPane.setFitToHeight(false);
        scrollPane.setFitToWidth(false);
        scrollPane.setPannable(true);

        VBox legend = buildLegend(gelView.geneColors());
        legend.setVisible(false);
        legend.setManaged(false);

        ZoomState zoomState = new ZoomState();
        Runnable refreshZoomButton = () -> zoomResetButton.setText(Math.round(zoomState.zoom * 100) + "%");
        Runnable zoomIn = () -> setZoom(gelPane, zoomState, zoomState.zoom * 1.15, refreshZoomButton);
        Runnable zoomOut = () -> setZoom(gelPane, zoomState, zoomState.zoom / 1.15, refreshZoomButton);
        Runnable zoomReset = () -> setZoom(gelPane, zoomState, 1.0, refreshZoomButton);

        zoomInButton.setOnAction(event -> zoomIn.run());
        zoomOutButton.setOnAction(event -> zoomOut.run());
        zoomResetButton.setOnAction(event -> zoomReset.run());
        scrollPane.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.isControlDown()) {
                if (event.getDeltaY() > 0) {
                    zoomIn.run();
                } else if (event.getDeltaY() < 0) {
                    zoomOut.run();
                }
                event.consume();
            }
        });

        Set<BandNode> selectedBands = new HashSet<>();
        for (BandNode bandNode : gelView.bands()) {
            bandNode.installSelectionHandler(selectedBands, selectedLabel);
        }
        installBoxSelection(gelPane, gelView.bands(), selectedBands, selectedLabel);

        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            SearchResult result = applySearch(gelView.bands(), newValue);
            matchLabel.setText(result.searching() ? result.matchCount() + " match" + (result.matchCount() == 1 ? "" : "es") : "");
            if (result.firstMatch() != null) {
                scrollToBand(scrollPane, zoomGroup, result.firstMatch());
            }
        });
        clearSearchButton.setOnAction(event -> searchField.clear());
        colorByGene.selectedProperty().addListener((observable, oldValue, selected) -> {
            legend.setVisible(selected);
            legend.setManaged(selected);
            for (BandNode bandNode : gelView.bands()) {
                bandNode.colorByGene = selected;
                bandNode.updateVisual();
            }
        });

        BorderPane root = new BorderPane(scrollPane);
        root.setTop(toolbar);
        root.setRight(legend);

        var screenBounds = Screen.getPrimary().getVisualBounds();
        double preferredWidth = gelPane.getPrefWidth() + 32;
        double preferredHeight = gelPane.getPrefHeight() + 88;
        double windowWidth = Math.min(preferredWidth, Math.max(900, screenBounds.getWidth() - 80));
        double windowHeight = Math.min(preferredHeight, Math.max(700, screenBounds.getHeight() - 80));

        Stage stage = new Stage();
        stage.setTitle("Synthetic Gel - " + consolidatedReport.getFileName() + " - saved to " + automaticOutput.getFileName());
        Scene scene = new Scene(root, windowWidth, windowHeight);
        installKeyboardShortcuts(scene, searchField, selectedBands, selectedLabel, gelView.bands(), zoomIn, zoomOut, zoomReset, savePng, saveSvg, savePdf);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(900, windowWidth));
        stage.setMinHeight(Math.min(650, windowHeight));
        stage.show();
    }

    private static GelView buildGel(Scene ownerScene, LinkedHashMap<String, List<GelBand>> lanes) {
        int laneCount = lanes.size() + 1;
        double visibleWidth = Math.max(ownerScene.getWidth(), 800);
        double visibleHeight = Math.max(ownerScene.getHeight(), 500);
        double leftLabelWidth = 82;
        double rightInset = 18;
        double topInset = 18;
        double gelHeight = Math.max(540, visibleHeight * 1.10);
        double labelAreaHeight = Math.max(150, Math.min(360, longestLaneLabelWidth(lanes) * 0.72 + 36));
        double laneWidth = Math.max(74, (visibleWidth - leftLabelWidth - rightInset) / Math.max(laneCount, 11));
        double gelLeft = leftLabelWidth;
        double gelWidth = laneWidth * laneCount;
        double gelBottom = topInset + gelHeight;
        double paneWidth = Math.max(visibleWidth, gelLeft + gelWidth + rightInset);
        double paneHeight = gelBottom + labelAreaHeight;
        double bandHeight = 2.0;

        Pane pane = new Pane();
        pane.setPrefSize(paneWidth, paneHeight);
        pane.setMinSize(paneWidth, paneHeight);
        pane.setMaxSize(paneWidth, paneHeight);
        pane.setStyle("-fx-background-color: white;");

        List<String> laneNames = new ArrayList<>(lanes.keySet());
        Map<String, Rectangle> laneHighlights = drawGelBackground(pane, lanes, gelLeft, topInset, gelWidth, gelHeight, laneCount, laneWidth);
        drawLadder(pane, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
        drawLadderLabels(pane, topInset, gelHeight, gelLeft - 8);
        Map<String, Color> geneColors = assignGeneColors(lanes);
        List<BandNode> bandNodes = drawSampleBands(pane, lanes, laneHighlights, geneColors, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
        drawGelBorder(pane, gelLeft, topInset, gelWidth, gelHeight);
        drawLaneLabels(pane, lanes, gelLeft, gelBottom + 34, laneWidth);

        return new GelView(pane, bandNodes, geneColors, laneNames, gelLeft, topInset, gelWidth, gelHeight, laneWidth, gelBottom + 34);
    }

    private static double longestLaneLabelWidth(LinkedHashMap<String, List<GelBand>> lanes) {
        return lanes.keySet().stream().mapToDouble(sample -> Math.max("Ladder".length(), sample.length()) * 6.2).max().orElse(60);
    }

    private static Map<String, Rectangle> drawGelBackground(Pane pane,
                                                            LinkedHashMap<String, List<GelBand>> lanes,
                                                            double x,
                                                            double y,
                                                            double width,
                                                            double height,
                                                            int laneCount,
                                                            double laneWidth) {
        Map<String, Rectangle> laneHighlights = new LinkedHashMap<>();
        Rectangle background = new Rectangle(x, y, width, height);
        background.setFill(Color.rgb(238, 238, 238));
        pane.getChildren().add(background);

        for (int i = 0; i < laneCount; i++) {
            double laneX = x + (i * laneWidth);
            Rectangle lane = new Rectangle(laneX, y, laneWidth, height);
            lane.setFill(i % 2 == 0 ? Color.rgb(244, 244, 244) : Color.rgb(232, 232, 232));
            lane.setMouseTransparent(true);
            Rectangle separator = new Rectangle(laneX, y, 3.0, height);
            separator.setFill(Color.WHITE);
            separator.setMouseTransparent(true);
            pane.getChildren().addAll(lane, separator);
        }

        int laneIndex = 1;
        for (String sampleName : lanes.keySet()) {
            Rectangle highlight = new Rectangle(x + (laneIndex * laneWidth), y, laneWidth, height);
            highlight.setFill(Color.TRANSPARENT);
            highlight.setMouseTransparent(true);
            pane.getChildren().add(highlight);
            laneHighlights.put(sampleName, highlight);
            laneIndex++;
        }

        for (int row = 24; row < height; row += 42) {
            Rectangle lightTexture = new Rectangle(x, y + row, width, 1.0);
            lightTexture.setFill(Color.rgb(246, 246, 246, 0.45));
            lightTexture.setMouseTransparent(true);
            Rectangle darkTexture = new Rectangle(x, y + row + 2, width, 1.0);
            darkTexture.setFill(Color.rgb(222, 222, 222, 0.25));
            darkTexture.setMouseTransparent(true);
            pane.getChildren().addAll(lightTexture, darkTexture);
        }
        return laneHighlights;
    }

    private static void drawGelBorder(Pane pane, double x, double y, double width, double height) {
        Rectangle border = new Rectangle(x, y, width, height);
        border.setFill(Color.TRANSPARENT);
        border.setStroke(Color.BLACK);
        border.setStrokeWidth(3.0);
        border.setMouseTransparent(true);
        pane.getChildren().add(border);
    }

    private static void drawLaneLabels(Pane pane, LinkedHashMap<String, List<GelBand>> lanes, double gelLeft, double labelY, double laneWidth) {
        addRotatedLaneLabel(pane, "Ladder", gelLeft + (laneWidth / 2), labelY);
        int laneIndex = 1;
        for (String sampleName : lanes.keySet()) {
            addRotatedLaneLabel(pane, sampleName, gelLeft + (laneIndex * laneWidth) + (laneWidth / 2), labelY);
            laneIndex++;
        }
    }

    private static void addRotatedLaneLabel(Pane pane, String label, double centerX, double y) {
        Text text = new Text(label);
        text.setFont(Font.font("Verdana", 10));
        text.setFill(Color.BLACK);
        text.setX(centerX);
        text.setY(y);
        text.getTransforms().add(new Rotate(45, centerX, y));
        text.setMouseTransparent(true);
        pane.getChildren().add(text);
    }

    private static void drawLadder(Pane pane, double gelLeft, double gelTop, double gelHeight, double laneWidth, double bandHeight) {
        for (int size : LADDER_SIZES) {
            addGelBandShape(pane, List.of(), null, Map.of(), gelLeft, ladderY(gelTop, gelHeight, size), laneWidth, bandHeight, 0.45, null);
        }
    }

    private static void drawLadderLabels(Pane pane, double gelTop, double gelHeight, double labelRightX) {
        for (int i = 0; i < LADDER_SIZES.length; i++) {
            Text text = new Text(LADDER_LABELS[i]);
            text.setFont(Font.font("Verdana", 10));
            text.setFill(Color.BLACK);
            text.setX(labelRightX - approximateTextWidth(LADDER_LABELS[i]));
            text.setY(ladderY(gelTop, gelHeight, LADDER_SIZES[i]) + 4);
            text.setMouseTransparent(true);
            pane.getChildren().add(text);
        }
    }

    private static double approximateTextWidth(String text) {
        return text.length() * 6.1;
    }

    private static List<BandNode> drawSampleBands(Pane pane,
                                                  LinkedHashMap<String, List<GelBand>> lanes,
                                                  Map<String, Rectangle> laneHighlights,
                                                  Map<String, Color> geneColors,
                                                  double gelLeft,
                                                  double gelTop,
                                                  double gelHeight,
                                                  double laneWidth,
                                                  double baseBandHeight) {
        List<BandNode> bandNodes = new ArrayList<>();
        int laneIndex = 1;
        for (Map.Entry<String, List<GelBand>> laneEntry : lanes.entrySet()) {
            String sampleName = laneEntry.getKey();
            double x = gelLeft + (laneIndex * laneWidth);
            Rectangle laneHighlight = laneHighlights.get(sampleName);
            Map<Integer, List<GelBand>> bandsBySize = new TreeMap<>(GelReportReader.groupByRoundedSize(laneEntry.getValue()));

            for (Map.Entry<Integer, List<GelBand>> bandEntry : bandsBySize.entrySet()) {
                int roundedSize = bandEntry.getKey();
                List<GelBand> bands = bandEntry.getValue();
                int count = bands.size();
                double laneVariation = deterministicRange(sampleName, 0.90, 1.10);
                double verticalJitter = deterministicRange(sampleName + ':' + roundedSize, -0.45, 0.45);
                double intensity = Math.min(1.0, bandIntensity(roundedSize, count) * laneVariation);
                BandNode bandNode = addGelBandShape(
                        pane,
                        bands,
                        laneHighlight,
                        geneColors,
                        x,
                        ladderY(gelTop, gelHeight, roundedSize) + verticalJitter,
                        laneWidth,
                        baseBandHeight + (intensity * 1.2),
                        intensity,
                        tooltipText(bands)
                );
                bandNodes.add(bandNode);
            }
            laneIndex++;
        }
        return bandNodes;
    }

    private static BandNode addGelBandShape(Pane pane,
                                            List<GelBand> bands,
                                            Rectangle laneHighlight,
                                            Map<String, Color> geneColors,
                                            double x,
                                            double centerY,
                                            double laneWidth,
                                            double bandHeight,
                                            double intensity,
                                            String popupText) {
        double bandWidth = Math.max(4, laneWidth * 0.66);
        double bandX = x + ((laneWidth - bandWidth) / 2.0);
        double bandY = centerY - (bandHeight / 2.0);
        Color geneColor = geneColors.getOrDefault(primaryGene(bands), Color.BLACK);

        Rectangle halo = new Rectangle(bandX - 1.5, bandY - 2.0, bandWidth + 3.0, bandHeight + 4.0);
        halo.setFill(Color.rgb(25, 25, 25, intensity * 0.20));
        halo.setMouseTransparent(true);

        Rectangle band = new Rectangle(bandX, bandY, bandWidth, bandHeight);
        band.setFill(Color.rgb(8, 8, 8, intensity));
        band.setMouseTransparent(true);

        Rectangle highlight = new Rectangle(bandX, bandY + 0.5, bandWidth, Math.max(0.75, bandHeight * 0.22));
        highlight.setFill(Color.rgb(255, 255, 255, Math.min(0.18, intensity * 0.14)));
        highlight.setMouseTransparent(true);

        Rectangle shadow = new Rectangle(bandX, bandY + bandHeight - 0.75, bandWidth, 0.75);
        shadow.setFill(Color.rgb(0, 0, 0, Math.min(0.32, intensity * 0.24)));
        shadow.setMouseTransparent(true);

        Rectangle hitBox = new Rectangle(bandX - 5.0, centerY - (BAND_HOVER_TARGET_HEIGHT / 2.0), bandWidth + 10.0, BAND_HOVER_TARGET_HEIGHT);
        hitBox.setFill(Color.rgb(255, 255, 255, 0.01));
        hitBox.setStroke(Color.TRANSPARENT);
        hitBox.setCursor(Cursor.HAND);

        Group group = new Group(halo, band, highlight, shadow, hitBox);
        group.setPickOnBounds(false);
        BandNode bandNode = new BandNode(group, hitBox, band, halo, laneHighlight,
                bands == null ? List.of() : bands, intensity, popupText, bandX, bandY, bandWidth, bandHeight, geneColor);

        if (popupText != null && !popupText.isBlank()) {
            Popup popup = createMetadataPopup(popupText);
            ContextMenu menu = createContextMenu(bandNode);
            hitBox.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
                showPopup(popup, hitBox, event);
                bandNode.hovered = true;
                bandNode.updateVisual();
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_MOVED, event -> showPopup(popup, hitBox, event));
            hitBox.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
                popup.hide();
                bandNode.hovered = false;
                bandNode.updateVisual();
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    menu.show(hitBox, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            hitBox.setOnContextMenuRequested(event -> menu.show(hitBox, event.getScreenX(), event.getScreenY()));
        } else {
            hitBox.setMouseTransparent(true);
        }

        pane.getChildren().add(group);
        return bandNode;
    }

    private static void installBoxSelection(Pane pane, List<BandNode> bands, Set<BandNode> selectedBands, Label selectedLabel) {
        Rectangle selection = new Rectangle();
        selection.setFill(Color.rgb(30, 144, 255, 0.12));
        selection.setStroke(Color.rgb(30, 144, 255, 0.85));
        selection.setStrokeWidth(1.0);
        selection.setMouseTransparent(true);
        selection.setVisible(false);
        pane.getChildren().add(selection);

        double[] startX = new double[1];
        double[] startY = new double[1];
        boolean[] selecting = new boolean[1];

        pane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isBandHit(event.getTarget(), bands)) {
                return;
            }
            startX[0] = event.getX();
            startY[0] = event.getY();
            selection.setX(startX[0]);
            selection.setY(startY[0]);
            selection.setWidth(0);
            selection.setHeight(0);
            selection.setVisible(true);
            selecting[0] = true;
            event.consume();
        });

        pane.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!selecting[0]) {
                return;
            }
            double x = Math.min(startX[0], event.getX());
            double y = Math.min(startY[0], event.getY());
            selection.setX(x);
            selection.setY(y);
            selection.setWidth(Math.abs(event.getX() - startX[0]));
            selection.setHeight(Math.abs(event.getY() - startY[0]));
            event.consume();
        });

        pane.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!selecting[0]) {
                return;
            }
            selecting[0] = false;
            selection.setVisible(false);
            Bounds box = selection.getBoundsInParent();
            if (box.getWidth() < 3 || box.getHeight() < 3) {
                event.consume();
                return;
            }
            if (!event.isControlDown() && !event.isShiftDown()) {
                for (BandNode selectedBand : List.copyOf(selectedBands)) {
                    selectedBand.selected = false;
                    selectedBand.updateVisual();
                }
                selectedBands.clear();
            }
            for (BandNode band : bands) {
                if (band.searchMatch && box.intersects(band.hitBox.getBoundsInParent())) {
                    band.selected = true;
                    band.updateVisual();
                    selectedBands.add(band);
                }
            }
            updateSelectedLabel(selectedBands, selectedLabel);
            event.consume();
        });
    }

    private static boolean isBandHit(Object target, List<BandNode> bands) {
        for (BandNode band : bands) {
            if (target == band.hitBox) {
                return true;
            }
        }
        return false;
    }

    private static ContextMenu createContextMenu(BandNode bandNode) {
        MenuItem showDetails = new MenuItem("Show band details");
        showDetails.setOnAction(event -> showBandDetails(bandNode));
        MenuItem copySample = new MenuItem("Copy sample name");
        copySample.setOnAction(event -> copyToClipboard(bandNode.sampleName()));
        MenuItem copyGenes = new MenuItem("Copy gene name(s)");
        copyGenes.setOnAction(event -> copyToClipboard(bandNode.geneNames()));
        MenuItem copySizes = new MenuItem("Copy amplicon size(s)");
        copySizes.setOnAction(event -> copyToClipboard(bandNode.ampliconSizes()));
        MenuItem copyAll = new MenuItem("Copy band details");
        copyAll.setOnAction(event -> copyToClipboard(bandNode.detailText()));
        return new ContextMenu(showDetails, copySample, copyGenes, copySizes, copyAll);
    }

    private static Popup createMetadataPopup(String text) {
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
        popup.setAutoHide(false);
        popup.setHideOnEscape(true);
        popup.getContent().add(label);
        return popup;
    }

    private static void showPopup(Popup popup, Rectangle owner, MouseEvent event) {
        if (owner.getScene() == null || owner.getScene().getWindow() == null) {
            return;
        }
        double x = event.getScreenX() + 14;
        double y = event.getScreenY() + 12;
        if (!popup.isShowing()) {
            popup.show(owner.getScene().getWindow(), x, y);
        } else {
            popup.setX(x);
            popup.setY(y);
        }
    }

    private static void showBandDetails(BandNode bandNode) {
        TextArea details = new TextArea(bandNode.detailText());
        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefColumnCount(70);
        details.setPrefRowCount(Math.max(8, Math.min(24, bandNode.bands.size() + 4)));
        Button copyButton = new Button("Copy details");
        copyButton.setOnAction(event -> copyToClipboard(details.getText()));
        VBox root = new VBox(8, details, copyButton);
        root.setPadding(new Insets(10));
        Stage stage = new Stage();
        stage.setTitle("Band details - " + bandNode.sampleName());
        stage.setScene(new Scene(root));
        stage.show();
    }

    private static void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static SearchResult applySearch(List<BandNode> bands, String query) {
        String normalized = query == null ? "" : query.strip().toLowerCase();
        boolean searching = !normalized.isBlank();
        BandNode firstMatch = null;
        int matchCount = 0;
        for (BandNode bandNode : bands) {
            bandNode.searchMatch = !searching || bandNode.searchText.contains(normalized);
            bandNode.searching = searching;
            bandNode.updateVisual();
            if (searching && bandNode.searchMatch) {
                matchCount++;
                if (firstMatch == null) {
                    firstMatch = bandNode;
                }
            }
        }
        return new SearchResult(searching, matchCount, firstMatch);
    }

    private static void setZoom(Pane gelPane, ZoomState zoomState, double requestedZoom, Runnable afterChange) {
        zoomState.zoom = Math.clamp(requestedZoom, MIN_ZOOM, MAX_ZOOM);
        gelPane.setScaleX(zoomState.zoom);
        gelPane.setScaleY(zoomState.zoom);
        if (afterChange != null) {
            afterChange.run();
        }
    }

    private static void scrollToBand(ScrollPane scrollPane, Group zoomGroup, BandNode bandNode) {
        Bounds contentBounds = zoomGroup.getLayoutBounds();
        Bounds bandBounds = bandNode.hitBox.localToScene(bandNode.hitBox.getBoundsInLocal());
        Bounds scrollBounds = scrollPane.localToScene(scrollPane.getBoundsInLocal());
        if (contentBounds.getWidth() <= 0 || contentBounds.getHeight() <= 0) {
            return;
        }
        double centerX = bandBounds.getCenterX() - scrollBounds.getMinX();
        double centerY = bandBounds.getCenterY() - scrollBounds.getMinY();
        double hTarget = scrollPane.getHvalue() + ((centerX - scrollBounds.getWidth() / 2.0) / contentBounds.getWidth());
        double vTarget = scrollPane.getVvalue() + ((centerY - scrollBounds.getHeight() / 2.0) / contentBounds.getHeight());
        scrollPane.setHvalue(Math.clamp(hTarget, 0.0, 1.0));
        scrollPane.setVvalue(Math.clamp(vTarget, 0.0, 1.0));
    }

    private static void installKeyboardShortcuts(Scene scene,
                                                 TextField searchField,
                                                 Set<BandNode> selectedBands,
                                                 Label selectedLabel,
                                                 List<BandNode> bands,
                                                 Runnable zoomIn,
                                                 Runnable zoomOut,
                                                 Runnable zoomReset,
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
                zoomReset.run();
                event.consume();
            } else if (event.isControlDown() && (event.getCode() == KeyCode.EQUALS || event.getCode() == KeyCode.ADD)) {
                zoomIn.run();
                event.consume();
            } else if (event.isControlDown() && (event.getCode() == KeyCode.MINUS || event.getCode() == KeyCode.SUBTRACT)) {
                zoomOut.run();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.A) {
                selectedBands.clear();
                for (BandNode band : bands) {
                    if (band.searchMatch) {
                        band.selected = true;
                        band.updateVisual();
                        selectedBands.add(band);
                    }
                }
                updateSelectedLabel(selectedBands, selectedLabel);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                for (BandNode selectedBand : List.copyOf(selectedBands)) {
                    selectedBand.selected = false;
                    selectedBand.updateVisual();
                }
                selectedBands.clear();
                updateSelectedLabel(selectedBands, selectedLabel);
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

    private static Map<String, Color> assignGeneColors(LinkedHashMap<String, List<GelBand>> lanes) {
        Map<String, Color> colors = new LinkedHashMap<>();
        for (List<GelBand> bands : lanes.values()) {
            for (GelBand band : bands) {
                colors.computeIfAbsent(band.geneName(), gene -> GENE_PALETTE[colors.size() % GENE_PALETTE.length]);
            }
        }
        return colors;
    }

    private static String primaryGene(List<GelBand> bands) {
        return bands == null || bands.isEmpty() ? "" : bands.getFirst().geneName();
    }

    private static String tooltipText(List<GelBand> bands) {
        if (bands.size() == 1) {
            GelBand band = bands.getFirst();
            return "Sample: " + band.sampleName() + '\n'
                    + "Gene: " + band.geneName() + '\n'
                    + "Amplicon size: " + band.ampliconSize() + " bp";
        }
        String sampleName = bands.getFirst().sampleName();
        String details = bands.stream()
                .map(gelBand -> gelBand.geneName() + " — " + gelBand.ampliconSize() + " bp")
                .collect(Collectors.joining("\n"));
        return "Sample: " + sampleName + '\n'
                + "Co-migrating amplicons: " + bands.size() + '\n'
                + details;
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

    private static double bandIntensity(int basePairs, int count) {
        double sizeFactor = 0.35 + (0.30 * (1.0 - normalizedGelPosition(basePairs)));
        double countFactor = Math.min(0.35, Math.max(0, count - 1) * 0.12);
        return Math.min(1.0, Math.max(0.30, sizeFactor + countFactor));
    }

    private static double deterministicRange(String key, double minimum, double maximum) {
        int hash = key == null ? 0 : key.hashCode();
        double unit = ((hash & 0x7fffffff) % 10_000) / 9_999.0;
        return minimum + ((maximum - minimum) * unit);
    }

    private static Path defaultSyntheticGelOutput(Path consolidatedReport) {
        Path reportDir = consolidatedReport.getParent();
        Path outputDir = reportDir != null && reportDir.getParent() != null ? reportDir.getParent() : reportDir;
        if (outputDir == null) {
            outputDir = Path.of(".");
        }
        String baseName = consolidatedReport.getFileName().toString()
                .replaceFirst("\\.tsv$", "")
                .replaceAll("[^A-Za-z0-9._-]+", "_");
        return outputDir.resolve("synthetic_gel_" + baseName + ".png");
    }

    private static void chooseAndSavePng(Pane gelPane, Path automaticOutput) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Synthetic Gel Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName(automaticOutput.getFileName().toString());
        setInitialDirectory(chooser, automaticOutput);
        File selectedFile = chooser.showSaveDialog(null);
        if (selectedFile != null) {
            savePaneAsPng(gelPane, selectedFile.toPath());
        }
    }

    private static void chooseAndSaveSvg(GelView gelView, Path automaticOutput) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Synthetic Gel SVG");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SVG image", "*.svg"));
        chooser.setInitialFileName(automaticOutput.getFileName().toString().replaceFirst("\\.png$", ".svg"));
        setInitialDirectory(chooser, automaticOutput);
        File selectedFile = chooser.showSaveDialog(null);
        if (selectedFile != null) {
            saveSvg(gelView, selectedFile.toPath());
        }
    }

    private static void chooseAndSavePdf(GelView gelView, Path automaticOutput) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Synthetic Gel PDF");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF document", "*.pdf"));
        chooser.setInitialFileName(automaticOutput.getFileName().toString().replaceFirst("\\.png$", ".pdf"));
        setInitialDirectory(chooser, automaticOutput);
        File selectedFile = chooser.showSaveDialog(null);
        if (selectedFile != null) {
            savePdf(gelView.pane(), selectedFile.toPath());
        }
    }

    private static void setInitialDirectory(FileChooser chooser, Path output) {
        Path parent = output.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            chooser.setInitialDirectory(parent.toFile());
        }
    }

    private static void savePaneAsPng(Pane pane, Path outputFile) {
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            WritableImage image = new WritableImage((int) Math.ceil(pane.getPrefWidth()), (int) Math.ceil(pane.getPrefHeight()));
            pane.snapshot(new SnapshotParameters(), image);
            ImageIO.write(toBufferedImage(image, false), "png", outputFile.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save synthetic gel image: " + outputFile, e);
        }
    }

    private static void saveSvg(GelView gelView, Path outputFile) {
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder svg = new StringBuilder();
            double width = gelView.pane().getPrefWidth();
            double height = gelView.pane().getPrefHeight();
            svg.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(format(width)).append("\" height=\"").append(format(height)).append("\" viewBox=\"0 0 ")
                    .append(format(width)).append(' ').append(format(height)).append("\">\n");
            svg.append("  <rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            svg.append("  <rect x=\"").append(format(gelView.gelLeft())).append("\" y=\"").append(format(gelView.gelTop())).append("\" width=\"")
                    .append(format(gelView.gelWidth())).append("\" height=\"").append(format(gelView.gelHeight())).append("\" fill=\"#eeeeee\" stroke=\"black\" stroke-width=\"3\"/>\n");
            for (int lane = 0; lane <= gelView.laneNames().size(); lane++) {
                double x = gelView.gelLeft() + (lane * gelView.laneWidth());
                String fill = lane % 2 == 0 ? "#f4f4f4" : "#e8e8e8";
                svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(gelView.gelTop())).append("\" width=\"")
                        .append(format(gelView.laneWidth())).append("\" height=\"").append(format(gelView.gelHeight())).append("\" fill=\"").append(fill).append("\"/>\n");
                svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(gelView.gelTop())).append("\" width=\"3\" height=\"")
                        .append(format(gelView.gelHeight())).append("\" fill=\"white\"/>\n");
            }
            for (int i = 0; i < LADDER_SIZES.length; i++) {
                double y = ladderY(gelView.gelTop(), gelView.gelHeight(), LADDER_SIZES[i]);
                double x = gelView.gelLeft() + (gelView.laneWidth() * 0.17);
                svgBand(svg, x, y - 1.0, gelView.laneWidth() * 0.66, 2.0, Color.BLACK, 0.45, "Ladder " + LADDER_LABELS[i]);
                svgText(svg, LADDER_LABELS[i], gelView.gelLeft() - 8 - approximateTextWidth(LADDER_LABELS[i]), y + 4, 10, Color.BLACK, null);
            }
            svg.append("  <rect x=\"").append(format(gelView.gelLeft())).append("\" y=\"").append(format(gelView.gelTop())).append("\" width=\"")
                    .append(format(gelView.gelWidth())).append("\" height=\"").append(format(gelView.gelHeight())).append("\" fill=\"none\" stroke=\"black\" stroke-width=\"3\"/>\n");
            svgText(svg, "Ladder", gelView.gelLeft() + (gelView.laneWidth() / 2), gelView.labelY(), 10, Color.BLACK,
                    "rotate(45 " + format(gelView.gelLeft() + (gelView.laneWidth() / 2)) + " " + format(gelView.labelY()) + ")");
            for (int i = 0; i < gelView.laneNames().size(); i++) {
                double cx = gelView.gelLeft() + ((i + 1) * gelView.laneWidth()) + (gelView.laneWidth() / 2);
                svgText(svg, gelView.laneNames().get(i), cx, gelView.labelY(), 10, Color.BLACK,
                        "rotate(45 " + format(cx) + " " + format(gelView.labelY()) + ")");
            }
            for (BandNode band : gelView.bands()) {
                svgBand(svg, band.bandX, band.bandY, band.bandWidth, Math.max(1.2, band.bandHeight),
                        band.colorByGene ? band.geneColor : Color.BLACK, Math.max(0.35, band.intensity), band.popupText);
            }
            svg.append("</svg>\n");
            Files.writeString(outputFile, svg.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save synthetic gel SVG: " + outputFile, e);
        }
    }

    private static void svgBand(StringBuilder svg, double x, double y, double width, double height, Color color, double opacity, String title) {
        svg.append("  <rect x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" width=\"")
                .append(format(width)).append("\" height=\"").append(format(height)).append("\" fill=\"")
                .append(toHex(color)).append("\" opacity=\"").append(format(opacity)).append("\">");
        if (title != null && !title.isBlank()) {
            svg.append("<title>").append(escapeXml(title)).append("</title>");
        }
        svg.append("</rect>\n");
    }

    private static void svgText(StringBuilder svg, String text, double x, double y, int fontSize, Color color, String transform) {
        svg.append("  <text x=\"").append(format(x)).append("\" y=\"").append(format(y)).append("\" font-family=\"Verdana\" font-size=\"")
                .append(fontSize).append("\" fill=\"").append(toHex(color)).append("\"");
        if (transform != null) {
            svg.append(" transform=\"").append(transform).append("\"");
        }
        svg.append(">").append(escapeXml(text)).append("</text>\n");
    }

    private static void savePdf(Pane pane, Path outputFile) {
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            WritableImage image = new WritableImage((int) Math.ceil(pane.getPrefWidth()), (int) Math.ceil(pane.getPrefHeight()));
            pane.snapshot(new SnapshotParameters(), image);
            BufferedImage buffered = toBufferedImage(image, true);
            Files.write(outputFile, pdfWithImage(buffered));
            showInfo("PDF saved", "Saved synthetic gel PDF to:\n" + outputFile.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save synthetic gel PDF: " + outputFile, e);
        }
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

    private static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
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

    private record GelView(Pane pane,
                           List<BandNode> bands,
                           Map<String, Color> geneColors,
                           List<String> laneNames,
                           double gelLeft,
                           double gelTop,
                           double gelWidth,
                           double gelHeight,
                           double laneWidth,
                           double labelY) {
    }

    private record SearchResult(boolean searching, int matchCount, BandNode firstMatch) {
    }

    private static final class ZoomState {
        private double zoom = 1.0;
    }

    private static final class BandNode {
        private final Group group;
        private final Rectangle hitBox;
        private final Rectangle band;
        private final Rectangle halo;
        private final Rectangle laneHighlight;
        private final List<GelBand> bands;
        private final double intensity;
        private final String popupText;
        private final String searchText;
        private final double bandX;
        private final double bandY;
        private final double bandWidth;
        private final double bandHeight;
        private final Color geneColor;
        private boolean hovered;
        private boolean selected;
        private boolean searchMatch = true;
        private boolean searching;
        private boolean colorByGene;

        private BandNode(Group group,
                         Rectangle hitBox,
                         Rectangle band,
                         Rectangle halo,
                         Rectangle laneHighlight,
                         List<GelBand> bands,
                         double intensity,
                         String popupText,
                         double bandX,
                         double bandY,
                         double bandWidth,
                         double bandHeight,
                         Color geneColor) {
            this.group = group;
            this.hitBox = hitBox;
            this.band = band;
            this.halo = halo;
            this.laneHighlight = laneHighlight;
            this.bands = bands;
            this.intensity = intensity;
            this.popupText = popupText;
            this.bandX = bandX;
            this.bandY = bandY;
            this.bandWidth = bandWidth;
            this.bandHeight = bandHeight;
            this.geneColor = geneColor;
            this.searchText = bands.stream()
                    .map(gelBand -> gelBand.sampleName() + " " + gelBand.geneName() + " " + gelBand.ampliconSize())
                    .collect(Collectors.joining(" "))
                    .toLowerCase();
            updateVisual();
        }

        private void installSelectionHandler(Set<BandNode> selectedBands, Label selectedLabel) {
            hitBox.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                if (!event.isControlDown() && !event.isShiftDown()) {
                    for (BandNode selectedBand : List.copyOf(selectedBands)) {
                        selectedBand.selected = false;
                        selectedBand.updateVisual();
                    }
                    selectedBands.clear();
                }
                selected = !selected;
                if (selected) {
                    selectedBands.add(this);
                } else {
                    selectedBands.remove(this);
                }
                updateVisual();
                updateSelectedLabel(selectedBands, selectedLabel);
                event.consume();
            });
        }

        private void updateVisual() {
            group.setOpacity(searchMatch ? 1.0 : 0.18);
            if (laneHighlight != null) {
                laneHighlight.setFill(hovered || selected ? Color.rgb(30, 144, 255, selected ? 0.18 : 0.10) : Color.TRANSPARENT);
            }
            Color baseColor = colorByGene ? geneColor : Color.rgb(8, 8, 8);
            band.setFill(Color.color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), intensity));

            if (selected) {
                band.setStroke(Color.rgb(255, 140, 0));
                band.setStrokeWidth(2.0);
                halo.setFill(Color.rgb(255, 140, 0, 0.28));
            } else if (hovered) {
                band.setStroke(Color.rgb(30, 144, 255));
                band.setStrokeWidth(1.25);
                halo.setFill(Color.rgb(30, 144, 255, 0.25));
            } else if (searching && searchMatch) {
                band.setStroke(Color.rgb(60, 180, 75));
                band.setStrokeWidth(1.25);
                halo.setFill(Color.rgb(60, 180, 75, 0.20));
            } else {
                band.setStroke(null);
                halo.setFill(Color.rgb(25, 25, 25, intensity * 0.20));
            }
        }

        private String sampleName() {
            return bands.isEmpty() ? "" : bands.getFirst().sampleName();
        }

        private String geneNames() {
            return bands.stream().map(GelBand::geneName).distinct().collect(Collectors.joining(", "));
        }

        private String ampliconSizes() {
            return bands.stream().map(gelBand -> gelBand.ampliconSize() + " bp").collect(Collectors.joining(", "));
        }

        private String detailText() {
            if (popupText == null || popupText.isBlank()) {
                return "No band details available.";
            }
            return popupText + "\n\nRows:\n" + bands.stream()
                    .map(gelBand -> gelBand.sampleName() + '\t' + gelBand.geneName() + '\t' + gelBand.ampliconSize() + " bp")
                    .collect(Collectors.joining("\n"));
        }
    }

    private static void updateSelectedLabel(Set<BandNode> selectedBands, Label selectedLabel) {
        if (selectedBands.isEmpty()) {
            selectedLabel.setText("No band selected");
        } else if (selectedBands.size() == 1) {
            BandNode selected = selectedBands.iterator().next();
            selectedLabel.setText("Selected: " + selected.sampleName() + " / " + selected.geneNames() + " / " + selected.ampliconSizes());
        } else {
            selectedLabel.setText("Selected bands: " + selectedBands.size());
        }
    }
}
