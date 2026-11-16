package ca.canada.inspection.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
     * The circular sequences of {@code fasta} (gzipped or not), by ID, each with its length as a circle;
     * in file order.
     *
     * @param tadpoleK the k-mer length of the mini-assembly: the most bases of a self-loop contig's start
     *                 written again at its end
     */
    public static Map<String, Integer> of(Path fasta, int tadpoleK) throws IOException {
        Map<String, Integer> circles = new LinkedHashMap<>();
        int overlapAtMost = Math.max(2, tadpoleK);
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            String header = scanner.nextHeader(null);
            while (header != null) {
                String text = header.substring(1).strip();
                boolean selfLoop = SELF_LOOP.matcher(text).find();
                boolean tadpole = !selfLoop && TADPOLE.matcher(text).find();
                boolean marked = MARKED.matcher(SequenceFileUtils.fastaDescription(header)).find();
                if (!selfLoop && !tadpole && !marked) {
                    header = scanner.nextHeader(null);
                    continue;
                }
                Ends ends = new Ends(overlapAtMost);
                String next = scanner.nextHeader(ends::accept);
                int overlap = marked ? 0 : selfLoop ? ends.repeated(tadpoleK - 1, 2)
                        : ends.sharedOverlap(UNFLAGGED_OVERLAP);
                int circle = ends.length - overlap;
                if (marked ? circle >= 2 : circle >= SHORTEST && (selfLoop || overlap > 0)) {
                    circles.putIfAbsent(SequenceFileUtils.fastaId(header), circle);
                }
                header = next;
            }
        }
        return circles;
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
