package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.Sample;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight page navigator for very large gel reports.
 *
 * <p>The interactive gel renderer remains responsible for drawing, tooltips,
 * selection, search within a page, and export. This class prevents thousands of
 * lanes from being rendered at once by writing page-specific report TSV files
 * and opening one gel page at a time.</p>
 */
public final class PaginatedGelViewer {
    public static final int DEFAULT_SAMPLES_PER_GEL = 100;

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
        showNavigator(model);
    }

    private static void showNavigator(PageModel model) {
        Label title = new Label("Large gel report: " + model.totalSamples() + " samples split into " + model.totalPages() + " gels");
        title.setStyle("-fx-font-weight: bold;");

        Label pageLabel = new Label();
        Label rangeLabel = new Label();
        TextField searchField = new TextField();
        searchField.setPromptText("Search sample or gene across all gel pages");

        Button previous = new Button("Previous Gel");
        Button next = new Button("Next Gel");
        Button open = new Button("Open Current Gel");

        Runnable refresh = () -> {
            pageLabel.setText("Page " + (model.pageIndex + 1) + " of " + model.totalPages());
            rangeLabel.setText(model.currentRangeText());
            previous.setDisable(model.pageIndex == 0);
            next.setDisable(model.pageIndex >= model.totalPages() - 1);
        };

        previous.setOnAction(event -> {
            if (model.pageIndex > 0) {
                model.pageIndex--;
                refresh.run();
                openCurrentPage(model);
            }
        });
        next.setOnAction(event -> {
            if (model.pageIndex < model.totalPages() - 1) {
                model.pageIndex++;
                refresh.run();
                openCurrentPage(model);
            }
        });
        open.setOnAction(event -> openCurrentPage(model));

        searchField.setOnAction(event -> {
            int page = model.pageContaining(searchField.getText());
            if (page >= 0) {
                model.pageIndex = page;
                refresh.run();
                openCurrentPage(model);
            } else {
                rangeLabel.setText("No matching sample/gene found: " + searchField.getText());
            }
        });

        HBox navigation = new HBox(8, previous, next, open, pageLabel);
        navigation.setAlignment(Pos.CENTER_LEFT);

        HBox search = new HBox(8, new Label("Find:"), searchField);
        search.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        VBox root = new VBox(10, title, navigation, rangeLabel, search,
                new Label("Tip: press Enter in the search box to jump to the first gel page containing that sample or gene."));
        root.setPadding(new Insets(12));
        root.setPrefWidth(720);

        refresh.run();
        openCurrentPage(model);

        Stage stage = new Stage();
        stage.setTitle("Synthetic Gel Pages");
        stage.setScene(new Scene(root));
        stage.show();
    }

    private static void openCurrentPage(PageModel model) {
        Path pageReport = model.writeCurrentPageReport();
        InteractiveGelViewer.show(model.ownerScene, pageReport, new HashMap<>());
    }

    private static final class PageModel {
        private final Scene ownerScene;
        private final Path consolidatedReport;
        private final LinkedHashMap<String, List<GelBand>> lanes;
        private final List<String> sampleNames;
        private final int samplesPerGel;
        private int pageIndex;

        private PageModel(Scene ownerScene,
                          Path consolidatedReport,
                          LinkedHashMap<String, List<GelBand>> lanes,
                          int samplesPerGel) {
            this.ownerScene = ownerScene;
            this.consolidatedReport = consolidatedReport;
            this.lanes = lanes;
            this.sampleNames = new ArrayList<>(lanes.keySet());
            this.samplesPerGel = samplesPerGel;
        }

        private int totalSamples() {
            return sampleNames.size();
        }

        private int totalPages() {
            return Math.max(1, (int) Math.ceil((double) sampleNames.size() / samplesPerGel));
        }

        private String currentRangeText() {
            int start = startIndex();
            int end = endIndex();
            String first = sampleNames.get(start);
            String last = sampleNames.get(end - 1);
            return "Samples " + (start + 1) + "–" + end + " of " + totalSamples()
                    + " (" + first + " ... " + last + ")";
        }

        private int pageContaining(String query) {
            if (query == null || query.isBlank()) {
                return -1;
            }
            String normalized = query.strip().toLowerCase();
            for (int i = 0; i < sampleNames.size(); i++) {
                String sampleName = sampleNames.get(i);
                if (sampleName.toLowerCase().contains(normalized)) {
                    return i / samplesPerGel;
                }
                for (GelBand band : lanes.getOrDefault(sampleName, List.of())) {
                    if (band.geneName().toLowerCase().contains(normalized)
                            || Integer.toString(band.ampliconSize()).contains(normalized)) {
                        return i / samplesPerGel;
                    }
                }
            }
            return -1;
        }

        private Path writeCurrentPageReport() {
            try {
                Path pageDirectory = outputDirectory().resolve("synthetic_gel_pages");
                Files.createDirectories(pageDirectory);
                Path pageReport = pageDirectory.resolve("report_page_" + String.format("%03d", pageIndex + 1) + ".tsv");

                StringBuilder tsv = new StringBuilder("Sample\tGene\tSource\tAmpliconSize\n");
                for (String sampleName : currentSamples()) {
                    for (GelBand band : lanes.getOrDefault(sampleName, List.of())) {
                        tsv.append(band.sampleName()).append('\t')
                                .append(band.geneName()).append('\t')
                                .append("gel_page").append('\t')
                                .append(band.ampliconSize()).append('\n');
                    }
                }
                Files.writeString(pageReport, tsv.toString(), StandardCharsets.UTF_8);
                return pageReport;
            } catch (IOException e) {
                throw new IllegalStateException("Unable to write paginated gel report", e);
            }
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

        private Path outputDirectory() {
            Path reportDir = consolidatedReport.getParent();
            if (reportDir != null && reportDir.getParent() != null) {
                return reportDir.getParent();
            }
            if (reportDir != null) {
                return reportDir;
            }
            return Path.of(".");
        }
    }
}
