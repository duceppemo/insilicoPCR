package ca.canada.inspection.gel;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The bands selected on the gel shown, kept apart from how they are drawn: the rules of a click,
 * a drag and Ctrl+A in one place, tested without a window.
 *
 * @param <B> a band as the window draws it
 */
final class GelSelection<B> {
    private final Set<B> selected = new LinkedHashSet<>();
    private final BiConsumer<B, Boolean> show;

    /** @param show told each band whose state changes, and whether it is now selected */
    GelSelection(BiConsumer<B, Boolean> show) {
        this.show = show;
    }

    boolean contains(B band) {
        return selected.contains(band);
    }

    /** The selected bands, in the order they were selected. */
    List<B> bands() {
        return List.copyOf(selected);
    }

    /** Lets go of every band. */
    void clear() {
        List<B> released = List.copyOf(selected);
        selected.clear();
        released.forEach(band -> show.accept(band, false));
    }

    /**
     * A click on {@code band}: it alone is selected, or, {@code adding} (Ctrl or Shift held), it is
     * added to the selection, or taken from it when it is already in.
     */
    void click(B band, boolean adding) {
        if (!adding) {
            clear();
            add(band);
        } else if (selected.remove(band)) {
            show.accept(band, false);
        } else {
            add(band);
        }
    }

    /**
     * The bands of {@code bands} that {@code chosen} keeps (those in a dragged box, or those
     * matching the search for Ctrl+A): they alone, or, {@code adding}, added to the selection.
     * Bands selected before and not chosen are let go unless adding.
     */
    void select(Collection<B> bands, Predicate<B> chosen, boolean adding) {
        if (!adding) {
            clear();
        }
        bands.stream().filter(chosen).forEach(this::add);
    }

    private void add(B band) {
        if (selected.add(band)) {
            show.accept(band, true);
        }
    }
}
