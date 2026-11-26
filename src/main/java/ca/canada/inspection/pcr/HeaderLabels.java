package ca.canada.inspection.pcr;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code key=value} words of primer headers ({@code size=}, {@code other=}, {@code mix=}, {@code ta=}): the one
 * place they are split from the header text. The key is read in any case; the value as written.
 */
public final class HeaderLabels {

    /** Every key the program reads, lower case, in the order the docs give them: size, other, mix, ta. */
    public static final List<String> KEYS = List.of(ExpectedSizes.TARGET_KEY, ExpectedSizes.OTHER_KEY, MixGroups.KEY,
            AnnealTemps.KEY);

    /** {@link #KEYS} as a message lists them: {@code size=, other=, mix= and ta=}. */
    public static String listed() {
        List<String> words = KEYS.stream().map(key -> key + "=").toList();
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.getLast();
    }

    private HeaderLabels() {
    }

    /** The words of a header's text, as every reader of it splits them. */
    public static String[] words(String description) {
        return description.trim().split("\\s+");
    }

    /**
     * One {@code key=value} word of a header.
     *
     * @param primerId the primer whose header holds it, as written in the file
     * @param assay    the assay of that primer (the primer ID when it names none)
     * @param key      the key, lower case
     * @param value    the value, as written (empty for {@code key=})
     * @param word     the whole word, as written
     */
    record Label(String primerId, String assay, String key, String value, String word) {
    }

    /**
     * Gives {@code action} every {@code key=value} word whose key is one of {@code keys} (lower case), primer by
     * primer in the order of {@code descriptions}, word by word.
     *
     * @param descriptions header text following the primer ID, per primer ID as written in the file
     */
    static void forEach(Map<String, String> descriptions, Set<String> keys, Consumer<Label> action) {
        descriptions.forEach((primerId, description) -> {
            PrimerId parsed = PrimerId.parse(primerId);
            String assay = parsed == null ? primerId : parsed.assay();
            for (String word : words(description)) {
                int equals = word.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String key = word.substring(0, equals).toLowerCase(Locale.ROOT);
                if (keys.contains(key)) {
                    action.accept(new Label(primerId, assay, key, word.substring(equals + 1), word));
                }
            }
        });
    }
}
