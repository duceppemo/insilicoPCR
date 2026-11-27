package ca.canada.inspection.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The circular sequences of a FASTA file, searched as circles: a product across the origin of a
 * plasmid, a mitochondrial genome or a complete chromosome is made by a PCR, and on the sequence
 * written from its origin it was in two pieces, at its two ends, and not reported. A sequence is
 * circular when its header says so ({@code circular=true}, as Unicycler, Flye and Bakta write it, or
 * {@code [topology=circular]}, as NCBI's submission tools do), or when it is a contig the mini-assembly
 * of FASTQ mode closed on itself.
 *
 * <p>Tadpole writes a circle with the first bases of its start again at its end, from 2 to {@code k - 1}
 * of them, and calls it a {@code self-loop}, or not: reads of one 1,500-bp circle gave contigs of 1,502
 * bases ({@code self-loop}), and of 1,505 to 1,526 ({@code unanchored}), as the reads went. The circle is
 * the contig without them: for a {@code self-loop}, the {@code k - 1} or 2 bases Tadpole repeats (a
 * self-loop of real reads had its ends alike by 0, 1, 2, 3, 7 or 30 bases: another count is taken for
 * chance, and the contig for a whole circle); for another contig of Tadpole's, the longest stretch of
 * {@link #UNFLAGGED_OVERLAP} to {@code k} bases its end and its start share (fewer are as likely chance).</p>
 */
public final class Circles {

    /**
     * The shortest contig of the mini-assembly searched as a circle: shorter, a loop of a short repeat's
     * copies, which holds no primer pair. A sequence whose header says it is circular is one whatever its
     * length (a 99-bp one was searched as linear, and the run said "if it is circular").
     */
    public static final int SHORTEST = 100;

    /**
     * The fewest bases a contig of Tadpole's not called a {@code self-loop} must repeat of its start at its end
     * to be taken for a circle: by chance, one contig in about 10 million.
     */
    public static final int UNFLAGGED_OVERLAP = 12;

    /** A header's word saying it is circular. */
    private static final Pattern MARKED = Pattern.compile(
            "(?i)(?:^|[\\s\\[;,])(?:circular=(?:true|yes|t|1)|topology=circular)(?=$|[\\s\\];,])");
    /** Tadpole's class of a contig whose end joins its start ({@code class=long/medium/self-loop}). */
    private static final Pattern SELF_LOOP = Pattern.compile("(?:^|,)class=[^,\\s]*self-loop(?=$|[,\\s])");
    /** A contig of Tadpole's: {@code contig_3,len=1526,cov=30.2,gc=0.503,class=long/medium/unanchored,...}. */
    private static final Pattern TADPOLE = Pattern.compile("^contig_\\d+,len=\\d+,.*(?:^|,)class=[^,\\s]+");

    private Circles() {
    }

    /**
     * {@code position} counted on past a circle's origin (or before it) as on the circle of {@code circle} bases:
     * 1,810 on a 1,800-base circle is 10, 0 is 1,800. A sequence of no length as a circle (0 or less) is linear:
     * the position as it is.
     */
    public static int onCircle(int position, int circle) {
        return circle > 0 ? Math.floorMod(position - 1, circle) + 1 : position;
    }

    /**
     * The circular sequences of {@code fasta} (gzipped or not), by ID, each with its length as a circle;
     * in file order.
     *
     * @param tadpoleK the k-mer length of the mini-assembly: the most bases of a self-loop contig's start
     *                 written again at its end
     */
    public static Map<String, Integer> of(Path fasta, int tadpoleK) throws IOException {
        return FastaSummary.of(fasta, tadpoleK).circles();
    }

    /**
     * What one record says of itself as a circle, its bases given one by one ({@link #accept}); {@code null}
     * for a record that is no candidate (neither marked circular nor a contig of Tadpole's).
     */
    static Record record(String header, int tadpoleK) {
        String text = header.substring(1).strip();
        boolean selfLoop = SELF_LOOP.matcher(text).find();
        boolean tadpole = !selfLoop && TADPOLE.matcher(text).find();
        boolean marked = MARKED.matcher(SequenceFileUtils.fastaDescription(header)).find();
        return selfLoop || tadpole || marked ? new Record(marked, selfLoop, tadpoleK) : null;
    }

    /** A candidate record's ends, and whether it is a circle once they are read. */
    static final class Record {
        private final boolean marked;
        private final boolean selfLoop;
        private final int tadpoleK;
        private final Ends ends;

        private Record(boolean marked, boolean selfLoop, int tadpoleK) {
            this.marked = marked;
            this.selfLoop = selfLoop;
            this.tadpoleK = tadpoleK;
            this.ends = new Ends(Math.max(2, tadpoleK));
        }

        void accept(int base) {
            ends.accept(base);
        }

        /** The record's length as a circle, or 0 for one that is not. */
        int circle() {
            int overlap = marked ? 0 : selfLoop ? ends.repeated(tadpoleK - 1, 2) : ends.sharedOverlap(UNFLAGGED_OVERLAP);
            int circle = ends.length - overlap;
            return (marked ? circle >= 2 : circle >= SHORTEST && (selfLoop || overlap > 0)) ? circle : 0;
        }
    }

    /** A sequence's length, its first and its last bases (up to {@code keep} of each). */
    private static final class Ends {
        private final int keep;
        private final StringBuilder head = new StringBuilder();
        private final char[] tail;
        private int length;

        Ends(int keep) {
            this.keep = keep;
            this.tail = new char[keep];
        }

        void accept(int base) {
            char c = Character.toUpperCase((char) base);
            if (length < keep) {
                head.append(c);
            }
            tail[length % keep] = c;
            length++;
        }

        /** The last {@code n} bases ({@code n <= keep}, {@code n <= length}). */
        private String last(int n) {
            StringBuilder last = new StringBuilder(n);
            for (int i = length - n; i < length; i++) {
                last.append(tail[i % keep]);
            }
            return last.toString();
        }

        /** The first of {@code counts} for which the sequence's end repeats as many bases of its start; 0 for none. */
        int repeated(int... counts) {
            String start = head.toString().toUpperCase(Locale.ROOT);
            for (int n : counts) {
                if (n >= 1 && n <= keep && n <= length / 2 && last(n).equals(start.substring(0, n))) {
                    return n;
                }
            }
            return 0;
        }

        /**
         * The most bases, from {@code fewest} to {@code keep} and no more than half the sequence, its end repeats
         * of its start; 0 for none.
         */
        int sharedOverlap(int fewest) {
            String start = head.toString().toUpperCase(Locale.ROOT);
            for (int n = Math.min(keep, length / 2); n >= fewest; n--) {
                if (last(n).equals(start.substring(0, n))) {
                    return n;
                }
            }
            return 0;
        }
    }
}
