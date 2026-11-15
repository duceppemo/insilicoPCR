package ca.canada.inspection.pcr;

/** DNA strands: the one complement of a base, for the template and the reads alike. */
final class Dna {
    private Dna() {
    }

    /** The base pairing with {@code base}; {@code N} for anything but A, C, G and T (upper case). */
    static char complement(char base) {
        return switch (base) {
            case 'A' -> 'T';
            case 'C' -> 'G';
            case 'G' -> 'C';
            case 'T' -> 'A';
            default -> 'N';
        };
    }

    /** The other strand of {@code sequence}, read 5' to 3'. */
    static String reverseComplement(String sequence) {
        StringBuilder result = new StringBuilder(sequence.length());
        for (int i = sequence.length() - 1; i >= 0; i--) {
            result.append(complement(sequence.charAt(i)));
        }
        return result.toString();
    }
}
