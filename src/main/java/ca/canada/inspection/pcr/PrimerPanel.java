package ca.canada.inspection.pcr;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The primers of a run, read once: each oligo's sequence by its ID (degenerate primers
 * expanded, {@code assay-F_0}, {@code assay-F_1}), the assays they form, which of them have
 * a probe, and the product sizes the primer file declares.
 */
public final class PrimerPanel {

    private final Map<String, String> sequences;
    private final Map<String, PrimerId.Assay> assays;
    private final Set<String> assaysWithProbe;
    private final ExpectedSizes expectedSizes;
    private final MixGroups mixGroups;

    private PrimerPanel(Map<String, String> sequences, ExpectedSizes expectedSizes, MixGroups mixGroups) {
        this.sequences = Collections.unmodifiableMap(new LinkedHashMap<>(sequences));
        // Read-only all the way down: every step shares these groups.
        Map<String, PrimerId.Assay> grouped = new LinkedHashMap<>();
        PrimerId.byAssay(this.sequences.keySet()).forEach((assay, primers) -> grouped.put(assay, new PrimerId.Assay(
                List.copyOf(primers.forward()), List.copyOf(primers.reverse()), List.copyOf(primers.probe()))));
        // Those that can give a product, a forward and a reverse primer: PrimerService rejects a primer file with
        // any other, and a panel built in a test may hold fewer primers (two maps, of all and of these, differed
        // in tests only, and the steps used either).
        grouped.values().removeIf(primers -> !primers.isComplete());
        this.assays = Collections.unmodifiableMap(grouped);
        Set<String> withProbe = new TreeSet<>();
        assays.forEach((assay, primers) -> {
            if (primers.hasProbe()) {
                withProbe.add(assay);
            }
        });
        this.assaysWithProbe = Collections.unmodifiableSet(withProbe);
        this.expectedSizes = Objects.requireNonNull(expectedSizes, "expectedSizes");
        this.mixGroups = Objects.requireNonNull(mixGroups, "mixGroups");
    }

    /** A panel declaring no product sizes. */
    public static PrimerPanel of(Map<String, String> sequences) {
        return of(sequences, ExpectedSizes.NONE);
    }

    /** A panel whose assays are each run on their own. */
    public static PrimerPanel of(Map<String, String> sequences, ExpectedSizes expectedSizes) {
        return of(sequences, expectedSizes, MixGroups.NONE);
    }

    public static PrimerPanel of(Map<String, String> sequences, ExpectedSizes expectedSizes, MixGroups mixGroups) {
        return new PrimerPanel(sequences, expectedSizes, mixGroups);
    }

    /** Sequences by oligo ID, in the order of the primer file. */
    public Map<String, String> sequences() {
        return sequences;
    }

    public Set<String> ids() {
        return sequences.keySet();
    }

    /** The sequence of an oligo, or {@code null} when the panel has none of that ID. */
    public String sequence(String id) {
        return sequences.get(id);
    }

    /** The length of an oligo, 0 when the panel has none of that ID. */
    public int length(String id) {
        String sequence = sequences.get(id);
        return sequence == null ? 0 : sequence.length();
    }

    /** The assays that can give a product (a forward and a reverse primer), by name in name order, with their oligo IDs by role. */
    public Map<String, PrimerId.Assay> assays() {
        return assays;
    }

    /**
     * Assays with a probe in the primer file. Such an assay needs its probe within the product,
     * while an assay without one is reported on its primers alone, so both kinds can be mixed.
     */
    public Set<String> assaysWithProbe() {
        return assaysWithProbe;
    }

    /** Whether the report has probe columns: at least one assay has a probe. */
    public boolean anyProbe() {
        return !assaysWithProbe.isEmpty();
    }

    /** The product sizes the primer file declares ({@code size=}, {@code other=}). */
    public ExpectedSizes expectedSizes() {
        return expectedSizes;
    }

    /** Which assays share a reaction ({@code mix=}), and so pair across assays. */
    public MixGroups mixGroups() {
        return mixGroups;
    }
}
