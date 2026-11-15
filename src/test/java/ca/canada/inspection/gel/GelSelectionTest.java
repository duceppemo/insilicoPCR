package ca.canada.inspection.gel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The selection rules of the gel window, without a window. */
class GelSelectionTest {

    private final List<String> shown = new ArrayList<>();
    private final GelSelection<String> selection =
            new GelSelection<>((band, selected) -> shown.add((selected ? "+" : "-") + band));

    @Test
    void aClickSelectsTheBandAloneAndCtrlAddsOrTakesItAway() {
        selection.click("a", false);
        selection.click("b", true);
        assertEquals(List.of("a", "b"), selection.bands());

        selection.click("a", true);
        assertEquals(List.of("b"), selection.bands());

        selection.click("c", false);
        assertEquals(List.of("c"), selection.bands());
        assertEquals(List.of("+a", "+b", "-a", "-b", "+c"), shown);
    }

    @Test
    void selectingTheMatchesLetsGoOfBandsThatNoLongerMatch() {
        // Ctrl+A after a new search: a band selected before, which does not match, is not kept.
        selection.click("old", false);
        selection.select(List.of("old", "x1", "y", "x2"), band -> band.startsWith("x"), false);

        assertEquals(List.of("x1", "x2"), selection.bands());
        assertFalse(selection.contains("old"));
        assertEquals(List.of("+old", "-old", "+x1", "+x2"), shown);
    }

    @Test
    void aBoxDraggedWithCtrlAddsToTheSelection() {
        selection.click("a", false);
        selection.select(List.of("a", "b"), band -> true, true);
        assertEquals(List.of("a", "b"), selection.bands());
        // A band already selected is not shown again.
        assertEquals(List.of("+a", "+b"), shown);

        selection.clear();
        assertEquals(List.of(), selection.bands());
        assertEquals(List.of("+a", "+b", "-a", "-b"), shown);
    }
}
