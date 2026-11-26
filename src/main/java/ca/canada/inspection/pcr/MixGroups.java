package ca.canada.inspection.pcr;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which assays of a primer file share a reaction: the multiplexes, as the primer file declares them
 * with {@code mix=<name>} after a primer's ID ({@code >toxA-F mix=panel1}). The primers of the assays
 * of one group can pair with each other into products of two assays; an assay without the word is run
 * on its own, so a file can hold singleplex and multiplex assays at once.
 */
public final class MixGroups {
    /** The header word that names an assay's group. */
    static final String KEY = "mix";

    /** A primer file that declares no group: every assay is run on its own. */
    public static final MixGroups NONE = new MixGroups(Map.of());

    /** assay -> group */
    private final Map<String, String> groupOf;

    private MixGroups(Map<String, String> groupOf) {
        this.groupOf = Collections.unmodifiableMap(new HashMap<>(groupOf));
    }

    /**
     * Reads {@code mix=} from the primers' header descriptions. An assay's group may be given on any of
     * its primers, and on several if they agree.
     *
     * @param descriptions header text following the primer ID, per primer ID as written in the file
     * @throws IllegalArgumentException for an empty name, or an assay given two different groups
     */
    public static MixGroups fromHeaders(Map<String, String> descriptions) {
        // assay -> the primer that declared its group, and the group
        Map<String, String[]> declared = new TreeMap<>();
        HeaderLabels.forEach(descriptions, Set.of(KEY), label -> {
            String group = label.value();
            if (group.isEmpty()) {
                throw new IllegalArgumentException("Primer " + label.primerId() + ": " + KEY + "= needs the name of the"
                        + " reaction the assay shares with others, such as " + KEY + "=panel1");
            }
            String[] previous = declared.putIfAbsent(label.assay(), new String[]{label.primerId(), group});
            if (previous != null && !previous[1].equals(group)) {
                throw new IllegalArgumentException("Assay " + label.assay() + " declares " + KEY + "= twice with"
                        + " different values: " + previous[1] + " on " + previous[0] + ", " + group + " on "
                        + label.primerId() + " (an assay is in one reaction)");
            }
        });
        Map<String, String> groupOf = new HashMap<>();
        declared.forEach((assay, primerAndGroup) -> groupOf.put(assay, primerAndGroup[1]));
        return groupOf.isEmpty() ? NONE : new MixGroups(groupOf);
    }

    /** Whether the primer file declares no reaction: every assay is run on its own. */
    public boolean isEmpty() {
        return groupOf.isEmpty();
    }

    /** The group of {@code assay}, or {@code null} when it is run on its own. */
    public String groupOf(String assay) {
        return groupOf.get(assay);
    }

    /** Whether {@code first} and {@code second}, two assays, share a reaction. */
    public boolean shareAReaction(String first, String second) {
        String group = groupOf.get(first);
        return group != null && !first.equals(second) && group.equals(groupOf.get(second));
    }

    /** The groups, by name, with their assays by name. */
    public Map<String, Set<String>> groups() {
        Map<String, Set<String>> groups = new TreeMap<>();
        groupOf.forEach((assay, group) -> groups.computeIfAbsent(group, ignored -> new TreeSet<>()).add(assay));
        return groups;
    }

    /**
     * @throws IllegalArgumentException when a product of two assays would be named as one of {@code assays}
     *                                  ({@code a} and {@code b} in one reaction, and an assay {@code a+b}):
     *                                  its rows would be read as that assay's, with its declared sizes; or when
     *                                  an assay of a reaction has {@code +} in its ID: {@code a} with {@code b+c}
     *                                  and {@code a+b} with {@code c} were both {@code a+b+c}
     */
    public void checkNames(Set<String> assays) {
        groups().forEach((group, members) -> {
            for (String member : members) {
                // Alone in its reaction, it makes no product of two assays to be confused with.
                if (members.size() > 1 && member.contains("+")) {
                    throw new IllegalArgumentException("Assay " + member + " shares the reaction " + group + " (mix=)"
                            + " and has '+' in its ID, which joins the names of two assays in a product of both: rename"
                            + " it");
                }
            }
            for (String first : members) {
                for (String second : members) {
                    String name = crossAssayName(first, second);
                    if (!first.equals(second) && assays.contains(name)) {
                        throw new IllegalArgumentException("Assay " + name + " is named as the products of " + first
                                + " and " + second + ", which share the reaction " + group + " (mix=): rename it");
                    }
                }
            }
        });
    }

    /** For QAlog.txt: the reactions the primer file declares and their assays; none when it declares none. */
    public java.util.List<String> qaLogLines() {
        if (groupOf.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("Assays sharing a reaction (mix= in the primer file; their primers pair across assays):");
        groups().forEach((group, assays) -> lines.add("  " + group + ": " + String.join(", ", assays)));
        return lines;
    }

    /**
     * The name a product of two assays is reported under, the same whichever primer is on which strand:
     * the two names in name order ({@code stxQ+toxA}); its primers are in the row's primer columns.
     */
    public static String crossAssayName(String first, String second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        return first.compareTo(second) <= 0 ? first + "+" + second : second + "+" + first;
    }
}
