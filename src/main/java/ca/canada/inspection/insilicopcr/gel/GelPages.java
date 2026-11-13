package ca.canada.inspection.insilicopcr.gel;

import java.util.List;
import java.util.Map;

/** Splits the lanes of a large run into gel pages of {@code samplesPerGel} samples. */
record GelPages(List<String> sampleNames, int samplesPerGel) {

    /** Result of a search across all pages: the first page with a match (-1 for none) and the match count. */
    record Search(int pageIndex, int matches) {
    }

    GelPages {
        sampleNames = List.copyOf(sampleNames);
        if (samplesPerGel < 1) {
            throw new IllegalArgumentException("samplesPerGel must be >= 1");
        }
    }

    int totalPages() {
        return Math.max(1, (int) Math.ceil((double) sampleNames.size() / samplesPerGel));
    }

    int startIndex(int pageIndex) {
        return pageIndex * samplesPerGel;
    }

    int endIndex(int pageIndex) {
        return Math.min(sampleNames.size(), startIndex(pageIndex) + samplesPerGel);
    }

    /** Samples on page {@code pageIndex}, in lane order. */
    List<String> samples(int pageIndex) {
        return sampleNames.subList(startIndex(pageIndex), endIndex(pageIndex));
    }

    /** "Samples 101–200 of 250 (first ... last)". */
    String rangeText(int pageIndex) {
        int start = startIndex(pageIndex);
        int end = endIndex(pageIndex);
        return "Samples " + (start + 1) + "–" + end + " of " + sampleNames.size()
                + " (" + sampleNames.get(start) + " ... " + sampleNames.get(end - 1) + ")";
    }

    /** Samples whose name, or one of whose genes or amplicon sizes, contains {@code query}. */
    Search find(Map<String, List<GelBand>> lanes, String query) {
        if (query == null || query.isBlank()) {
            return new Search(-1, 0);
        }
        String normalized = GelText.normalizeQuery(query);
        int first = -1;
        int count = 0;
        for (int i = 0; i < sampleNames.size(); i++) {
            String sampleName = sampleNames.get(i);
            boolean matches = sampleName.toLowerCase().contains(normalized)
                    || lanes.getOrDefault(sampleName, List.of()).stream().anyMatch(b ->
                    b.geneName().toLowerCase().contains(normalized)
                            || Integer.toString(b.ampliconSize()).contains(normalized));
            if (matches) {
                count++;
                if (first < 0) {
                    first = i / samplesPerGel;
                }
            }
        }
        return new Search(first, count);
    }
}
