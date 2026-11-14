package ca.canada.inspection.pcr;

/** Report rows for tests that need one but not the details of its primers. */
public final class TestRows {

    private TestRows() {
    }

    /** A row of a perfect {@code <assay>-F}/{@code -R} pair without probe, on the plus strand. */
    public static ReportRow row(String sample, String assay, int start, int end, String contig, String description) {
        return new ReportRow(sample, assay, start, end, end - start + 1, contig, description, assay + "-F",
                assay + "-R", 0, 0, 0, 0, '+', null, 0, 0);
    }

    /** A row for a call whose row the test does not look at. */
    public static ReportRow any() {
        return row("S", "assay", 1, 100, "read_1", "");
    }
}
