package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * When amplicons of different sizes are one product: the rule the read-level search, the read
 * extension and the primer file's declared sizes ({@link ExpectedSizes#TOLERANCE}) share.
 */
public final class ProductSizes {
    private ProductSizes() {
    }

    /**
     * Amplicon sizes at most this many bp apart are one product: length variants of one
     * locus (indels between gene copies or individuals, a homopolymer miscounted by the
     * sequencer). Sizes further apart are separate products with their own support.
     */
    public static final int GAP = 10;

    /**
     * Whether an amplicon of {@code size} bp is the product seen at {@code productSizes}, give
     * or take a length variant: the one rule for "the same product" when a call from reads is
     * compared with what the sample already shows.
     */
    public static boolean sameProduct(Collection<Integer> productSizes, int size) {
        int smallest = productSizes.stream().mapToInt(Integer::intValue).min().orElseThrow();
        int largest = productSizes.stream().mapToInt(Integer::intValue).max().orElseThrow();
        return size >= smallest - GAP && size <= largest + GAP;
    }

    /**
     * Splits an assay's amplicons into products: runs of sizes with no gap above
     * {@link #GAP}, by increasing size.
     */
    static List<List<ReportRow>> splitBySize(List<ReportRow> rows) {
        List<ReportRow> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(ReportRow::size));
        List<List<ReportRow>> products = new ArrayList<>();
        int previous = Integer.MIN_VALUE;
        for (ReportRow row : sorted) {
            if (products.isEmpty() || row.size() - previous > GAP) {
                products.add(new ArrayList<>());
            }
            products.getLast().add(row);
            previous = row.size();
        }
        return products;
    }
}
