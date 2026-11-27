package ca.canada.inspection.gel;

import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.report.ReportColumn;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Text shown about the amplicons of one gel band: tooltips, details, selection and search. */
final class GelText {
    private GelText() {
    }

    /**
     * Hover text of a band: one amplicon, with how each oligo binds and what it was called on, or the list of
     * co-migrating amplicons, each with its weakest site and its differences from the template.
     */
    static String tooltip(List<GelBand> bands) {
        if (bands.size() == 1) {
            GelBand band = bands.getFirst();
            return "Sample: " + band.sampleName() + '\n'
                    + "Assay: " + band.geneName() + '\n'
                    + "Amplicon size: " + band.ampliconSize() + " bp" + sizeCheck(band, "\nSize check: ")
                    + (band.pairingTag().isEmpty() ? "" : "\nMade by: " + band.pairingTag())
                    + binding(band.row(), "\n");
        }
        String details = bands.stream()
                .map(gelBand -> gelBand.geneName() + " — " + gelBand.ampliconSize() + " bp" + sizeCheck(gelBand, ", ")
                        + (gelBand.pairingTag().isEmpty() ? "" : ", " + gelBand.pairingTag())
                        + summary(gelBand.row()))
                .collect(Collectors.joining("\n"));
        return "Sample: " + bands.getFirst().sampleName() + '\n'
                + "Co-migrating amplicons: " + bands.size() + '\n'
                + details;
    }

    /**
     * How the oligos of {@code row} bind, its weakest site and what it was called on, a line each, each line
     * starting with {@code lineStart}; empty without a row.
     */
    private static String binding(ReportRow row, String lineStart) {
        if (row == null) {
            return "";
        }
        // The report's forward column holds the primer on the plus strand when the pair is not the assay's own
        // (one primer at an inverted repeat, or primers of two assays): it is no forward primer.
        boolean designed = row.pairing() == ReportRow.Pairing.DESIGNED;
        StringBuilder text = new StringBuilder();
        text.append(primer(designed ? "Forward" : "Plus-strand primer", row.forward(), lineStart))
                .append(primer(designed ? "Reverse" : "Minus-strand primer", row.reverse(), lineStart));
        if (row.probe() != null) {
            text.append(lineStart).append("Probe ").append(row.probe().id()).append(": ")
                    .append(differences(row.probe().mismatches(), 0, 0)).append(tm(row.probe().tm(), ", Tm "));
        }
        Weakest weakest = weakest(row);
        if (weakest != null) {
            text.append(lineStart).append("Weakest site: ").append(weakest.oligo()).append(tm(weakest.tm(), ", "));
        }
        return text.append(lineStart).append("Called on: ").append(calledOn(row.source())).toString();
    }

    /** A line on how a primer binds; empty for a report without the primer's column. */
    private static String primer(String role, ReportRow.Binding binding, String lineStart) {
        if (binding.primer().isBlank()) {
            return "";
        }
        return lineStart + role + " " + binding.primer() + ": "
                + differences(binding.mismatches(), binding.threePrime(), binding.unpaired())
                + tm(binding.tm(), ", Tm ");
    }

    /**
     * An oligo's differences from its site, as the report counts them and {@code report_no_product.tsv} words
     * them ({@link ReportRow#differences}), with those in the 3' window: {@code 2 mismatches and indels (1 in the
     * 3' window), 4 unpaired 5' bases}; {@code no mismatch} for none.
     */
    static String differences(int mismatches, int threePrime, int unpaired) {
        List<String> parts = new java.util.ArrayList<>(ReportRow.differences(mismatches, unpaired));
        if (threePrime > 0) {
            String inWindow = threePrime + " in the 3' window";
            if (mismatches > 0) {
                parts.set(0, parts.getFirst() + " (" + inWindow + ")");
            } else {
                parts.addFirst(inWindow);
            }
        }
        return parts.isEmpty() ? "no mismatch" : String.join(", ", parts);
    }

    /** {@code prefix} and the Tm as the report writes it, {@code 51.2 °C}; empty for none (a 1.x report). */
    private static String tm(double tm, String prefix) {
        return Double.isFinite(tm) ? prefix + ReportColumn.tm(tm) + " °C" : "";
    }

    /** The oligo of a product whose site melts lowest, and its Tm. */
    private record Weakest(String oligo, double tm) {
    }

