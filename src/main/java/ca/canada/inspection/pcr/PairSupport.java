package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Checks a product found on extended reads against the sample's read pairs (FASTQ mode).
 *
 * <p>Extending a read follows the better-supported branch at every fork. Where two loci
 * share a repeat, the walk can enter the repeat at one locus and leave it at the other,
 * and it does so from both primers alike, so reads extended from both sides agree on a
 * product that does not exist ({@link ReadExtensionCaller} cannot tell). Read pairs can:
 * a pair is one DNA fragment, and no fragment of the sample spans a junction the sample
 * does not have.</p>
 *
 * <p>Two rules, in this order:</p>
 * <ol>
 * <li><b>Read-pair support.</b> Every read pair is placed on the product (both mates,
 * facing each other) and each window of W bases must lie inside enough fragments. W is
 * the largest window that the sample's fragments and depth still fill
 * ({@link #MIN_MEDIAN_FRAGMENTS}, {@link #WINDOW_MEDIAN_SHARE}).</li>
 * <li><b>Repeat longer than the window.</b> Pairs cannot bridge a repeat longer than
 * themselves: every window is then held by fragments of one locus or the other, for a
 * real product and for a wrong turn alike. Such a repeat is seen where reads of its
 * other copy enter and leave the product: they match the product over the repeat only.
 * The product is then withheld.</li>
 * </ol>
 *
 * <p>The thresholds come from a benchmark of simulated 2 x 250 bp pairs (fragments of
 * 450 +/- 60 bp) from a bacterial genome edited to hold products of 460 to 1750 bp, real
 * products through a repeat with a second copy elsewhere, and traps where a wrong turn
 * through a repeat of 40 to 1000 bp gives a product of the declared size, at 60x and
 * 20x. Without this check the extension called 1 to 4 false products per run, each held
 * by most of the extended reads of both sides.</p>
 */
public final class PairSupport {

    /** Smallest window tried, and the step to the next ones (100, 150, 200, ...). */
    public static final int MIN_WINDOW = 100;
    public static final int WINDOW_STEP = 50;

    /**
     * Fragments that must hold the median window for a window size to be used. At 60x
     * the benchmark fills 300 to 350-base windows and at 20x 200 to 300-base ones; a
     * fixed 300 rejected two real products at 20x, and a fixed 150 let a 300 bp trap
     * through at 60x.
     */
    public static final int MIN_MEDIAN_FRAGMENTS = 10;

    /**
     * The median window must also be held by this share of the fragments holding the
     * median window of the smallest size. A window close to the fragment length is held
     * only by the longest fragments, and their number varies too much from window to
     * window: with 450 bp fragments at 60x, a real 790 bp product had a 400-base window
     * held by 1 pair, against 10 for the median 400-base window (22% of the 45 holding
     * the median 100-base window); at 350 bases the figures were 6 and 16 (36%).
     */
    public static final double WINDOW_MEDIAN_SHARE = 0.30;

    /**
     * A window held by fewer fragments than this, or than {@link #MIN_WINDOW_SHARE} of
     * the median, is not supported. In the benchmark the weakest window of a real
     * product kept 3 fragments of a median of 11, or more; across a wrong junction it
     * kept 0 or 1.
     */
    public static final int MIN_WINDOW_FRAGMENTS = 2;
    public static final double MIN_WINDOW_SHARE = 0.10;

    /**
     * Reads that must enter the product at one position, and leave it at another, to mark
     * the two ends of a repeat with another copy elsewhere: at least this many, and this
     * share of the product's median depth. Reads of the other copy match the product
     * over the repeat and stop matching at its ends, all at the same base; in the
     * benchmark 10 or more reads do at 20x, 30 or more at 60x. A sequencing error ends a
     * match anywhere, and a small insertion or deletion ends matches in both directions
     * at one position, which is not counted ({@link #MAX_OPPOSITE_SHARE}). Read depth is
     * not used: at these coverages it varies by a quarter from place to place, and a
     * repeat with two copies measured from 1.2 to 2.1 times its flanks.
     */
    public static final int MIN_BOUNDARY_READS = 4;
    public static final double MIN_BOUNDARY_SHARE = 0.15;

    /** Reads ending their match the other way at a repeat end, relative to those that mark it, at most. */
    static final double MAX_OPPOSITE_SHARE = 0.5;

    /** Bases a match may end from a position and still count for it. */
    private static final int BOUNDARY_SPREAD = 3;

    /** Unmatched bases of a read, lying on the product, for its match to count as ending there. */
    static final int MIN_CLIPPED = 20;

    /** Length of the exact matches that anchor a read on a product. */
    static final int SEED = 16;

    /** Mismatches tolerated over the part of a read lying on the product. */
    static final double MAX_MISMATCH_SHARE = 0.03;

    /** A read must lie on the product over at least this many bases to be placed. */
    static final int MIN_PLACED = 40;

    /** What the read pairs say about a product. */
    public enum Kind {
        /** Every window lies inside enough fragments, and no long repeat was seen. */
        SUPPORTED,
        /** Some window lies inside too few fragments: read pairs do not span the product there. */
        UNSUPPORTED_WINDOW,
        /** Too few read pairs lie on the product to judge it. */
        TOO_FEW_PAIRS,
        /** The product passes through a repeat longer than the fragments. */
        UNRESOLVED_REPEAT,
        /** The product has not been checked. */
        NOT_CHECKED
    }

    /**
     * @param paired       whether fragments are read pairs; single reads otherwise
     * @param window       window size used, in bases (0 when not checked)
     * @param weakest      fragments holding the weakest window
     * @param weakestStart 1-based start of the weakest window on the product
     * @param median       fragments holding the median window
     * @param repeatStart  1-based start of the repeat stretch (0 when none)
     * @param repeatEnd    1-based end of the repeat stretch
     * @param repeatEntering reads of the other copy whose match starts at the repeat's start
     * @param repeatLeaving  reads of the other copy whose match ends at the repeat's end
     * @param fragments    fragments placed on the product
     */
    public record Verdict(Kind kind, boolean paired, int window, int weakest, int weakestStart, int median,
                          int repeatStart, int repeatEnd, int repeatEntering, int repeatLeaving, int fragments) {

        /** No check was made. */
        public static final Verdict NOT_CHECKED = new Verdict(Kind.NOT_CHECKED, true, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** Whether the product may be reported. */
        public boolean passes() {
            return kind == Kind.SUPPORTED;
        }

        private String unit(int count) {
            return paired ? (count == 1 ? "read pair" : "read pairs") : (count == 1 ? "read" : "reads");
        }

        /** One clause for reports and logs, e.g. {@code every 300-base window held by at least 9 read pairs}. */
        public String summary() {
            return switch (kind) {
                case SUPPORTED -> "every " + window + "-base window held by at least " + weakest + " " + unit(weakest);
                case UNSUPPORTED_WINDOW -> "the " + window + "-base window at " + weakestStart + "-"
                        + (weakestStart + window - 1) + " is held by " + weakest + " " + unit(weakest)
                        + ", against " + median + " for the median window";
                case TOO_FEW_PAIRS -> "too few " + unit(2) + " to verify it: the median " + window
                        + "-base window is held by " + median + ", " + MIN_MEDIAN_FRAGMENTS + " needed";
                case UNRESOLVED_REPEAT -> "bases " + repeatStart + "-" + repeatEnd + " are a repeat with another"
                        + " copy in the sample (" + repeatEntering + " reads of that copy enter the product there and "
                        + repeatLeaving + " leave it), longer than the " + window + "-base window the " + unit(2)
                        + " can verify: a real product and a wrong turn look the same there";
                case NOT_CHECKED -> "not checked against " + unit(2);
            };
        }
    }

    /**
     * Where a read lies on a product: 0-based half-open, in the product's orientation.
     *
     * @param whole      the whole part of the read lying on the product matches it
     * @param entersHere the match starts inside the product although the read goes on to its left
     * @param leavesHere the match ends inside the product although the read goes on to its right
     */
    record Placement(int product, boolean plusStrand, int start, int end, boolean whole, boolean entersHere,
                     boolean leavesHere) {
    }

    private PairSupport() {
    }

    /**
     * Places reads on a set of products and counts, per product, the fragments and the
     * read depth. Not thread-safe.
     */
    public static final class Tally {
        private final List<String> products;
        private final Map<Integer, int[]> seeds = new HashMap<>();
        private final List<List<int[]>> fragments = new ArrayList<>();
        private final List<int[]> depthChanges = new ArrayList<>();
        private final List<int[]> entering = new ArrayList<>();
        private final List<int[]> leaving = new ArrayList<>();
        private boolean paired;

        /** @param products product sequences, each in any one orientation */
        public Tally(List<String> products) {
            this.products = products.stream().map(sequence -> sequence.toUpperCase(Locale.ROOT)).toList();
            for (int p = 0; p < this.products.size(); p++) {
                String sequence = this.products.get(p);
                fragments.add(new ArrayList<>());
                depthChanges.add(new int[sequence.length() + 1]);
                entering.add(new int[sequence.length() + 1]);
                leaving.add(new int[sequence.length() + 1]);
                index(p, sequence, true);
                index(p, reverseComplement(sequence), false);
            }
        }

        private void index(int product, String strand, boolean plus) {
            for (int i = 0; i + SEED <= strand.length(); i++) {
                int key = encode(strand, i);
                if (key >= 0) {
                    // {product, strand, position}, appended to the entries of this seed
                    int[] old = seeds.get(key);
                    int[] entries = old == null ? new int[3] : Arrays.copyOf(old, old.length + 3);
                    entries[entries.length - 3] = product;
                    entries[entries.length - 2] = plus ? 1 : 0;
                    entries[entries.length - 1] = i;
                    seeds.put(key, entries);
                }
            }
        }

        /** Adds the two reads of a pair, as sequenced. */
        public void addPair(String mate1, String mate2) {
            paired = true;
            Placement[] first = place(mate1);
            Placement[] second = place(mate2);
            for (int p = 0; p < products.size(); p++) {
                Placement a = first[p];
                Placement b = second[p];
                cover(a);
                cover(b);
                if (a == null || b == null || !a.whole() || !b.whole() || a.plusStrand() == b.plusStrand()) {
                    continue;
                }
                Placement left = a.plusStrand() ? a : b;
                Placement right = a.plusStrand() ? b : a;
                // Facing each other: the mate read forward starts the fragment, the other ends it.
                if (left.start() <= right.start() && left.end() <= right.end()) {
                    fragments.get(p).add(new int[]{left.start(), right.end()});
                }
            }
        }

        /** Adds an unpaired read: the read is the fragment. */
        public void addRead(String read) {
            Placement[] placements = place(read);
            for (int p = 0; p < products.size(); p++) {
                cover(placements[p]);
                if (placements[p] != null && placements[p].whole()) {
                    fragments.get(p).add(new int[]{placements[p].start(), placements[p].end()});
                }
            }
        }

        private void cover(Placement placement) {
            if (placement != null) {
                int[] changes = depthChanges.get(placement.product());
                changes[placement.start()]++;
                changes[placement.end()]--;
                if (placement.entersHere()) {
                    entering.get(placement.product())[placement.start()]++;
                }
                if (placement.leavesHere()) {
                    leaving.get(placement.product())[placement.end()]++;
                }
            }
        }

        /** The best placement of a read on each product, or {@code null} where it does not lie. */
        Placement[] place(String read) {
            Placement[] best = new Placement[products.size()];
            String upper = read.toUpperCase(Locale.ROOT);
            int length = upper.length();
            if (length < SEED) {
                return best;
            }
            for (int offset : new int[]{0, (length - SEED) / 2, length - SEED}) {
                int key = encode(upper, offset);
                int[] entries = key < 0 ? null : seeds.get(key);
                if (entries == null) {
                    continue;
                }
                for (int e = 0; e < entries.length; e += 3) {
                    int product = entries[e];
                    if (best[product] != null && best[product].whole()) {
                        continue;
                    }
                    Placement placement = align(upper, offset, product, entries[e + 1] == 1, entries[e + 2]);
                    if (placement != null && (best[product] == null || placement.whole()
                            || placement.end() - placement.start() > best[product].end() - best[product].start())) {
                        best[product] = placement;
                    }
                }
            }
            return best;
        }

        /**
         * Lays the read on one strand of a product along the diagonal of a seed match,
         * without gaps. Whole: the part of the read on the product matches within
         * {@link #MAX_MISMATCH_SHARE}. Otherwise the stretch around the seed that does is
         * kept, for the depth only: a read of another copy of a repeat matches the repeat
         * and not what follows it.
         */
        private Placement align(String read, int readOffset, int product, boolean plus, int strandPosition) {
            String sequence = products.get(product);
            int size = sequence.length();
            int shift = strandPosition - readOffset; // read base i lies on strand base i + shift
            int from = Math.max(0, -shift);
            int to = Math.min(read.length(), size - shift);
            if (to - from < MIN_PLACED) {
                return null;
            }
            int mismatches = 0;
            for (int i = from; i < to; i++) {
                if (read.charAt(i) != strandBase(sequence, plus, i + shift)) {
                    mismatches++;
                }
            }
            int start = from;
            int end = to;
            boolean whole = mismatches <= MAX_MISMATCH_SHARE * (to - from);
            if (!whole) {
                // Grow from the seed while the stretch stays within the mismatch share.
                start = readOffset;
                end = readOffset + SEED;
                int[] grown = grow(read, sequence, plus, shift, from, to, start, end);
                start = grown[0];
                end = grown[1];
                if (end - start < MIN_PLACED) {
                    return null;
                }
            }
            boolean clippedBefore = start - from >= MIN_CLIPPED;
            boolean clippedAfter = to - end >= MIN_CLIPPED;
            int a = start + shift;
            int b = end + shift;
            return plus ? new Placement(product, true, a, b, whole, clippedBefore, clippedAfter)
                    : new Placement(product, false, size - b, size - a, whole, clippedAfter, clippedBefore);
        }

        /** Extends [start, end) over the diagonal as far as a run of matches allows after each mismatch. */
        private static int[] grow(String read, String sequence, boolean plus, int shift, int from, int to,
                                  int start, int end) {
            int bestEnd = end;
            int score = 0;
            int best = 0;
            for (int i = end; i < to; i++) {
                score += read.charAt(i) == strandBase(sequence, plus, i + shift) ? 1 : -8;
                if (score > best) {
                    best = score;
                    bestEnd = i + 1;
                } else if (score < best - 16) {
                    break;
                }
            }
            int bestStart = start;
            score = 0;
            best = 0;
            for (int i = start - 1; i >= from; i--) {
                score += read.charAt(i) == strandBase(sequence, plus, i + shift) ? 1 : -8;
                if (score > best) {
                    best = score;
                    bestStart = i;
                } else if (score < best - 16) {
                    break;
                }
            }
            return new int[]{bestStart, bestEnd};
        }

        /** Base {@code position} of the product's plus strand, or of its reverse complement. */
        private static char strandBase(String sequence, boolean plus, int position) {
            return plus ? sequence.charAt(position) : complement(sequence.charAt(sequence.length() - 1 - position));
        }

        /** The verdict for product {@code index} from what was added so far. */
        public Verdict verdict(int index) {
            int[] depth = depth(index);
            return assess(depth.length, fragments.get(index), depth, entering.get(index), leaving.get(index), paired);
        }

        /** Reads covering each base of product {@code index}, placed whole or in part. */
        int[] depth(int index) {
            int[] changes = depthChanges.get(index);
            int[] depth = new int[changes.length - 1];
            int running = 0;
            for (int i = 0; i < depth.length; i++) {
                running += changes[i];
                depth[i] = running;
            }
            return depth;
        }

        /** Per position of product {@code index}, the reads whose match starts there, clipped on the left. */
        int[] entering(int index) {
            return entering.get(index);
        }

        /** Per position of product {@code index}, the reads whose match ends there, clipped on the right. */
        int[] leaving(int index) {
            return leaving.get(index);
        }

        /** The fragments placed on product {@code index}, 0-based half-open. */
        List<int[]> fragments(int index) {
            return fragments.get(index);
        }
    }

    /**
     * Applies the two rules.
     *
     * @param length    product length
     * @param fragments fragments placed on the product, 0-based half-open
     * @param depth     reads covering each base of the product, placed whole or in part
     * @param entering  per position, reads whose match starts there although they go on to the left
     * @param leaving   per position (exclusive end), reads whose match ends there although they go on
     */
    static Verdict assess(int length, List<int[]> fragments, int[] depth, int[] entering, int[] leaving,
                          boolean paired) {
        int window = 0;
        int[] counts = null;
        int median = 0;
        int firstWindow = Math.min(MIN_WINDOW, length);
        int firstMedian = 0;
        for (int w = firstWindow; w <= length; w += WINDOW_STEP) {
            int[] held = windowCounts(length, fragments, w);
            int m = median(held, 0, held.length);
            if (w == firstWindow) {
                firstMedian = m;
            }
            if (m < Math.max(MIN_MEDIAN_FRAGMENTS, WINDOW_MEDIAN_SHARE * firstMedian)) {
                break;
            }
            window = w;
            counts = held;
            median = m;
        }
        if (counts == null) {
            return new Verdict(Kind.TOO_FEW_PAIRS, paired, firstWindow, 0, 0, firstMedian, 0, 0, 0, 0, fragments.size());
        }
        int weakestStart = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] < counts[weakestStart]) {
                weakestStart = i;
            }
        }
        int weakest = counts[weakestStart];
        if (weakest < Math.max(MIN_WINDOW_FRAGMENTS, MIN_WINDOW_SHARE * median)) {
            return new Verdict(Kind.UNSUPPORTED_WINDOW, paired, window, weakest, weakestStart + 1, median, 0, 0, 0, 0,
                    fragments.size());
        }
        int[] repeat = unbridgedRepeat(depth, entering, leaving, window);
        if (repeat != null) {
            return new Verdict(Kind.UNRESOLVED_REPEAT, paired, window, weakest, weakestStart + 1, median,
                    repeat[0] + 1, repeat[1], repeat[2], repeat[3], fragments.size());
        }
        return new Verdict(Kind.SUPPORTED, paired, window, weakest, weakestStart + 1, median, 0, 0, 0, 0,
                fragments.size());
    }

    /** Per window start, the fragments that contain the whole window. */
    static int[] windowCounts(int length, List<int[]> fragments, int window) {
        int[] changes = new int[length - window + 2];
        for (int[] fragment : fragments) {
            int start = Math.max(0, fragment[0]);
            int lastWindow = Math.min(length, fragment[1]) - window; // last window start the fragment holds
            if (lastWindow >= start) {
                changes[start]++;
                changes[lastWindow + 1]--;
            }
        }
        int[] counts = new int[length - window + 1];
        int running = 0;
        for (int i = 0; i < counts.length; i++) {
            running += changes[i];
            counts[i] = running;
        }
        return counts;
    }

    /**
     * A stretch at least {@code window} long that reads of another copy enter at its
     * start and leave at its end.
     *
     * @return {start (0-based), end (exclusive), reads entering, reads leaving}, or {@code null}
     */
    static int[] unbridgedRepeat(int[] depth, int[] entering, int[] leaving, int window) {
        double needed = Math.max(MIN_BOUNDARY_READS, MIN_BOUNDARY_SHARE * median(depth, 0, depth.length));
        List<Integer> starts = boundaries(entering, leaving, needed);
        List<Integer> ends = boundaries(leaving, entering, needed);
        for (int start : starts) {
            for (int end : ends) {
                if (end - start >= window) {
                    return new int[]{start, end, near(entering, start), near(leaving, end)};
                }
            }
        }
        return null;
    }

    /**
     * Positions where at least {@code needed} reads end their match in one direction, and
     * few in the other: the strongest position of each such cluster.
     */
    private static List<Integer> boundaries(int[] marking, int[] opposite, double needed) {
        List<Integer> positions = new ArrayList<>();
        int i = 0;
        while (i < marking.length) {
            int near = near(marking, i);
            if (near < needed) {
                i++;
                continue;
            }
            // The peak of this cluster.
            int best = i;
            int j = i;
            while (j < marking.length && near(marking, j) >= needed) {
                if (marking[j] > marking[best]) {
                    best = j;
                }
                j++;
            }
            if (near(opposite, best) <= MAX_OPPOSITE_SHARE * near(marking, best)) {
                positions.add(best);
            }
            i = j;
        }
        return positions;
    }

    /** Reads ending their match within {@link #BOUNDARY_SPREAD} bases of {@code position}. */
    private static int near(int[] ends, int position) {
        int sum = 0;
        for (int i = Math.max(0, position - BOUNDARY_SPREAD);
             i <= Math.min(ends.length - 1, position + BOUNDARY_SPREAD); i++) {
            sum += ends[i];
        }
        return sum;
    }

    private static int median(int[] values, int from, int to) {
        int[] part = Arrays.copyOfRange(values, from, to);
        Arrays.sort(part);
        return part.length == 0 ? 0 : part[part.length / 2];
    }

    /** The {@link #SEED} bases from {@code position} as two bits each, or -1 when one is not A, C, G or T. */
    private static int encode(String sequence, int position) {
        int key = 0;
        for (int i = position; i < position + SEED; i++) {
            int code = switch (sequence.charAt(i)) {
                case 'A' -> 0;
                case 'C' -> 1;
                case 'G' -> 2;
                case 'T' -> 3;
                default -> -1;
            };
            if (code < 0) {
                return -1;
            }
            key = (key << 2) | code;
        }
        return key & Integer.MAX_VALUE;
    }

    private static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'C' -> 'G';
            case 'G' -> 'C';
            case 'T' -> 'A';
            default -> 'N';
        };
    }

    static String reverseComplement(String sequence) {
        StringBuilder result = new StringBuilder(sequence.length());
        for (int i = sequence.length() - 1; i >= 0; i--) {
            result.append(complement(sequence.charAt(i)));
        }
        return result.toString();
    }
}
