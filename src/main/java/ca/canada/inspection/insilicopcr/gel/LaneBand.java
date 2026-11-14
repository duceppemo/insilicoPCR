package ca.canada.inspection.insilicopcr.gel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One band to draw in a sample lane: the amplicons it stands for, where it sits and how strong
 * it looks. The three gel pictures place bands slightly differently, hence one factory each.
 *
 * @param lane      lane index; lane 0 is the ladder
 * @param laneX     left edge of the lane
 * @param gene      gene that sets the band's colour
 * @param bands     amplicons drawn as this band
 * @param centerY   vertical centre of the band
 * @param intensity band opacity, 0 to 1
 * @param rect      the band's rectangle
 */
record LaneBand(int lane, double laneX, String gene, List<GelBand> bands, double centerY, double intensity, BandRect rect) {

    /** Rectangle of a band: 66% of the lane wide, centred in the lane and on {@code centerY}. */
    record BandRect(double x, double y, double width, double height) {
        static BandRect inLane(double laneX, double centerY, double laneWidth, double height) {
            double width = Math.max(4, laneWidth * 0.66);
            return new BandRect(laneX + ((laneWidth - width) / 2.0), centerY - (height / 2.0), width, height);
        }
    }

    /**
     * Bands of the interactive viewer: amplicons of a lane that round to the same size form one
     * band, smaller bands look stronger, and each lane and band gets a small fixed variation.
     */
    static List<LaneBand> window(GelLayout layout, int lane, String sampleName, List<GelBand> laneBands,
                                 double baseBandHeight) {
        List<LaneBand> result = new ArrayList<>();
        double laneX = layout.laneX(lane);
        for (Map.Entry<Integer, List<GelBand>> entry : new TreeMap<>(GelReportReader.groupByRoundedSize(laneBands)).entrySet()) {
            int roundedSize = entry.getKey();
            List<GelBand> bands = entry.getValue();
            double laneVariation = GelScale.deterministicRange(sampleName, 0.90, 1.10);
            double verticalJitter = GelScale.deterministicRange(sampleName + ':' + roundedSize, -0.45, 0.45);
            double intensity = Math.min(1.0, GelScale.sizeIntensity(roundedSize, bands.size()) * laneVariation);
            double centerY = layout.bandY(roundedSize) + verticalJitter;
            result.add(new LaneBand(lane, laneX, GelText.primaryGene(bands), bands, centerY, intensity,
                    BandRect.inLane(laneX, centerY, layout.laneWidth(), baseBandHeight + (intensity * 1.2))));
        }
        return result;
    }

    /**
     * Bands of the PNG image: amplicons that round to the same size but come from different
     * genes are drawn as one band per gene, 3 px apart, so that no gene colour is hidden.
     */
    static List<LaneBand> image(GelLayout layout, int lane, String sampleName, List<GelBand> laneBands,
                                double baseBandHeight) {
        List<LaneBand> result = new ArrayList<>();
        double laneX = layout.laneX(lane);
        for (Map.Entry<Integer, List<GelBand>> sizeEntry : GelReportReader.groupByRoundedSize(laneBands).entrySet()) {
            int size = sizeEntry.getKey();
            double verticalJitter = GelScale.deterministicRange(sampleName + ':' + size, -0.45, 0.45);
            Map<String, List<GelBand>> byGene = new TreeMap<>();
            for (GelBand band : sizeEntry.getValue()) {
                byGene.computeIfAbsent(band.geneName(), ignored -> new ArrayList<>()).add(band);
            }
            int geneIndex = 0;
            for (Map.Entry<String, List<GelBand>> geneEntry : byGene.entrySet()) {
                double geneOffset = (geneIndex - (byGene.size() - 1) / 2.0) * 3.0;
                double intensity = GelColors.bandIntensity(geneEntry.getValue().size());
                double centerY = layout.bandY(size) + verticalJitter + geneOffset;
                result.add(new LaneBand(lane, laneX, geneEntry.getKey(), geneEntry.getValue(), centerY, intensity,
                        BandRect.inLane(laneX, centerY, layout.laneWidth(), baseBandHeight + (intensity * 1.2))));
                geneIndex++;
            }
        }
        return result;
    }

    /** Bands of a paginated-viewer page: one per rounded size, no variation, height 2 + intensity. */
    static List<LaneBand> page(GelLayout layout, int lane, List<GelBand> laneBands) {
        List<LaneBand> result = new ArrayList<>();
        double laneX = layout.laneX(lane);
        for (Map.Entry<Integer, List<GelBand>> entry : new TreeMap<>(GelReportReader.groupByRoundedSize(laneBands)).entrySet()) {
            List<GelBand> bands = entry.getValue();
            double centerY = layout.bandY(entry.getKey());
            double intensity = GelColors.bandIntensity(bands.size());
            result.add(new LaneBand(lane, laneX, GelText.primaryGene(bands), bands, centerY, intensity,
                    BandRect.inLane(laneX, centerY, layout.laneWidth(), 2.0 + intensity)));
        }
        return result;
    }

    /** Hover text of the band. */
    String tooltip() {
        return GelText.tooltip(bands);
    }
}
