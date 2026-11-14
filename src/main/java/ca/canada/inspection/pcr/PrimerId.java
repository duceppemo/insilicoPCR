package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A primer ID read by the naming convention {@code <assay>-F}, {@code <assay>-R} or
 * {@code <assay>-P}: the assay is everything before the last dash, and the letter after it
 * is the primer's role. What follows the letter is kept in the ID only, as for the
 * {@code _1}, {@code _2} of a degenerate primer's expanded sequences ({@code assay-F_1}).
 * The one place that rule is written.
 */
public record PrimerId(String assay, Role role) {

    /** What a primer does in its assay, by the letter after the last dash. */
    public enum Role {
        FORWARD('F'), REVERSE('R'), PROBE('P');

        private final char letter;

        Role(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }

        static Role of(char letter) {
            for (Role role : values()) {
                if (role.letter == letter) {
                    return role;
                }
            }
            return null;
        }
    }

    /** The ID read by the convention, or {@code null} when it does not follow it. */
    public static PrimerId parse(String id) {
        Role role = roleOf(id);
        return role == null ? null : new PrimerId(id.substring(0, id.lastIndexOf('-')), role);
    }

    /** True when the ID names a probe, such as {@code assay-P} or {@code assay-P_3}. */
    public static boolean isProbe(String id) {
        return roleOf(id) == Role.PROBE;
    }

    /**
     * The role of {@code id}, or {@code null} when it does not follow the convention; as
     * {@link #parse} reads it, without making the assay name (it runs once per BLAST hit).
     */
    public static Role roleOf(String id) {
        int lastDash = id.lastIndexOf('-');
        if (lastDash <= 0 || lastDash == id.length() - 1) {
            return null;
        }
        return Role.of(id.charAt(lastDash + 1));
    }

    /** The primer IDs of one assay, by role, as written (or as expanded). */
    public record Assay(List<String> forward, List<String> reverse, List<String> probe) {

        public boolean hasProbe() {
            return !probe.isEmpty();
        }

        /** Both a forward and a reverse primer: the least a product needs. */
        public boolean isComplete() {
            return !forward.isEmpty() && !reverse.isEmpty();
        }

        List<String> of(Role role) {
            return switch (role) {
                case FORWARD -> forward;
                case REVERSE -> reverse;
                case PROBE -> probe;
            };
        }
    }

    /** Groups primer IDs by assay, sorted by assay name; IDs that do not follow the convention are left out. */
    public static Map<String, Assay> byAssay(Iterable<String> ids) {
        Map<String, Assay> assays = new TreeMap<>();
        for (String id : ids) {
            PrimerId parsed = parse(id);
            if (parsed != null) {
                assays.computeIfAbsent(parsed.assay(),
                                ignored -> new Assay(new ArrayList<>(), new ArrayList<>(), new ArrayList<>()))
                        .of(parsed.role()).add(id);
            }
        }
        return assays;
    }
}
