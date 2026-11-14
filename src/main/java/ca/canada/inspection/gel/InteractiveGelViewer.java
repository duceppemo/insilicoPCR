package ca.canada.inspection.gel;

import ca.canada.inspection.gel.LaneBand.BandRect;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Interactive synthetic gel viewer backed by JavaFX scene-graph nodes. */
public final class InteractiveGelViewer {
    private static final double BAND_HOVER_TARGET_HEIGHT = 14.0;
    private static final double BAND_HEIGHT = 2.0;
    private static final double MIN_ZOOM = 0.45;
    private static final double MAX_ZOOM = 3.0;

    private InteractiveGelViewer() {
    }

    public static void show(Scene ownerScene, Path consolidatedReport, Collection<String> sampleNames) {
        if (ownerScene == null) {
            throw new IllegalArgumentException("Unable to draw synthetic gel because the application scene is not available.");
        }

        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(consolidatedReport, sampleNames);
        if (lanes.isEmpty()) {
            throw new IllegalStateException("No amplicons were found in consolidated report: " + consolidatedReport);
        }

        GelView gelView = buildGel(ownerScene, lanes);
        Pane gelPane = gelView.pane();
        Group zoomGroup = new Group(gelPane);
        // Only offered as the name to save under: opening the viewer writes nothing, so it opens
        // on a folder that cannot be written to and leaves the --gel picture as it is.
        Path automaticOutput = GelImages.defaultOutput(consolidatedReport);

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

        VBox legend = GelNodes.legend(gelView.geneColors());
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
            matchLabel.setText(result.searching() ? GelText.matches(result.matchCount()) : "");
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
        stage.setTitle("Synthetic Gel - " + consolidatedReport.getFileName());
        Scene scene = new Scene(root, windowWidth, windowHeight);
        installKeyboardShortcuts(scene, searchField, selectedBands, selectedLabel, gelView.bands(), zoomIn, zoomOut, zoomReset, savePng, saveSvg, savePdf);
        stage.setScene(scene);
        stage.setMinWidth(Math.min(900, windowWidth));
        stage.setMinHeight(Math.min(650, windowHeight));
        stage.show();
    }

    private static GelView buildGel(Scene ownerScene, LinkedHashMap<String, List<GelBand>> lanes) {
        GelLayout layout = GelLayout.window(ownerScene.getWidth(), ownerScene.getHeight(), lanes.keySet());
        List<String> laneNames = List.copyOf(lanes.keySet());
        Map<String, Color> geneColors = GelColors.assignGeneColors(lanes);
        List<LaneBand> laneBands = new ArrayList<>();
        // Behind each sample lane, a highlight shown while one of its bands is hovered or selected.
        List<Rectangle> laneHighlights = new ArrayList<>();
        for (int lane = 1; lane <= laneNames.size(); lane++) {
            laneBands.addAll(LaneBand.window(layout, lane, laneNames.get(lane - 1),
                    lanes.get(laneNames.get(lane - 1)), BAND_HEIGHT));
            Rectangle highlight = new Rectangle(layout.laneX(lane), layout.gelTop(), layout.laneWidth(), layout.gelHeight());
            highlight.setFill(Color.TRANSPARENT);
            highlight.setMouseTransparent(true);
            laneHighlights.add(highlight);
        }

        List<BandNode> bandNodes = new ArrayList<>();
        GelScene scene = GelScene.of(layout, laneNames, laneBands, band -> BandPaint.BAND, GelScene.LADDER_FONT_SIZE);
        Pane pane = GelNodes.pane(scene, laneHighlights, band -> {
            BandNode node = bandNode(band, laneHighlights.get(band.lane().lane() - 1),
                    geneColors.getOrDefault(band.lane().gene(), Color.BLACK));
            bandNodes.add(node);
            return node.group;
        });
        pane.setStyle("-fx-background-color: white;");
        return new GelView(pane, bandNodes, geneColors, laneNames, layout, laneBands);
    }

