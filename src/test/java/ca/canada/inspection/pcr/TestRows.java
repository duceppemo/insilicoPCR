package ca.canada.inspection.pcr;

/** Report rows for tests that need one but not the details of its primers. */
public final class TestRows {

    private TestRows() {
    }

    /** A row of a perfect {@code <assay>-F}/{@code -R} pair without probe, on the plus strand. */
    public static ReportRow row(String sample, String assay, int start, int end, String contig, String description) {
        return of(sample, assay, start, end, end - start + 1, contig, description, assay + "-F", assay + "-R",
                0, 0, 0, 0, '+', null, 0, 0);
    }

    /** A row of a contig of the assembly, its columns given in report order. */
    public static ReportRow of(String sample, String assay, int start, int end, int size, String contig,
                               String description, String forwardPrimer, String reversePrimer,
                               int forwardMismatches, int reverseMismatches, int forwardThreePrime,
                               int reverseThreePrime, char strand, ReportRow.Probe probe,
                               int forwardUnpaired, int reverseUnpaired) {
        return new ReportRow(sample, assay, new ReportRow.Site(start, end, size, contig, description, strand),
                new ReportRow.Binding(forwardPrimer, forwardMismatches, forwardThreePrime, forwardUnpaired),
                new ReportRow.Binding(reversePrimer, reverseMismatches, reverseThreePrime, reverseUnpaired),
                probe, ReportRow.Source.ASSEMBLY);
    }

    /** A row for a call whose row the test does not look at. */
    public static ReportRow any() {
        return row("S", "assay", 1, 100, "read_1", "");
    }
}
