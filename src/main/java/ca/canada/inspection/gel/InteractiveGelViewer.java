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
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceBox;
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
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Interactive synthetic gel viewer backed by JavaFX scene-graph nodes. */
public final class InteractiveGelViewer {
    private static final double BAND_HOVER_TARGET_HEIGHT = 14.0;
    /** Lanes on one gel; a run of more samples is shown a gel (a page) at a time. */
    public static final int SAMPLES_PER_GEL = 100;
    private static final double MIN_ZOOM = 0.45;
    /**
     * The room the gel is laid out for, whatever the size of the window it was opened from: the
     * smallest the layout takes, the size the main window opens at.
     */
    private static final double LAYOUT_WIDTH = 800;
    private static final double LAYOUT_HEIGHT = 500;
    private static final double MAX_ZOOM = 3.0;

    private InteractiveGelViewer() {
    }

    /**
     * Opens the gel of {@code consolidatedReport}: one lane per sample of {@code sampleNames} (with or
     * without a band; only the samples with a band when it is empty), {@link #SAMPLES_PER_GEL} lanes a
     * page. Every page has the same tools: selection, search (which turns to the first page with a
     * match), colours (by gene, or by size check: target, other known product, unexpected), zoom
     * and saving. A gel is saved as {@code --gel} draws it, at its own size, without the outlines
     * and fading of selection and search, with its colours and their legend.
     */
    public static void show(Path consolidatedReport, Collection<String> sampleNames) {
        LinkedHashMap<String, List<GelBand>> lanes = GelReportReader.read(consolidatedReport, sampleNames);
        if (lanes.isEmpty()) {
            throw new IllegalStateException("No sample to draw in consolidated report: " + consolidatedReport);
        }
        GelPages pages = new GelPages(List.copyOf(lanes.keySet()), SAMPLES_PER_GEL);
        // One colour per gene for the whole run, so a gene keeps its colour from page to page.
        Map<String, Color> geneColors = GelColors.assignGeneColors(lanes);
        Current current = new Current();
        // Only offered as the name to save under: opening the viewer writes nothing, so it opens
        // on a folder that cannot be written to and leaves the --gel picture as it is.
        Path automaticOutput = GelImages.defaultOutput(consolidatedReport);
        java.util.function.Supplier<Path> pageOutput = () ->
                GelImages.pageOutput(automaticOutput, current.page, pages.totalPages());
        ChoiceBox<GelColorMode> colorMode = new ChoiceBox<>();
        colorMode.getItems().setAll(GelColorMode.values());
        colorMode.setValue(GelColorMode.BLACK);
        // The gel shown, as a picture to save.
        java.util.function.Supplier<GelScene> picture = () ->
                GelPicture.scene(pages.lanes(current.page, lanes), geneColors, colorMode.getValue());

        // Made first: the save dialogs and messages open over it, not behind it.
        Stage stage = new Stage();
        Group zoomGroup = new Group();
        Button savePngButton = new Button("Save PNG...");
        Runnable savePng = () -> chooseAndSave(stage, "PNG", "png", picture.get(), pageOutput.get());
        savePngButton.setOnAction(event -> savePng.run());

        Button saveSvgButton = new Button("Save SVG...");
        Runnable saveSvg = () -> chooseAndSave(stage, "SVG", "svg", picture.get(), pageOutput.get());
        saveSvgButton.setOnAction(event -> saveSvg.run());

        Button savePdfButton = new Button("Save PDF...");
        Runnable savePdf = () -> chooseAndSave(stage, "PDF", "pdf", picture.get(), pageOutput.get());
        savePdfButton.setOnAction(event -> savePdf.run());

        Button zoomOutButton = new Button("−");
        Button zoomResetButton = new Button("100%");
        Button zoomInButton = new Button("+");
        Label selectedLabel = new Label("No band selected");
        Label matchLabel = new Label();
        TextField searchField = new TextField();
        searchField.setPromptText("Search sample, assay, size or primer");
        Button clearSearchButton = new Button("Clear");

        HBox toolbar = new HBox(8, savePngButton, saveSvgButton, savePdfButton, new Label("Color:"), colorMode,
                new Label("Search:"), searchField, clearSearchButton, matchLabel,
                new Label("Zoom:"), zoomOutButton, zoomResetButton, zoomInButton, selectedLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));
        HBox.setHgrow(searchField, Priority.ALWAYS);

