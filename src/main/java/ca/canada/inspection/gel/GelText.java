package ca.canada.inspection.gel;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/** Text shown about the amplicons of one gel band: tooltips, details, selection and search. */
final class GelText {
    private GelText() {
    }

    /** Hover text of a band: one amplicon, or the list of co-migrating amplicons. */
    static String tooltip(List<GelBand> bands) {
        if (bands.size() == 1) {
            GelBand band = bands.getFirst();
            return "Sample: " + band.sampleName() + '\n'
                    + "Assay: " + band.geneName() + '\n'
                    + "Amplicon size: " + band.ampliconSize() + " bp" + sizeCheck(band, "\nSize check: ")
                    + (band.pairingTag().isEmpty() ? "" : "\nMade by: " + band.pairingTag());
        }
        String details = bands.stream()
                .map(gelBand -> gelBand.geneName() + " — " + gelBand.ampliconSize() + " bp" + sizeCheck(gelBand, ", ")
                        + (gelBand.pairingTag().isEmpty() ? "" : ", " + gelBand.pairingTag()))
                .collect(Collectors.joining("\n"));
        return "Sample: " + bands.getFirst().sampleName() + '\n'
                + "Co-migrating amplicons: " + bands.size() + '\n'
                + details;
    }

    /** {@code prefix} and what the amplicon's size is for its assay; empty when not checked. */
    private static String sizeCheck(GelBand band, String prefix) {
        return band.sizeCheck() == null ? "" : prefix + band.sizeCheck().text();
    }

    /** Gene of the first amplicon of a band, which sets the band's colour; empty for no amplicon. */
    static String primaryGene(List<GelBand> bands) {
        return bands == null || bands.isEmpty() ? "" : bands.getFirst().geneName();
    }

    /** Sample of the band; empty for a band without amplicons (the ladder). */
    static String sampleName(List<GelBand> bands) {
        return bands.isEmpty() ? "" : bands.getFirst().sampleName();
    }

    /** Distinct gene names of the band, comma separated. */
    static String geneNames(List<GelBand> bands) {
        return bands.stream().map(GelBand::geneName).distinct().collect(Collectors.joining(", "));
    }

    /** Amplicon sizes of the band, comma separated. */
    static String ampliconSizes(List<GelBand> bands) {
        return bands.stream().map(gelBand -> gelBand.ampliconSize() + " bp").collect(Collectors.joining(", "));
    }

    /** Text of the band details window and of "Copy band details". */
    static String details(String tooltip, List<GelBand> bands) {
        if (tooltip == null || tooltip.isBlank()) {
            return "No band details available.";
        }
        return tooltip + "\n\nRows:\n" + bands.stream()
                .map(gelBand -> gelBand.sampleName() + '\t' + gelBand.geneName() + '\t' + gelBand.ampliconSize() + " bp"
                        + (gelBand.sizeCheck() == null ? "" : "\t" + gelBand.sizeCheck().text())
                        + (gelBand.pairingTag().isEmpty() ? "" : "\t" + gelBand.pairingTag()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Lower-case text a search query is matched against: sample, gene, size and size check
     * ({@code target}, {@code other}, {@code unexpected}) of each amplicon.
     */
    static String searchText(List<GelBand> bands) {
        return bands.stream()
                .map(gelBand -> gelBand.sampleName() + " " + gelBand.geneName() + " " + gelBand.ampliconSize()
                        + (gelBand.sizeCheck() == null ? "" : " " + gelBand.sizeCheck().text()))
                .collect(Collectors.joining(" "))
                .toLowerCase();
    }

    /** A search query trimmed and in lower case; empty for {@code null}. */
    static String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase();
    }

    /** Status text for the selected bands, each given by its amplicons. */
    static String selection(Collection<List<GelBand>> selectedBands) {
        if (selectedBands.isEmpty()) {
            return "No band selected";
        }
        if (selectedBands.size() == 1) {
            List<GelBand> bands = selectedBands.iterator().next();
            return "Selected: " + sampleName(bands) + " / " + geneNames(bands) + " / " + ampliconSizes(bands);
        }
        return "Selected bands: " + selectedBands.size();
    }

    /** "1 match", "2 matches". */
    static String matches(int count) {
        return count + " match" + (count == 1 ? "" : "es");
    }

    /**
     * The matches of a search on the gel shown, and on every gel of a run of several when they
     * hold more: Ctrl+A selects those of the gel shown.
     */
    static String matches(int onThisGel, int onAllGels) {
        return onAllGels > onThisGel ? matches(onThisGel) + " on this gel, " + onAllGels + " on all gels"
                : matches(onThisGel);
    }
}
