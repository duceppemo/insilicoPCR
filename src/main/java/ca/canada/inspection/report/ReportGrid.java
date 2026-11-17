package ca.canada.inspection.report;

import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.SplitAcrossContigs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the HTML report shows of each sample and assay, before any HTML: the grid of cells
 * ({@link HtmlReport#cell}), in display order (samples, then assays, alphabetically), and the
 * counts its tiles give.
 *
 * @param samples       every sample of the run, and any a product names
 * @param assays        the columns: the primer file's assays, and any a product names (products of two
 *                      assays, {@code A+B})
 * @param cells         sample to assay to cell, in display order
 * @param totals        how many cells show each state
 * @param anyDeclared   whether a column's assay declares a product size
 * @param anyUndeclared whether one does not
 * @param assayCount    the primer file's assays (a column of products of two assays is not one)
 */
record ReportGrid(Set<String> samples, Set<String> assays, Map<String, Map<String, HtmlReport.Cell>> cells,
                  Map<HtmlReport.State, Integer> totals, boolean anyDeclared, boolean anyUndeclared, int assayCount) {

    /**
     * @param assays the primer file's assays
     * @param findings FASTQ-mode diagnostics, by sample and assay (the first of each)
     * @param splits   assays whose product could span a break in the assembly
     */
    static ReportGrid of(List<HtmlReport.Product> products, Collection<String> sampleNames, List<String> assays,
                         ExpectedSizes sizes, List<Finding> findings, List<SplitAcrossContigs.Split> splits) {
        Set<String> samples = new TreeSet<>(sampleNames);
        Set<String> assayOrder = new TreeSet<>(assays);
        Map<String, List<HtmlReport.Product>> byPair = new HashMap<>();
        for (HtmlReport.Product product : products) {
            samples.add(product.sample());
            assayOrder.add(product.assay());
            byPair.computeIfAbsent(key(product.sample(), product.assay()), ignored -> new ArrayList<>()).add(product);
        }
        Map<String, Finding> findingByPair = new HashMap<>();
        for (Finding finding : findings) {
            findingByPair.putIfAbsent(key(finding.sample(), finding.assay()), finding);
        }
        Map<String, SplitAcrossContigs.Split> splitByPair = new HashMap<>();
        for (SplitAcrossContigs.Split split : splits) {
            splitByPair.putIfAbsent(key(split.sample(), split.assay()), split);
        }
        Map<String, Map<String, HtmlReport.Cell>> cells = new LinkedHashMap<>();
        Map<HtmlReport.State, Integer> totals = new EnumMap<>(HtmlReport.State.class);
        for (String sample : samples) {
            Map<String, HtmlReport.Cell> row = new LinkedHashMap<>();
            for (String assay : assayOrder) {
                String key = key(sample, assay);
                HtmlReport.Cell cell = HtmlReport.cell(assay, byPair.getOrDefault(key, List.of()), sizes,
                        findingByPair.get(key), splitByPair.get(key));
                row.put(assay, cell);
                totals.merge(cell.state(), 1, Integer::sum);
            }
            cells.put(sample, row);
        }
        boolean anyDeclared = assayOrder.stream().anyMatch(sizes.assays()::contains);
        boolean anyUndeclared = assayOrder.stream().anyMatch(assay -> !sizes.assays().contains(assay));
        // The primer file's assays: a column of products of two assays (A+B) is not one (it read "6 assays" for 5).
        int assayCount = assays.isEmpty() ? assayOrder.size() : new TreeSet<>(assays).size();
        return new ReportGrid(samples, assayOrder, cells, totals, anyDeclared, anyUndeclared, assayCount);
    }

    /** The cells showing {@code state}. */
    int total(HtmlReport.State state) {
        return totals.getOrDefault(state, 0);
    }

    private static String key(String sample, String assay) {
        return sample + '\u0000' + assay;
    }
}
