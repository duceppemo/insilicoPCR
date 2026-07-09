package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * JavaFX scene-graph implementation of the synthetic gel.
 *
 * <p>Every visible sample band is a real JavaFX node, so hover tooltips,
 * highlighting, selection, future click actions, zooming, and PNG export can be
 * implemented without image-coordinate hit testing.</p>
 */
public final class InteractiveGelViewer {
    private static final int GEL_MIN_BP = 0;
    private static final int GEL_MAX_BP = 25_000;
    private static final double GEL_LOG_OFFSET_BP = 50.0;
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

        Pane gelPane = buildGel(ownerScene, lanes);
        Path automaticOutput = defaultSyntheticGelOutput(consolidatedReport);
        savePaneAsPng(gelPane, automaticOutput);

        Button saveButton = new Button("Save Image As...");
        saveButton.setOnAction(event -> chooseAndSave(gelPane, automaticOutput));

        HBox toolbar = new HBox(10, saveButton);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8));

        ScrollPane scrollPane = new ScrollPane(gelPane);
        scrollPane.setFitToHeight(false);
        scrollPane.setFitToWidth(false);
        scrollPane.setPannable(true);

        BorderPane root = new BorderPane(scrollPane);
        root.setBottom(toolbar);

        var screenBounds = Screen.getPrimary().getVisualBounds();
        double toolbarHeight = 56;
        double windowMargin = 80;
        double preferredWidth = gelPane.getPrefWidth() + 32;
        double preferredHeight = gelPane.getPrefHeight() + toolbarHeight + 32;
        double maxWindowWidth = Math.max(900, screenBounds.getWidth() - windowMargin);
        double maxWindowHeight = Math.max(700, screenBounds.getHeight() - windowMargin);
        double windowWidth = Math.min(preferredWidth, maxWindowWidth);
        double windowHeight = Math.min(preferredHeight, maxWindowHeight);

        Stage stage = new Stage();
        stage.setTitle("Synthetic Gel - " + consolidatedReport.getFileName() + " - saved to " + automaticOutput.getFileName());
        stage.setScene(new Scene(root, windowWidth, windowHeight));
        stage.setMinWidth(Math.min(900, windowWidth));
        stage.setMinHeight(Math.min(650, windowHeight));
        stage.show();
    }

    private static Pane buildGel(Scene ownerScene, LinkedHashMap<String, List<GelBand>> lanes) {
        int laneCount = lanes.size() + 1;
        double visibleWidth = Math.max(ownerScene.getWidth(), 800);
        double visibleHeight = Math.max(ownerScene.getHeight(), 500);

        double leftLabelWidth = 82;
        double rightInset = 18;
        double topInset = 18;
        double gelHeight = Math.max(540, visibleHeight * 1.10);
        double longestLabelWidth = longestLaneLabelWidth(lanes);
        double labelAreaHeight = Math.max(150, Math.min(360, longestLabelWidth * 0.72 + 36));
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

        drawGelBackground(pane, gelLeft, topInset, gelWidth, gelHeight, laneCount, laneWidth);
        drawLadder(pane, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
        drawLadderLabels(pane, topInset, gelHeight, gelLeft - 8);
        drawSampleBands(pane, lanes, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
        drawGelBorder(pane, gelLeft, topInset, gelWidth, gelHeight);
        drawLaneLabels(pane, lanes, gelLeft, gelBottom + 34, laneWidth);

        return pane;
    }

    private static double longestLaneLabelWidth(LinkedHashMap<String, List<GelBand>> lanes) {
        double longest = "Ladder".length();
        for (String sampleName : lanes.keySet()) {
            longest = Math.max(longest, sampleName.length());
        }
        return longest * 6.2;
    }

    private static void drawGelBackground(Pane pane, double x, double y, double width, double height,
                                          int laneCount, double laneWidth) {
        Rectangle background = new Rectangle(x, y, width, height);
        background.setFill(Color.rgb(238, 238, 238));
        pane.getChildren().add(background);

        for (int i = 0; i < laneCount; i++) {
            double laneX = x + (i * laneWidth);
            Rectangle lane = new Rectangle(laneX, y, laneWidth, height);
            lane.setFill(i % 2 == 0 ? Color.rgb(244, 244, 244) : Color.rgb(232, 232, 232));
            pane.getChildren().add(lane);

            Rectangle separator = new Rectangle(laneX, y, 3.0, height);
            separator.setFill(Color.WHITE);
            pane.getChildren().add(separator);
        }

        for (int row = 24; row < height; row += 42) {
            Rectangle lightTexture = new Rectangle(x, y + row, width, 1.0);
            lightTexture.setFill(Color.rgb(246, 246, 246, 0.45));
            Rectangle darkTexture = new Rectangle(x, y + row + 2, width, 1.0);
            darkTexture.setFill(Color.rgb(222, 222, 222, 0.25));
            pane.getChildren().addAll(lightTexture, darkTexture);
        }
    }

    private static void drawGelBorder(Pane pane, double x, double y, double width, double height) {
        Rectangle border = new Rectangle(x, y, width, height);
        border.setFill(Color.TRANSPARENT);
        border.setStroke(Color.BLACK);
        border.setStrokeWidth(3.0);
        pane.getChildren().add(border);
    }

    private static void drawLaneLabels(Pane pane, LinkedHashMap<String, List<GelBand>> lanes,
                                       double gelLeft, double labelY, double laneWidth) {
        addRotatedLaneLabel(pane, "Ladder", gelLeft + (laneWidth / 2), labelY);

        int laneIndex = 1;
        for (String sampleName : lanes.keySet()) {
            double x = gelLeft + (laneIndex * laneWidth) + (laneWidth / 2);
            addRotatedLaneLabel(pane, sampleName, x, labelY);
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
        pane.getChildren().add(text);
    }

    private static void drawLadder(Pane pane, double gelLeft, double gelTop, double gelHeight,
                                   double laneWidth, double bandHeight) {
        for (int size : LADDER_SIZES) {
            addGelBandShape(pane, gelLeft, ladderY(gelTop, gelHeight, size), laneWidth, bandHeight, 0.45, null);
        }
    }

    private static void drawLadderLabels(Pane pane, double gelTop, double gelHeight, double labelRightX) {
        for (int i = 0; i < LADDER_SIZES.length; i++) {
            Text text = new Text(LADDER_LABELS[i]);
            text.setFont(Font.font("Verdana", 10));
            text.setFill(Color.BLACK);
            text.setX(labelRightX - approximateTextWidth(LADDER_LABELS[i]));
            text.setY(ladderY(gelTop, gelHeight, LADDER_SIZES[i]) + 4);
            pane.getChildren().add(text);
        }
    }

    private static double approximateTextWidth(String text) {
        return text.length() * 6.1;
    }

    private static void drawSampleBands(Pane pane, LinkedHashMap<String, List<GelBand>> lanes,
                                        double gelLeft, double gelTop, double gelHeight,
                                        double laneWidth, double baseBandHeight) {
        int laneIndex = 1;
        for (Map.Entry<String, List<GelBand>> laneEntry : lanes.entrySet()) {
            String sampleName = laneEntry.getKey();
            double x = gelLeft + (laneIndex * laneWidth);
            Map<Integer, List<GelBand>> bandsBySize = new TreeMap<>(GelReportReader.groupByRoundedSize(laneEntry.getValue()));

            for (Map.Entry<Integer, List<GelBand>> bandEntry : bandsBySize.entrySet()) {
                int roundedSize = bandEntry.getKey();
                List<GelBand> bands = bandEntry.getValue();
                int count = bands.size();
                double laneVariation = deterministicRange(sampleName, 0.90, 1.10);
                double verticalJitter = deterministicRange(sampleName + ':' + roundedSize, -0.45, 0.45);
                double intensity = Math.min(1.0, bandIntensity(roundedSize, count) * laneVariation);
                double adjustedBandHeight = baseBandHeight + (intensity * 1.2);
                Tooltip tooltip = new Tooltip(tooltipText(bands));

                addGelBandShape(
                        pane,
                        x,
                        ladderY(gelTop, gelHeight, roundedSize) + verticalJitter,
                        laneWidth,
                        adjustedBandHeight,
                        intensity,
                        tooltip
                );
            }
            laneIndex++;
        }
    }

    private static Group addGelBandShape(Pane pane,
                                         double x,
                                         double centerY,
                                         double laneWidth,
                                         double bandHeight,
                                         double intensity,
                                         Tooltip tooltip) {
        double bandWidth = Math.max(4, laneWidth * 0.66);
        double bandX = x + ((laneWidth - bandWidth) / 2.0);
        double bandY = centerY - (bandHeight / 2.0);

        Rectangle halo = new Rectangle(bandX - 1.5, bandY - 2.0, bandWidth + 3.0, bandHeight + 4.0);
        halo.setFill(Color.rgb(25, 25, 25, intensity * 0.20));

        Rectangle band = new Rectangle(bandX, bandY, bandWidth, bandHeight);
        band.setFill(Color.rgb(8, 8, 8, intensity));

        Rectangle highlight = new Rectangle(bandX, bandY + 0.5, bandWidth, Math.max(0.75, bandHeight * 0.22));
        highlight.setFill(Color.rgb(255, 255, 255, Math.min(0.18, intensity * 0.14)));

        Rectangle shadow = new Rectangle(bandX, bandY + bandHeight - 0.75, bandWidth, 0.75);
        shadow.setFill(Color.rgb(0, 0, 0, Math.min(0.32, intensity * 0.24)));

        Group group = new Group(halo, band, highlight, shadow);
        if (tooltip != null) {
            Tooltip.install(group, tooltip);
            group.setOnMouseEntered(event -> {
                band.setStroke(Color.rgb(30, 144, 255));
                band.setStrokeWidth(1.25);
                halo.setFill(Color.rgb(30, 144, 255, 0.25));
            });
            group.setOnMouseExited(event -> {
                band.setStroke(null);
                halo.setFill(Color.rgb(25, 25, 25, intensity * 0.20));
            });
        }
        pane.getChildren().add(group);
        return group;
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
                .map(band -> band.geneName() + " — " + band.ampliconSize() + " bp")
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

    private static void chooseAndSave(Pane gelPane, Path automaticOutput) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save Synthetic Gel Image");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
        chooser.setInitialFileName(automaticOutput.getFileName().toString());
        Path parent = automaticOutput.getParent();
        if (parent != null && Files.isDirectory(parent)) {
            chooser.setInitialDirectory(parent.toFile());
        }

        File selectedFile = chooser.showSaveDialog(null);
        if (selectedFile != null) {
            savePaneAsPng(gelPane, selectedFile.toPath());
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
            ImageIO.write(toBufferedImage(image), "png", outputFile.toFile());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to save synthetic gel image: " + outputFile, e);
        }
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return buffered;
    }
}
