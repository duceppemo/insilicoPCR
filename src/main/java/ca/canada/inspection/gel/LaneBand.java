package ca.canada.inspection.gel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One band to draw in a sample lane: the amplicons it stands for, where it sits and how strong
 * it looks. The gel window and every picture of a gel (the {@code --gel} image, the window's
 * saved PNG, SVG and PDF) place their bands with the one factory, {@link #of}.
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
     * The bands of a lane. Amplicons that round to the same size form one band, as they would run
     * together on a gel; with {@code byGene}, those of different genes are drawn as one band per
     * gene, 3 px apart, so that no gene colour is hidden. Smaller bands look stronger, and each lane
     * and band gets a small fixed variation, the same in every picture of the gel.
     */
    static List<LaneBand> of(GelLayout layout, int lane, String sampleName, List<GelBand> laneBands,
                             double baseBandHeight, boolean byGene) {
        List<LaneBand> result = new ArrayList<>();
        double laneX = layout.laneX(lane);
        double laneVariation = GelScale.deterministicRange(sampleName, 0.90, 1.10);
        for (Map.Entry<Integer, List<GelBand>> sizeEntry
                : new TreeMap<>(GelReportReader.groupByRoundedSize(laneBands)).entrySet()) {
            int size = sizeEntry.getKey();
            double verticalJitter = GelScale.deterministicRange(sampleName + ':' + size, -0.45, 0.45);
            Map<String, List<GelBand>> groups = new TreeMap<>();
            if (byGene) {
                sizeEntry.getValue().forEach(band ->
                        groups.computeIfAbsent(band.geneName(), ignored -> new ArrayList<>()).add(band));
            } else {
                groups.put(GelText.primaryGene(sizeEntry.getValue()), sizeEntry.getValue());
            }
            int index = 0;
            for (Map.Entry<String, List<GelBand>> group : groups.entrySet()) {
                double offset = (index - (groups.size() - 1) / 2.0) * 3.0;
                double intensity = Math.min(1.0, GelScale.sizeIntensity(size, group.getValue().size()) * laneVariation);
                double centerY = belowTheWells(layout, layout.bandY(size) + verticalJitter + offset);
                result.add(new LaneBand(lane, laneX, group.getKey(), group.getValue(), centerY, intensity,
                        BandRect.inLane(laneX, centerY, layout.laneWidth(), baseBandHeight + (intensity * 1.2))));
                index++;
            }
        }
        return result;
    }

    /**
     * A band no higher than just below the wells: the scale ends at 25 kb, and a larger product
     * (with {@code --max-length 0}) sat on the gel's top border, hidden under it.
     */
    private static double belowTheWells(GelLayout layout, double centerY) {
        return Math.max(layout.gelTop() + 8, centerY);
    }

    /** Hover text of the band. */
    String tooltip() {
        return GelText.tooltip(bands);
    }
}