        // The page bar, shown only for a run of more samples than one gel holds.
        Button previousPage = new Button("◀ Previous gel");
        Button nextPage = new Button("Next gel ▶");
        Label pageLabel = new Label();
        HBox pageBar = new HBox(8, previousPage, pageLabel, nextPage);
        pageBar.setAlignment(Pos.CENTER_LEFT);
        pageBar.setPadding(new Insets(0, 8, 8, 8));
        boolean paged = pages.totalPages() > 1;
        pageBar.setVisible(paged);
        pageBar.setManaged(paged);

        ScrollPane scrollPane = new ScrollPane(zoomGroup);
        scrollPane.setFitToHeight(false);
        scrollPane.setFitToWidth(false);
        scrollPane.setPannable(true);

        // The legend of the colours shown, for the whole run: a gene or a size check keeps its
        // colour from gel to gel.
        List<GelBand> runBands = lanes.values().stream().flatMap(List::stream).toList();
        VBox legend = new VBox();
        Runnable showLegend = () -> {
            Map<String, Color> entries = GelColors.legend(colorMode.getValue(), runBands, geneColors);
            legend.getChildren().setAll(entries.isEmpty() ? List.of()
                    : List.of(GelNodes.legend(colorMode.getValue().legendTitle(), entries)));
        };

        ZoomState zoomState = new ZoomState();
        Runnable refreshZoomButton = () -> zoomResetButton.setText(Math.round(zoomState.zoom * 100) + "%");
        Runnable zoomIn = () -> setZoom(current.view.pane(), zoomState, zoomState.zoom * 1.15, refreshZoomButton);
        Runnable zoomOut = () -> setZoom(current.view.pane(), zoomState, zoomState.zoom / 1.15, refreshZoomButton);
        Runnable zoomReset = () -> setZoom(current.view.pane(), zoomState, 1.0, refreshZoomButton);

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

        GelSelection<BandNode> selection = new GelSelection<>((band, selected) -> {
            band.selected = selected;
            band.updateVisual();
        });
        // The matches on the gel shown, and on all gels: worked out once per search.
        java.util.function.BiConsumer<SearchResult, GelPages.Search> showSearch = (result, found) -> {
            int onAllGels = found != null ? found.matches() : result.matchCount();
            matchLabel.setText(result.searching() ? GelText.matches(result.matchCount(), onAllGels) : "");
            if (result.firstMatch() != null) {
                scrollToBand(scrollPane, zoomGroup, result.firstMatch());
            }
        };
        // Draws page current.page: its gel, with the run's tools wired to its bands.
        Runnable render = () -> {
            // Coloured, bands of one size that differ in what colours them are drawn apart.
            current.view = buildGel(pages.lanes(current.page, lanes), geneColors, colorMode.getValue());
            Pane gelPane = current.view.pane();
            gelPane.setScaleX(zoomState.zoom);
            gelPane.setScaleY(zoomState.zoom);
            zoomGroup.getChildren().setAll(gelPane);
            selection.clear();
            updateSelectedLabel(selection, selectedLabel);
            for (BandNode bandNode : current.view.bands()) {
                bandNode.installSelectionHandler(selection, selectedLabel);
                bandNode.updateVisual();
            }
            installBoxSelection(gelPane, current.view.bands(), selection, selectedLabel);
            SearchResult result = applySearch(current.view.bands(), searchField.getText());
            showSearch.accept(result, paged && result.searching()
                    ? pages.find(lanes, searchField.getText(), colorMode.getValue()) : null);
            pageLabel.setText("Gel " + (current.page + 1) + " of " + pages.totalPages() + " · "
                    + pages.rangeText(current.page));
            previousPage.setDisable(current.page == 0);
            nextPage.setDisable(current.page >= pages.totalPages() - 1);
        };
        Runnable goPrevious = () -> {
            if (current.page > 0) {
                current.page--;
                render.run();
            }
        };
        Runnable goNext = () -> {
            if (current.page < pages.totalPages() - 1) {
                current.page++;
                render.run();
            }
        };
        previousPage.setOnAction(event -> goPrevious.run());
        nextPage.setOnAction(event -> goNext.run());