    /**
     * The site of {@code row} that melts lowest; {@code null} when no Tm was worked out (a 1.x report) or the
     * report names no oligo. One primer at both ends of a product is told apart by its strand.
     */
    private static Weakest weakest(ReportRow row) {
        boolean onePrimer = row.pairing() == ReportRow.Pairing.ONE_PRIMER;
        Weakest weakest = null;
        for (Weakest site : new Weakest[]{
                new Weakest(row.forwardPrimer() + (onePrimer ? " (plus-strand site)" : ""), row.forwardTm()),
                new Weakest(row.reversePrimer() + (onePrimer ? " (minus-strand site)" : ""), row.reverseTm()),
                row.probe() == null ? null : new Weakest(row.probeId(), row.probeTm())}) {
            if (site != null && !site.oligo().isBlank() && Double.isFinite(site.tm())
                    && (weakest == null || site.tm() < weakest.tm())) {
                weakest = site;
            }
        }
        return weakest;
    }

    /** What a call was made on, with what supports a call from reads. */
    static String calledOn(ReportRow.Source source) {
        return switch (source) {
            case ReportRow.Source.Assembly assembly -> "assembly";
            case ReportRow.Source.Reads reads -> "reads, the whole amplicon in " + reads.supporting() + " of "
                    + reads.informative() + " informative reads (" + reads.percent() + ")";
            case ReportRow.Source.Extension extension -> "extended reads, " + extension.forwardClosing() + " of "
                    + extension.forwardSeeds() + " forward-seeded and " + extension.reverseClosing() + " of "
                    + extension.reverseSeeds() + " reverse-seeded reaching the other primer";
        };
    }

    /**
     * A co-migrating amplicon's weakest site and its differences from the template, all oligos together (each
     * one's are in the band details): {@code , weakest site 51.2 °C, 1 unpaired 5' base}; empty without a row.
     */
    private static String summary(ReportRow row) {
        if (row == null) {
            return "";
        }
        Weakest weakest = weakest(row);
        int mismatches = row.forwardMismatches() + row.reverseMismatches() + row.probeMismatches();
        int threePrime = row.forwardThreePrime() + row.reverseThreePrime();
        int unpaired = row.forwardUnpaired() + row.reverseUnpaired();
        return (weakest == null ? "" : tm(weakest.tm(), ", weakest site "))
                + (mismatches + threePrime + unpaired == 0 ? "" : ", " + differences(mismatches, threePrime, unpaired));
    }

    /** Where the amplicon lies, and the report's description of it; empty without a row or a contig. */
    private static String site(ReportRow row, String lineStart) {
        if (row == null || row.contig() == null || row.contig().isBlank()) {
            return "";
        }
        String strand = row.strand() == ' ' ? "" : ", " + row.strand() + " strand";
        String description = row.contigDescription() == null || row.contigDescription().isBlank() ? ""
                : lineStart + "Description: " + row.contigDescription();
        return lineStart + "On: " + row.contig() + ", " + row.location() + strand + description;
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

    /**
     * Text of the band details window and of "Copy band details": the hover text, where each amplicon lies (and,
     * for co-migrating amplicons, how each one's oligos bind), then a line per amplicon.
     */
    static String details(String tooltip, List<GelBand> bands) {
        if (tooltip == null || tooltip.isBlank()) {
            return "No band details available.";
        }
        String sites = bands.size() == 1 ? site(bands.getFirst().row(), "\n")
                : bands.stream().filter(band -> band.row() != null)
                .map(band -> "\n\n" + band.geneName() + " — " + band.ampliconSize() + " bp"
                        + binding(band.row(), "\n  ") + site(band.row(), "\n  "))
                .collect(Collectors.joining());
        return tooltip + sites + "\n\nRows:\n" + bands.stream()
                .map(gelBand -> gelBand.sampleName() + '\t' + gelBand.geneName() + '\t' + gelBand.ampliconSize() + " bp"
                        + (gelBand.sizeCheck() == null ? "" : "\t" + gelBand.sizeCheck().text())
                        + (gelBand.pairingTag().isEmpty() ? "" : "\t" + gelBand.pairingTag()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Lower-case text a search query is matched against: sample, gene, size and size check
     * ({@code target}, {@code other}, {@code unexpected}) of each amplicon, and the primers and probe that made it.
     */
    static String searchText(List<GelBand> bands) {
        return bands.stream()
                .map(gelBand -> gelBand.sampleName() + " " + gelBand.geneName() + " " + gelBand.ampliconSize()
                        + (gelBand.sizeCheck() == null ? "" : " " + gelBand.sizeCheck().text())
                        + (gelBand.row() == null ? "" : " " + gelBand.row().forwardPrimer() + " "
                        + gelBand.row().reversePrimer() + (gelBand.row().probe() == null ? ""
                        : " " + gelBand.row().probeId())))
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
    }

    /** A search query trimmed and in lower case; empty for {@code null}. */
    static String normalizeQuery(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT); // as searchText
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
