package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static ca.canada.inspection.pcr.TestHits.minusHit;
import static ca.canada.inspection.pcr.TestHits.plusHit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ProductsTest {

    /** A degenerate forward primer expanded into twelve variants, each hitting the same site. */
    private static PrimerPanel degenerate() {
        Map<String, String> sequences = new LinkedHashMap<>();
        for (int i = 1; i <= 12; i++) {
            sequences.put("toxA-F_" + i, PRIMER_20);
        }
        sequences.put("toxA-R", PRIMER_20);
        return PrimerPanel.of(sequences);
    }

    @Test
    void variantsAreListedInPrimerFileOrderWhateverOrderTheHitsCameIn() {
        Sample sample = new Sample("S");
        for (int i = 12; i >= 1; i--) {
            sample.addBlastResult("toxA-F_" + i, plusHit("toxA-F_" + i, "c1", 101));
        }
        sample.addBlastResult("toxA-R", minusHit("toxA-R", "c1", 581));

        List<String> forward = Products.of(sample, degenerate(), AmpliconOptions.withMismatches(0)).stream()
                .map(ReportRow::forwardPrimer).toList();

        assertEquals(List.of("toxA-F_1", "toxA-F_2", "toxA-F_3", "toxA-F_4", "toxA-F_5", "toxA-F_6", "toxA-F_7",
                "toxA-F_8", "toxA-F_9", "toxA-F_10", "toxA-F_11", "toxA-F_12"), forward);
    }

    @Test
    void aSamplesProductsAreWorkedOutOnceUntilItsHitsChange() {
        Sample sample = new Sample("S");
        sample.addBlastResult("toxA-F_1", plusHit("toxA-F_1", "c1", 101));
        sample.addBlastResult("toxA-R", minusHit("toxA-R", "c1", 581));
        PrimerPanel primers = degenerate();
        AmpliconOptions options = AmpliconOptions.withMismatches(0);

        List<ReportRow> first = Products.of(sample, primers, options);
        assertSame(first, Products.of(sample, primers, options));
        sample.addBlastResult("toxA-F_2", plusHit("toxA-F_2", "c1", 101));
        assertEquals(2, Products.of(sample, primers, options).size());
    }
}
