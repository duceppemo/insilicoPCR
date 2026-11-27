package ca.canada.inspection.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a run needs of a FASTA file besides its BLAST hits, read in one pass: each sequence's description (the
 * header after its ID), its length, and the circles among them ({@link Circles}). An assembly was read once for
 * each of these, a gzipped one decompressed each time.
 *
 * @param descriptions by sequence ID, the header after the ID
 * @param lengths      by sequence ID, the number of bases
 * @param circles      by sequence ID, the length of each sequence searched as a circle
 */
public record FastaSummary(Map<String, String> descriptions, Map<String, Integer> lengths,
                           Map<String, Integer> circles) {

    public FastaSummary {
        descriptions = Map.copyOf(descriptions);
        lengths = Map.copyOf(lengths);
        circles = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(circles));
    }

    /**
     * @param tadpoleK the k of the mini-assembly, whose contigs closing on themselves repeat up to {@code k - 1}
     *                 bases of their start at their end
     * @throws IOException when the file cannot be read
     */
    public static FastaSummary of(Path fasta, int tadpoleK) throws IOException {
        Map<String, String> descriptions = new HashMap<>();
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, Integer> circles = new LinkedHashMap<>();
        int[] length = new int[1];
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            String header = scanner.nextHeader(null);
            while (header != null) {
                String id = SequenceFileUtils.fastaId(header);
                Circles.Record circle = Circles.record(header, tadpoleK);
                length[0] = 0;
                String next = scanner.nextHeader(circle == null ? base -> length[0]++ : base -> {
                    length[0]++;
                    circle.accept(base);
                });
                descriptions.put(id, SequenceFileUtils.fastaDescription(header));
                lengths.put(id, length[0]);
                int asCircle = circle == null ? 0 : circle.circle();
                if (asCircle > 0) {
                    circles.putIfAbsent(id, asCircle);
                }
                header = next;
            }
        }
        return new FastaSummary(descriptions, lengths, circles);
    }
}
