package ca.canada.inspection.pcr;

import java.util.ArrayList;
import java.util.List;

/**
 * A nucleotide sequence held at one byte per base, in blocks.
 *
 * <p>A contig can be hundreds of millions of bases long. Built in a {@code StringBuilder}
 * and turned into a {@code String}, it needs up to three times its length for a moment:
 * the builder's array, the larger array it is being copied into, and the final copy. Here
 * a sequence grows by adding blocks, nothing is copied once it is longer than one block,
 * and it is read in place.</p>
 */
final class PackedBases implements CharSequence {

    private static final int BLOCK_BITS = 20;
    private static final int BLOCK = 1 << BLOCK_BITS;

    /** Full blocks, each of {@link #BLOCK} bases. */
    private final List<byte[]> full = new ArrayList<>();
    /** The block being filled. It starts small, for the many sequences that are reads. */
    private byte[] tail = new byte[256];
    private int tailUsed;

    void append(char base) {
        if (tailUsed == tail.length) {
            if (tail.length < BLOCK) {
                tail = java.util.Arrays.copyOf(tail, Math.min(BLOCK, tail.length * 2));
            } else {
                full.add(tail);
                tail = new byte[BLOCK];
                tailUsed = 0;
            }
        }
        // Sequence characters are ASCII; anything else cannot pair with a primer base.
        tail[tailUsed++] = base < 128 ? (byte) base : (byte) '?';
    }

    @Override
    public int length() {
        // Overflows past 2^31 - 1 bases, which a Java array or string could not hold either.
        return Math.toIntExact(((long) full.size() << BLOCK_BITS) + tailUsed);
    }

    @Override
    public char charAt(int index) {
        int block = index >>> BLOCK_BITS;
        if (block < full.size()) {
            return (char) full.get(block)[index & (BLOCK - 1)];
        }
        int inTail = index - (full.size() << BLOCK_BITS);
        if (index < 0 || inTail >= tailUsed) {
            throw new IndexOutOfBoundsException("index " + index + ", length " + length());
        }
        return (char) tail[inTail];
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        if (start < 0 || end > length() || start > end) {
            throw new IndexOutOfBoundsException("range " + start + "-" + end + ", length " + length());
        }
        StringBuilder part = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            part.append(charAt(i));
        }
        return part.toString();
    }

    @Override
    public String toString() {
        return subSequence(0, length()).toString();
    }
}
