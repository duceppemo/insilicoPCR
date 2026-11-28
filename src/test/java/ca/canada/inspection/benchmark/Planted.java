package ca.canada.inspection.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;

/**
 * A copy of an assay's product as it is written into a sample, with one or both primer sites changed, and what the
 * rule says of the changed sites.
 *
 * @param template the product's template in the sample, in the forward primer's sense, with {@link #FLANK} random
 *                 bases on each side: the rule is judged with the bases beside the site, which must be known
 * @param verdict  what {@link Rule} says of the product: accepted when every changed site is, rejected when one is
 * @param note     what was changed and each changed site's Tm, for the scorecard:
 *                 {@code F: 7G>A 12C>T; site Tm 57.2 (56.8 to 58.1); accept: Tm}
 */
record Planted(String template, Rule.Verdict verdict, String note) {

    /** Random bases written on each side of a planted product. */
    static final int FLANK = 4;

    /** Product bases beyond a primer's 3' end that the rule's windows take in. */
    private static final int INSIDE = 2;

    /** How a primer site differs from its primer. */
    enum Kind {
        /** Substitutions anywhere outside the 3' window. */
        SUBSTITUTIONS,
        /**
         * Two substitutions, on the 8th base from each end of the primer: with primers of up to 24 bases, no 17
         * bases in a row of the primer are then within one difference of the site, whichever base of the 17 is left
         * out of the comparison. A search of reads by such words finds no read of the site (Limitations, "Known
         * gaps"), though a hot primer still melts above the minimum on it.
         */
        EIGHTH_FROM_ENDS,
        /** Substitutions within the first six bases of the primer's 5' end. */
        FIVE_PRIME,
        /** One substitution on the primer's last base. */
        THREE_PRIME_END,
        /** One substitution within the 3' window, off the last base. */
        THREE_PRIME_WINDOW,
        /** A primer base the template lacks, at least 8 bases from the 3' end and 3 from the 5' end. */
        DELETION,
        /** A template base the primer lacks, likewise. */
        INSERTION
    }

    /** Which sites are changed. */
    enum Sites {
        FORWARD, REVERSE, BOTH
    }

    /** A primer's site after its change, in the primer's own sense, and what was done. */
    private record Changed(String sense, String changes) {
    }

    /** The product unchanged. */
    static Planted perfect(Random random, Assay assay, Rule rule) {
        return of(random, assay, new Changed(assay.forward(), "perfect"), null, rule);
    }

    /** The product of {@code assay} with {@code count} changes of {@code kind} in each site of {@code sites}. */
    static Planted changed(Random random, Assay assay, Sites sites, Kind kind, int count, Rule rule) {
        Changed forward = sites == Sites.REVERSE ? null : change(random, assay.forward(), kind, count, rule);
        Changed reverse = sites == Sites.FORWARD ? null : change(random, assay.reverse(), kind, count, rule);
        return of(random, assay, forward, reverse, rule);
    }

    private static Planted of(Random random, Assay assay, Changed forward, Changed reverse, Rule rule) {
        String product = assay.product();
        String left = Seq.random(random, FLANK);
        String right = Seq.random(random, FLANK);
        int forwardLength = assay.forward().length();
        int reverseLength = assay.reverse().length();
        String middle = product.substring(forwardLength, product.length() - reverseLength);
        String template = left + (forward == null ? assay.forward() : forward.sense()) + middle
                + Seq.reverseComplement(reverse == null ? assay.reverse() : reverse.sense()) + right;
        List<String> notes = new ArrayList<>();
        List<Rule.Verdict> verdicts = new ArrayList<>();
        // The bases beside a site, in its primer's sense: outside the product before the primer's 5' end, inside
        // it after its 3' end.
        if (forward != null) {
            Rule.Site site = rule.judge(assay.forward(), forward.sense(), left, middle.substring(0, INSIDE), true);
            notes.add("F: " + forward.changes() + "; " + site.text());
            verdicts.add(site.verdict());
        }
        if (reverse != null) {
            Rule.Site site = rule.judge(assay.reverse(), reverse.sense(), Seq.reverseComplement(right),
                    Seq.reverseComplement(middle.substring(middle.length() - INSIDE)), true);
            notes.add("R: " + reverse.changes() + "; " + site.text());
            verdicts.add(site.verdict());
        }
        Rule.Verdict verdict = verdicts.contains(Rule.Verdict.REJECT) ? Rule.Verdict.REJECT
                : verdicts.contains(Rule.Verdict.BORDERLINE) ? Rule.Verdict.BORDERLINE : Rule.Verdict.ACCEPT;
        return new Planted(template, verdict, String.join(" | ", notes));
    }

    private static Changed change(Random random, String primer, Kind kind, int count, Rule rule) {
        int length = primer.length();
        StringBuilder sense = new StringBuilder(primer);
        List<String> changes = new ArrayList<>();
        switch (kind) {
            case SUBSTITUTIONS, FIVE_PRIME -> {
                int span = kind == Kind.FIVE_PRIME ? 6 : length - rule.window();
                TreeSet<Integer> positions = new TreeSet<>();
                while (positions.size() < Math.min(count, span)) {
                    positions.add(random.nextInt(span));
                }
                for (int position : positions) {
                    changes.add(substitute(random, sense, position));
                }
            }
            case EIGHTH_FROM_ENDS -> {
                changes.add(substitute(random, sense, 7));
                changes.add(substitute(random, sense, length - 8));
            }
            case THREE_PRIME_END -> changes.add(substitute(random, sense, length - 1));
            case THREE_PRIME_WINDOW -> changes.add(substitute(random, sense,
                    length - 2 - random.nextInt(rule.window() - 1)));
            case DELETION -> {
                int position = 3 + random.nextInt(length - 11);
                changes.add((position + 1) + "del" + sense.charAt(position));
                sense.deleteCharAt(position);
            }
            case INSERTION -> {
                int position = 3 + random.nextInt(length - 11);
                // Not the base on either side: the insertion would be read as a longer run of it.
                char base;
                do {
                    base = Seq.random(random, 1).charAt(0);
                } while (base == sense.charAt(position) || base == sense.charAt(position - 1));
                sense.insert(position, base);
                changes.add((position + 1) + "ins" + base);
            }
        }
        return new Changed(sense.toString(), String.join(" ", changes));
    }

    /** Puts another base at {@code position} (0-based from the primer's 5' end) and names the change. */
    private static String substitute(Random random, StringBuilder sense, int position) {
        char was = sense.charAt(position);
        char now = Seq.other(random, was);
        sense.setCharAt(position, now);
        return (position + 1) + String.valueOf(was) + ">" + now;
    }
}
