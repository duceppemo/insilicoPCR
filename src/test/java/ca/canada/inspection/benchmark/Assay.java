package ca.canada.inspection.benchmark;

import java.util.Random;

/**
 * An assay the benchmark designed, with the product its primers give on a perfect template.
 *
 * @param id      the assay's name ({@code id-F}, {@code id-R}, {@code id-P} in the primer file)
 * @param forward the forward primer, 5' to 3'
 * @param reverse the reverse primer, 5' to 3'
 * @param probe   the probe, in the product's sense, or {@code null}
 * @param words   header words after the forward primer's ID ({@code ta=50}, {@code mix=m1}), or empty
 * @param product the template a perfect assay amplifies, in the forward primer's sense: it starts with the forward
 *                primer and ends with the reverse complement of the reverse primer
 */
record Assay(String id, String forward, String reverse, String probe, String words, String product) {

    /** Primers the default rule accepts on a perfect site with room to spare: 55 °C is needed. */
    static final double TM_LOW = 60;
    static final double TM_HIGH = 66;

    /** An assay of random primers melting at {@link #TM_LOW} to {@link #TM_HIGH} °C, with a product of {@code length} bp. */
    static Assay design(Random random, String id, int length) {
        return design(random, id, length, false, TM_LOW, TM_HIGH);
    }

    /**
     * An assay of random primers melting at {@code tmLow} to {@code tmHigh} °C on a perfect match.
     *
     * @param length    the product's length, bp: at least 60, and 120 with a probe
     * @param withProbe whether it has a probe, placed midway, melting 6 to 12 °C above {@code tmLow}
     */
    static Assay design(Random random, String id, int length, boolean withProbe, double tmLow, double tmHigh) {
        return design(random, id, length, withProbe, tmLow, tmHigh, 20, 24);
    }

    /** Likewise, with primers of {@code minLength} to {@code maxLength} bases. */
    static Assay design(Random random, String id, int length, boolean withProbe, double tmLow, double tmHigh,
                        int minLength, int maxLength) {
        if (length < (withProbe ? 120 : 60)) {
            throw new IllegalArgumentException("Too short a product for " + id + ": " + length);
        }
        String forward = oligo(random, minLength, maxLength, tmLow, tmHigh);
        String reverse = oligo(random, minLength, maxLength, tmLow, tmHigh);
        int between = length - forward.length() - reverse.length();
        String probe = null;
        String middle;
        if (withProbe) {
            probe = oligo(random, 24, 28, tmLow + 6, tmLow + 12);
            int before = (between - probe.length()) / 2;
            middle = Seq.random(random, before) + probe + Seq.random(random, between - probe.length() - before);
        } else {
            middle = Seq.random(random, between);
        }
        return new Assay(id, forward, reverse, probe, "", forward + middle + Seq.reverseComplement(reverse));
    }

    /**
     * A random oligo of {@code minLength} to {@code maxLength} bases that melts at {@code tmLow} to {@code tmHigh} °C
     * on its perfect complement, with 35 to 65% G and C and no base four times in a row.
     */
    static String oligo(Random random, int minLength, int maxLength, double tmLow, double tmHigh) {
        for (int attempt = 0; attempt < 100_000; attempt++) {
            String oligo = Seq.random(random, minLength + random.nextInt(maxLength - minLength + 1));
            double gc = Seq.gc(oligo);
            if (gc < 0.35 || gc > 0.65 || Seq.hasRun(oligo, 4)) {
                continue;
            }
            double tm = Rule.perfectTm(oligo);
            if (tm >= tmLow && tm <= tmHigh) {
                return oligo;
            }
        }
        throw new IllegalStateException("No oligo of " + minLength + " to " + maxLength + " bases melts at " + tmLow
                + " to " + tmHigh + " °C");
    }

    Assay withWords(String headerWords) {
        return new Assay(id, forward, reverse, probe, headerWords, product);
    }

    Assay withForward(String primer) {
        return new Assay(id, primer, reverse, probe, words, product);
    }

    int size() {
        return product.length();
    }

    /** The assay's records of a primer FASTA file. */
    String fasta() {
        StringBuilder records = new StringBuilder();
        records.append('>').append(id).append("-F").append(words.isEmpty() ? "" : " " + words).append('\n')
                .append(forward).append('\n');
        records.append('>').append(id).append("-R\n").append(reverse).append('\n');
        if (probe != null) {
            records.append('>').append(id).append("-P\n").append(probe).append('\n');
        }
        return records.toString();
    }
}
