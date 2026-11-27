package ca.canada.inspection.gel;

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

    /** The lanes of page {@code pageIndex}, in lane order, with their bands from {@code lanes}. */
    java.util.LinkedHashMap<String, List<GelBand>> lanes(int pageIndex, Map<String, List<GelBand>> lanes) {
        java.util.LinkedHashMap<String, List<GelBand>> page = new java.util.LinkedHashMap<>();
        samples(pageIndex).forEach(name -> page.put(name, lanes.getOrDefault(name, List.of())));
        return page;
    }

    /** "Samples 101–200 of 250 (first ... last)". */
    String rangeText(int pageIndex) {
        int start = startIndex(pageIndex);
        int end = endIndex(pageIndex);
        return "Samples " + (start + 1) + "–" + end + " of " + sampleNames.size()
                + " (" + sampleNames.get(start) + " ... " + sampleNames.get(end - 1) + ")";
    }

    /**
     * The bands of every page that {@code query} matches, as the viewer matches them on the page
     * it shows: a band, drawn as {@code mode} draws it ({@link LaneBand#groups}), matches when its
     * sample name, genes, sizes, size checks or primer and probe IDs hold the query
     * ({@link GelText#searchText}). A lane
     * without a band matches nothing, as on the page.
     */
    Search find(Map<String, List<GelBand>> lanes, String query, GelColorMode mode) {
        String normalized = GelText.normalizeQuery(query);
        if (normalized.isBlank()) {
            return new Search(-1, 0);
        }
        int first = -1;
        int count = 0;
        for (int i = 0; i < sampleNames.size(); i++) {
            for (List<GelBand> band : LaneBand.groups(lanes.getOrDefault(sampleNames.get(i), List.of()), mode)
                    .values().stream().flatMap(List::stream).toList()) {
                if (GelText.searchText(band).contains(normalized)) {
                    count++;
                    if (first < 0) {
                        first = i / samplesPerGel;
                    }
                }
            }
        }
        return new Search(first, count);
    }
}