    private static BandNode bandNode(GelScene.Band sceneBand, Rectangle laneHighlight, Color geneColor) {
        GelNodes.BandShape shape = GelNodes.band(sceneBand);
        BandRect rect = sceneBand.rect();
        double centerY = sceneBand.lane().centerY();
        String popupText = sceneBand.title();

        Rectangle hitBox = new Rectangle(rect.x() - 5.0, centerY - (BAND_HOVER_TARGET_HEIGHT / 2.0), rect.width() + 10.0,
                BAND_HOVER_TARGET_HEIGHT);
        hitBox.setFill(BandPaint.HIT_BOX);
        hitBox.setStroke(Color.TRANSPARENT);
        hitBox.setCursor(Cursor.HAND);

        Group group = new Group(shape.group(), hitBox);
        group.setPickOnBounds(false);
        BandNode bandNode = new BandNode(group, hitBox, shape.body(), shape.halo(), laneHighlight,
                sceneBand.lane().bands(), sceneBand.intensity(), popupText, rect, geneColor);

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
        String normalized = GelText.normalizeQuery(query);
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
        chooser.setInitialFileName(GelImages.withExtension(automaticOutput, "svg"));
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
        chooser.setInitialFileName(GelImages.withExtension(automaticOutput, "pdf"));
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
            GelImages.writePng(GelImages.snapshot(pane, pane.getPrefWidth(), pane.getPrefHeight(), false), outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to save synthetic gel image: " + outputFile, e);
        }
    }

    private static void saveSvg(GelView gelView, Path outputFile) {
        // As shown: black bands, or each in its gene's colour.
        boolean colorByGene = !gelView.bands().isEmpty() && gelView.bands().getFirst().colorByGene;
        GelScene scene = GelScene.of(gelView.layout(), gelView.laneNames(), gelView.laneBands(),
                band -> colorByGene ? gelView.geneColors().getOrDefault(band.gene(), Color.BLACK) : BandPaint.BAND,
                GelScene.LADDER_FONT_SIZE);
        try {
            GelImages.writeText(GelSvg.of(scene), outputFile);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to save synthetic gel SVG: " + outputFile, e);
        }
    }

    private static void savePdf(Pane pane, Path outputFile) {
        try {
            GelImages.writePdf(GelImages.snapshot(pane, pane.getPrefWidth(), pane.getPrefHeight(), true), outputFile);
            showInfo("PDF saved", "Saved synthetic gel PDF to:\n" + outputFile.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to save synthetic gel PDF: " + outputFile, e);
        }
    }

    private static void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private record GelView(Pane pane,
                           List<BandNode> bands,
                           Map<String, Color> geneColors,
                           List<String> laneNames,
                           GelLayout layout,
                           List<LaneBand> laneBands) {
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
        private final BandRect rect;
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
                         BandRect rect,
                         Color geneColor) {
            this.group = group;
            this.hitBox = hitBox;
            this.band = band;
            this.halo = halo;
            this.laneHighlight = laneHighlight;
            this.bands = bands;
            this.intensity = intensity;
            this.popupText = popupText;
            this.rect = rect;
            this.geneColor = geneColor;
            this.searchText = GelText.searchText(bands);
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
            BandPaint.State state = BandPaint.State.of(hovered, selected, searching, searchMatch, colorByGene, geneColor, intensity);
            group.setOpacity(state.opacity());
            if (laneHighlight != null) {
                laneHighlight.setFill(state.laneFill());
            }
            band.setFill(state.bandFill());
            band.setStroke(state.stroke());
            if (state.stroke() != null) {
                band.setStrokeWidth(state.strokeWidth());
            }
            halo.setFill(state.halo());
        }

        private String sampleName() {
            return GelText.sampleName(bands);
        }

        private String geneNames() {
            return GelText.geneNames(bands);
        }

        private String ampliconSizes() {
            return GelText.ampliconSizes(bands);
        }

        private String detailText() {
            return GelText.details(popupText, bands);
        }
    }

    private static void updateSelectedLabel(Set<BandNode> selectedBands, Label selectedLabel) {
        selectedLabel.setText(GelText.selection(selectedBands.stream().map(band -> band.bands).toList()));
    }
}
