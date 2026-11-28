package ca.canada.inspection.benchmark;

import java.util.Random;

/**
 * DNA as the benchmark makes it: random sequences from a seed, so that every case is the same on every computer and
 * in every version. Nothing here comes from the program under test.
 */
final class Seq {

    private static final String BASES = "ACGT";

    private Seq() {
    }

    /** {@code length} random bases. */
    static String random(Random random, int length) {
        StringBuilder bases = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            bases.append(BASES.charAt(random.nextInt(4)));
        }
        return bases.toString();
    }

    static String reverseComplement(String sequence) {
        StringBuilder reversed = new StringBuilder(sequence.length());
        for (int i = sequence.length() - 1; i >= 0; i--) {
            reversed.append(complement(sequence.charAt(i)));
        }
        return reversed.toString();
    }

    private static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'C' -> 'G';
            case 'G' -> 'C';
            case 'T' -> 'A';
            default -> throw new IllegalArgumentException("Not a base: " + base);
        };
    }

    /** A base other than {@code base}. */
    static char other(Random random, char base) {
        char drawn;
        do {
            drawn = BASES.charAt(random.nextInt(4));
        } while (drawn == base);
        return drawn;
    }

    /** {@code sequence} with another base at {@code index}. */
    static String substituted(Random random, String sequence, int index) {
        return sequence.substring(0, index) + other(random, sequence.charAt(index)) + sequence.substring(index + 1);
    }

    /** The share of G and C. */
    static double gc(String sequence) {
        return sequence.chars().filter(base -> base == 'G' || base == 'C').count() / (double) sequence.length();
    }

    /** Whether one base is repeated {@code run} times or more in a row. */
    static boolean hasRun(String sequence, int run) {
        int length = 1;
        for (int i = 1; i < sequence.length(); i++) {
            length = sequence.charAt(i) == sequence.charAt(i - 1) ? length + 1 : 1;
            if (length >= run) {
                return true;
            }
        }
        return run <= 1;
    }

    /**
     * A copy of {@code sequence} with about {@code divergence} of its bases substituted: the other copy of a repeat.
     */
    static String diverged(Random random, String sequence, double divergence) {
        StringBuilder copy = new StringBuilder(sequence);
        for (int i = 0; i < copy.length(); i++) {
            if (random.nextDouble() < divergence) {
                copy.setCharAt(i, other(random, copy.charAt(i)));
            }
        }
        return copy.toString();
    }
}