        Runnable search = () -> {
            String query = searchField.getText();
            SearchResult result = applySearch(current.view.bands(), query);
            GelPages.Search found = paged && result.searching() ? pages.find(lanes, query, colorMode.getValue()) : null;
            if (found != null && result.matchCount() == 0 && found.pageIndex() >= 0 && found.pageIndex() != current.page) {
                // Nothing on this gel: turn to the first one with a match.
                current.page = found.pageIndex();
                render.run();
                return;
            }
            showSearch.accept(result, found);
        };
        // With several gels, every search looks through the whole run: it waits for a pause in the
        // typing rather than running on every key.
        javafx.animation.PauseTransition typing = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
        typing.setOnFinished(event -> search.run());
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (paged && !newValue.isEmpty()) {
                typing.playFromStart();
            } else {
                typing.stop();
                search.run();
            }
        });
        clearSearchButton.setOnAction(event -> searchField.clear());
        colorMode.valueProperty().addListener((observable, oldValue, mode) -> {
            showLegend.run();
            render.run();
        });

        render.run();
        BorderPane root = new BorderPane(scrollPane);
        root.setTop(new VBox(toolbar, pageBar));
        // A long legend (many genes) scrolls instead of running off the window.
        ScrollPane legendPane = new ScrollPane(legend);
        legendPane.setFitToWidth(true);
        legendPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        legendPane.setStyle("-fx-background-color: transparent; -fx-background: white;");
        legendPane.visibleProperty().bind(javafx.beans.binding.Bindings.isNotEmpty(legend.getChildren()));
        legendPane.managedProperty().bind(legendPane.visibleProperty());
        root.setRight(legendPane);

        var screenBounds = Screen.getPrimary().getVisualBounds();
        double preferredWidth = current.view.pane().getPrefWidth() + 32;
        double preferredHeight = current.view.pane().getPrefHeight() + 88;
        double windowWidth = Math.min(preferredWidth, Math.max(900, screenBounds.getWidth() - 80));
        double windowHeight = Math.min(preferredHeight, Math.max(700, screenBounds.getHeight() - 80));

        stage.setTitle("Synthetic Gel - " + consolidatedReport.getFileName());
        Scene scene = new Scene(root, windowWidth, windowHeight);
        installKeyboardShortcuts(scene, searchField, selection, selectedLabel, () -> current.view.bands(),
                zoomIn, zoomOut, zoomReset, savePng, saveSvg, savePdf, paged ? goPrevious : null,
                paged ? goNext : null);
        stage.setScene(scene);
        // At least as wide as the toolbar: the window of a gel of one or two lanes was narrower, and every
        // label of its toolbar was cut short ("Save F...", "Zo...", "..."), with Windows' wider font.
        root.applyCss();
        double toolbarWidth = toolbar.prefWidth(-1) + 16;
        double width = Math.min(Math.max(windowWidth, toolbarWidth), Math.max(900, screenBounds.getWidth() - 80));
        if (width > windowWidth) {
            stage.setWidth(width);
        }
        stage.setMinWidth(Math.min(900, width));
        stage.setMinHeight(Math.min(650, windowHeight));
        stage.show();
    }

    /** The page on show and its gel. */
    private static final class Current {
        private int page;
        private GelView view;
    }

    private static GelView buildGel(LinkedHashMap<String, List<GelBand>> lanes, Map<String, Color> geneColors,
                                    GelColorMode mode) {
        GelLayout layout = GelLayout.window(LAYOUT_WIDTH, LAYOUT_HEIGHT, lanes.keySet());
        List<String> laneNames = List.copyOf(lanes.keySet());
        List<LaneBand> laneBands = new ArrayList<>();
        // Behind each sample lane, a highlight shown while one of its bands is hovered or selected.
        List<LaneHighlight> laneHighlights = new ArrayList<>();
        for (int lane = 1; lane <= laneNames.size(); lane++) {
            laneBands.addAll(LaneBand.of(layout, lane, laneNames.get(lane - 1),
                    lanes.get(laneNames.get(lane - 1)), GelScene.BAND_HEIGHT, mode));
            Rectangle highlight = new Rectangle(layout.laneX(lane), layout.gelTop(), layout.laneWidth(), layout.gelHeight());
            highlight.setFill(Color.TRANSPARENT);
            highlight.setMouseTransparent(true);
            laneHighlights.add(new LaneHighlight(highlight));
        }

        List<BandNode> bandNodes = new ArrayList<>();
        // The window's own legend sits beside the gel, outside the scrolled pane. The bands are drawn
        // in their colours, as in a saved picture; hovering, selecting and searching restyle them.
        GelScene scene = GelScene.of(layout, laneNames, laneBands, GelColors.band(mode, geneColors),
                GelScene.LADDER_FONT_SIZE, null, Map.of());
        Pane pane = GelNodes.pane(scene, laneHighlights.stream().map(LaneHighlight::rectangle).toList(), band -> {
            Color color = GelColors.of(mode, band.lane().bands().getFirst(), geneColors);
            BandNode node = bandNode(band, laneHighlights.get(band.lane().lane() - 1), color);
            node.colored = color != null;
            node.updateVisual();
            bandNodes.add(node);
            return node.group;
        });
        pane.setStyle("-fx-background-color: white;");
        return new GelView(pane, bandNodes);
    }

    private static BandNode bandNode(GelScene.Band sceneBand, LaneHighlight laneHighlight, Color bandColor) {
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
                sceneBand.lane().bands(), sceneBand.intensity(), popupText, rect, bandColor);

        if (popupText != null && !popupText.isBlank()) {
            // The popup and the menu are made when first needed: a gel of 100 lanes has hundreds
            // of bands, most never pointed at.
            hitBox.addEventHandler(MouseEvent.MOUSE_ENTERED, event -> {
                showPopup(bandNode.popup(), hitBox, event);
                bandNode.hovered = true;
                bandNode.updateVisual();
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_MOVED, event -> showPopup(bandNode.popup(), hitBox, event));
            hitBox.addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
                if (bandNode.popup != null) {
                    bandNode.popup.hide();
                }
                bandNode.hovered = false;
                bandNode.updateVisual();
            });
            hitBox.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() == MouseButton.SECONDARY) {
                    bandNode.menu().show(hitBox, event.getScreenX(), event.getScreenY());
                    event.consume();
                }
            });
            hitBox.setOnContextMenuRequested(event -> bandNode.menu().show(hitBox, event.getScreenX(), event.getScreenY()));
        } else {
            hitBox.setMouseTransparent(true);
        }
        return bandNode;
    }

    private static void installBoxSelection(Pane pane, List<BandNode> bands, GelSelection<BandNode> selected,
                                            Label selectedLabel) {
        Rectangle dragBox = new Rectangle();
        dragBox.setFill(Color.rgb(30, 144, 255, 0.12));
        dragBox.setStroke(Color.rgb(30, 144, 255, 0.85));
        dragBox.setStrokeWidth(1.0);
        dragBox.setMouseTransparent(true);
        dragBox.setVisible(false);
        pane.getChildren().add(dragBox);

        double[] startX = new double[1];
        double[] startY = new double[1];
        boolean[] selecting = new boolean[1];

        pane.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || isBandHit(event.getTarget(), bands)) {
                return;
            }
            startX[0] = event.getX();
            startY[0] = event.getY();
            dragBox.setX(startX[0]);
            dragBox.setY(startY[0]);
            dragBox.setWidth(0);
            dragBox.setHeight(0);
            dragBox.setVisible(true);
            selecting[0] = true;
            event.consume();
        });

        pane.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!selecting[0]) {
                return;
            }
            double x = Math.min(startX[0], event.getX());
            double y = Math.min(startY[0], event.getY());
            dragBox.setX(x);
            dragBox.setY(y);
            dragBox.setWidth(Math.abs(event.getX() - startX[0]));
            dragBox.setHeight(Math.abs(event.getY() - startY[0]));
            event.consume();
        });

        pane.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (!selecting[0]) {
                return;
            }
            selecting[0] = false;
            dragBox.setVisible(false);
            Bounds box = dragBox.getBoundsInParent();
            if (box.getWidth() < 3 || box.getHeight() < 3) {
                event.consume();
                return;
            }
            selected.select(bands, band -> band.searchMatch && box.intersects(band.hitBox.getBoundsInParent()),
                    event.isControlDown() || event.isShiftDown());
            updateSelectedLabel(selected, selectedLabel);
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
        MenuItem copyGenes = new MenuItem("Copy assay name(s)");
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
        }
        // Beside the pointer, on the side where it fits: a band near the screen's right or bottom edge had its
        // popup, a dozen lines since it shows how each oligo binds, cut off. Never under the pointer, which would
        // leave the band and hide it: where it fits on neither side, on the side with more room.
        javafx.geometry.Rectangle2D screen = javafx.stage.Screen.getScreensForRectangle(event.getScreenX(),
                        event.getScreenY(), 1, 1).stream().findFirst().orElse(javafx.stage.Screen.getPrimary())
                .getVisualBounds();
        double popupX = beside(event.getScreenX(), 14, popup.getWidth(), screen.getMinX(), screen.getMaxX());
        double popupY = beside(event.getScreenY(), 12, popup.getHeight(), screen.getMinY(), screen.getMaxY());
        if (popupX <= event.getScreenX() && event.getScreenX() <= popupX + popup.getWidth()
                && popupY <= event.getScreenY() && event.getScreenY() <= popupY + popup.getHeight()) {
            // Too big for the screen on both axes (dozens of co-migrating amplicons): beside the pointer anyway,
            // cut off, rather than under it.
            popupX = event.getScreenX() + 14;
        }
        popup.setX(popupX);
        popup.setY(popupY);
    }

    /**
     * Where a popup of {@code size} starts along one axis, {@code gap} from the pointer at {@code pointer}: after it
     * when it fits before {@code max}, else before it when it fits after {@code min}, else as far inside the
     * screen as it goes (on the other axis it is still beside the pointer, so it does not cover it).
     */
    static double beside(double pointer, double gap, double size, double min, double max) {
        double after = pointer + gap;
        double before = pointer - gap - size;
        if (after + size <= max) {
            return after;
        }
        return before >= min ? before : Math.max(min, max - size);
    }

    private static void showBandDetails(BandNode bandNode) {
        TextArea details = new TextArea(bandNode.detailText());
        details.setEditable(false);
        details.setWrapText(false);
        details.setPrefColumnCount(70);
        details.setPrefRowCount(Math.max(8, Math.min(30, (int) details.getText().lines().count() + 1)));
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
                                                 GelSelection<BandNode> selection,
                                                 Label selectedLabel,
                                                 java.util.function.Supplier<List<BandNode>> bands,
                                                 Runnable zoomIn,
                                                 Runnable zoomOut,
                                                 Runnable zoomReset,
                                                 Runnable savePng,
                                                 Runnable saveSvg,
                                                 Runnable savePdf,
                                                 Runnable previousPage,
                                                 Runnable nextPage) {
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
            } else if (previousPage != null && event.getCode() == KeyCode.PAGE_UP) {
                // Only with more than one gel: otherwise Page Up/Down scroll the gel.
                previousPage.run();
                event.consume();
            } else if (nextPage != null && event.getCode() == KeyCode.PAGE_DOWN) {
                nextPage.run();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.A
                    && !(scene.getFocusOwner() instanceof javafx.scene.control.TextInputControl)) {
                // Ctrl+A in the search box selects its text, not every band. Bands selected
                // before that no longer match the search are let go, not left highlighted.
                selection.select(bands.get(), band -> band.searchMatch, false);
                updateSelectedLabel(selection, selectedLabel);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                searchField.clear();
                selection.clear();
                updateSelectedLabel(selection, selectedLabel);
                event.consume();
            }
        });
    }

    /**
     * Asks where to save {@code picture} as {@code format}, offering the name {@code --gel} gives
     * the gel shown (with this format's extension), then saves it there.
     */
    private static void chooseAndSave(Window owner, String format, String extension, GelScene picture,
                                      Path automaticOutput) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Synthetic Gel " + format);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format + " file", "*." + extension));
        chooser.setInitialFileName(GelImages.withExtension(automaticOutput, extension));
        setInitialDirectory(chooser, automaticOutput);
        File selectedFile = chooser.showSaveDialog(owner);
        if (selectedFile == null) {
            return;
        }
        save(owner, format, selectedFile.toPath(), file -> {
            switch (extension) {
                case "svg" -> GelImages.writeText(GelSvg.of(picture), file);
                case "pdf" -> GelImages.writePdf(GelPicture.image(picture, true), file);
                default -> GelImages.writePng(GelPicture.image(picture, false), file);
            }
        });
    }

    private static void setInitialDirectory(FileChooser chooser, Path output) {
        Path parent = output.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            chooser.setInitialDirectory(parent.toFile());
        }
    }

    /** Something written to a file, which may fail. */
    @FunctionalInterface
    private interface FileWrite {
        void to(Path file) throws IOException;
    }

    /**
     * Writes {@code what} to {@code outputFile} and says so; a failure (a folder that cannot be
     * written to, a full disk) is a message, not a silent trace on the console.
     */
    private static void save(Window owner, String what, Path outputFile, FileWrite write) {
        try {
            write.to(outputFile);
            showInfo(owner, what + " saved", "Saved the synthetic gel " + what + " to:\n" + outputFile.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not save the synthetic gel " + what + " to "
                    + outputFile.toAbsolutePath() + ":\n" + e.getMessage(), ButtonType.OK);
            alert.initOwner(owner);
            alert.setHeaderText(null);
            alert.showAndWait();
        }
    }

    private static void showInfo(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private record GelView(Pane pane, List<BandNode> bands) {
    }

    private record SearchResult(boolean searching, int matchCount, BandNode firstMatch) {
    }

    /**
     * The highlight behind a sample lane, from all of its bands: the last band of a lane to change
     * does not decide it alone.
     */
    private static final class LaneHighlight {
        private final Rectangle rectangle;
        private final List<BandNode> bands = new ArrayList<>();

        private LaneHighlight(Rectangle rectangle) {
            this.rectangle = rectangle;
        }

        private Rectangle rectangle() {
            return rectangle;
        }

        private void refresh() {
            rectangle.setFill(BandPaint.laneHighlight(bands.stream().anyMatch(band -> band.hovered),
                    bands.stream().anyMatch(band -> band.selected)));
        }
    }

    private static final class ZoomState {
        private double zoom = 1.0;
    }

    private static final class BandNode {
        private final Group group;
        private final Rectangle hitBox;
        private final Rectangle band;
        private final Rectangle halo;
        private final LaneHighlight laneHighlight;
        private final List<GelBand> bands;
        private final double intensity;
        private final String popupText;
        private final String searchText;
        private final BandRect rect;
        private final Color bandColor;
        private boolean hovered;
        private boolean selected;
        private boolean searchMatch = true;
        private boolean searching;
        private boolean colored;
        private Popup popup;
        private ContextMenu menu;

        private BandNode(Group group,
                         Rectangle hitBox,
                         Rectangle band,
                         Rectangle halo,
                         LaneHighlight laneHighlight,
                         List<GelBand> bands,
                         double intensity,
                         String popupText,
                         BandRect rect,
                         Color bandColor) {
            this.group = group;
            this.hitBox = hitBox;
            this.band = band;
            this.halo = halo;
            this.laneHighlight = laneHighlight;
            this.bands = bands;
            this.intensity = intensity;
            this.popupText = popupText;
            this.rect = rect;
            this.bandColor = bandColor;
            this.searchText = GelText.searchText(bands);
            if (laneHighlight != null) {
                laneHighlight.bands.add(this);
            }
            updateVisual();
        }

        private Popup popup() {
            if (popup == null) {
                popup = createMetadataPopup(popupText);
            }
            return popup;
        }

        private ContextMenu menu() {
            if (menu == null) {
                menu = createContextMenu(this);
            }
            return menu;
        }

        private void installSelectionHandler(GelSelection<BandNode> selection, Label selectedLabel) {
            hitBox.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                selection.click(this, event.isControlDown() || event.isShiftDown());
                updateSelectedLabel(selection, selectedLabel);
                event.consume();
            });
        }

        private void updateVisual() {
            BandPaint.State state = BandPaint.State.of(hovered, selected, searching, searchMatch, colored, bandColor, intensity);
            group.setOpacity(state.opacity());
            if (laneHighlight != null) {
                laneHighlight.refresh();
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

    private static void updateSelectedLabel(GelSelection<BandNode> selection, Label selectedLabel) {
        selectedLabel.setText(GelText.selection(selection.bands().stream().map(band -> band.bands).toList()));
    }
}
