package ca.canada.inspection.pcr;

import ca.canada.inspection.pcr.ExpectedSizes.Label;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectedSizesTest {

    /** Headers as primer ID, description pairs. */
    private static ExpectedSizes sizes(String... idsAndDescriptions) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < idsAndDescriptions.length; i += 2) {
            headers.put(idsAndDescriptions[i], idsAndDescriptions[i + 1]);
        }
        return ExpectedSizes.fromHeaders(headers);
    }

    @Test
    void headersWithoutDeclarationGiveNone() {
        ExpectedSizes sizes = sizes("assay-F", "", "assay-R", "ITS1 reverse, Smith 2010 (Tm=60)");

        assertSame(ExpectedSizes.NONE, sizes);
        assertTrue(sizes.isEmpty());
        assertNull(sizes.label("assay", 100));
        assertEquals("", sizes.labelText("assay", 100));
        assertTrue(sizes.qaLogLines().isEmpty());
    }

    @Test
    void singleSizeMatchesLengthVariantsOfTheProduct() {
        ExpectedSizes sizes = sizes("assay-F", "size=434", "assay-R", "");

        assertEquals(Label.TARGET, sizes.label("assay", 434));
        assertEquals(Label.TARGET, sizes.label("assay", 424));
        assertEquals(Label.TARGET, sizes.label("assay", 444));
        assertEquals(Label.UNEXPECTED, sizes.label("assay", 423));
        assertEquals(Label.UNEXPECTED, sizes.label("assay", 445));
        assertEquals("unexpected", sizes.labelText("assay", 119));
    }

    @Test
    void rangeMatchesExactly() {
        ExpectedSizes sizes = sizes("assay-R", "size=1000-1100");

        assertEquals(Label.TARGET, sizes.label("assay", 1000));
        assertEquals(Label.TARGET, sizes.label("assay", 1100));
        assertEquals(Label.UNEXPECTED, sizes.label("assay", 999));
        assertEquals(Label.UNEXPECTED, sizes.label("assay", 1101));
    }

    @Test
    void otherKnownProductsAreLabelledApartFromTheTarget() {
        // Declared on different primers of the assay, next to free text, in any letter case.
        ExpectedSizes sizes = sizes("assay-F", "Nakhla 2010 size=103", "assay-R", "OTHER=453,960-980", "assay-P", "FAM");

        assertEquals(Label.TARGET, sizes.label("assay", 103));
        assertEquals(Label.OTHER, sizes.label("assay", 460));
        assertEquals(Label.OTHER, sizes.label("assay", 972));
        assertEquals(Label.UNEXPECTED, sizes.label("assay", 312));
        assertEquals("other", sizes.labelText("assay", 453));
    }

    @Test
    void targetWinsWhenASizeIsBothTargetAndOther() {
        assertEquals(Label.TARGET, sizes("assay-F", "size=100 other=105").label("assay", 104));
    }

    @Test
    void severalTargetSizesAreAllTarget() {
        ExpectedSizes sizes = sizes("assay-F", "size=159,163");

        assertEquals(Label.TARGET, sizes.label("assay", 159));
        assertEquals(Label.TARGET, sizes.label("assay", 163));
    }

    @Test
    void assaysWithoutDeclarationStayUnlabelledNextToOnesThatDeclare() {
        ExpectedSizes sizes = sizes("a-F", "size=100", "a-R", "", "b-F", "", "b-R", "");

        assertEquals(Set.of("a"), sizes.assays());
        assertNull(sizes.label("b", 100));
        assertEquals("", sizes.labelText("b", 100));
    }

    @Test
    void assayNameIsEverythingBeforeTheRoleSuffixAsInTheReport() {
        // Assay names may hold dashes; expanded degenerate IDs never reach this class.
        assertEquals(Label.TARGET, sizes("ITS-1-F", "size=72").label("ITS-1", 72));
    }

    @Test
    void sameDeclarationOnTwoPrimersIsFineButDifferentValuesAreNot() {
        assertEquals(Label.TARGET, sizes("assay-F", "size=434", "assay-R", "size=434").label("assay", 434));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sizes("assay-F", "size=434", "assay-R", "size=265"));
        assertEquals("Assay assay declares size= twice with different values: 434 on assay-F, 265 on assay-R",
                error.getMessage());
    }

    @Test
    void unreadableSizesAreRejectedNamingThePrimer() {
        for (String bad : List.of("size=abc", "size=", "size=434bp", "size=450-420", "size=0", "size=-5",
                "size=1-2-3", "other=100,,200", "size=4.5")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> sizes("assay-F", bad), bad);
            assertTrue(error.getMessage().startsWith("Primer assay-F: cannot read the product size"), error.getMessage());
            assertTrue(error.getMessage().contains("'" + bad + "'"), error.getMessage());
        }
    }

    @Test
    void qaLogLinesGiveTheRangesUsed() {
        ExpectedSizes sizes = sizes("b-F", "size=434 other=575", "a-F", "size=60-80");

        assertEquals(List.of(
                "Product sizes declared in the primer file (SizeCheck column of the report; every product is"
                        + " reported whatever its size):",
                "  a: target 60-80 bp",
                "  b: target 424-444 bp; other 565-585 bp"), sizes.qaLogLines());
    }

    @Test
    void smallSizesDoNotGoBelowOneBase() {
        assertEquals(List.of("a: target 1-15 bp"), sizes("a-F", "size=5").summaryLines());
    }
}
