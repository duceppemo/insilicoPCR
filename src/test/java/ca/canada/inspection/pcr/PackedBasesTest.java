package ca.canada.inspection.pcr;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PackedBasesTest {

    private static char baseAt(int index) {
        return "ACGTN".charAt(index % 5);
    }

    private static PackedBases of(int length) {
        PackedBases bases = new PackedBases();
        for (int i = 0; i < length; i++) {
            bases.append(baseAt(i));
        }
        return bases;
    }

    @Test
    void anEmptySequenceHasNoBases() {
        PackedBases bases = new PackedBases();

        assertEquals(0, bases.length());
        assertEquals("", bases.toString());
        assertThrows(IndexOutOfBoundsException.class, () -> bases.charAt(0));
    }

    @Test
    void aReadIsKeptAsWritten() {
        PackedBases bases = new PackedBases();
        "ACGTacgtNNRY".chars().forEach(c -> bases.append((char) c));

        assertEquals(12, bases.length());
        assertEquals("ACGTacgtNNRY", bases.toString());
        assertEquals("acgt", bases.subSequence(4, 8).toString());
        assertThrows(IndexOutOfBoundsException.class, () -> bases.charAt(12));
        assertThrows(IndexOutOfBoundsException.class, () -> bases.charAt(-1));
    }

    @Test
    void everyBaseIsFoundAgainAcrossBlocks() {
        // Three full blocks and a partly filled fourth.
        int block = PackedBases.BLOCK;
        int length = 3 * block + 12_345;
        PackedBases bases = of(length);

        assertEquals(length, bases.length());
        for (int index : new int[] {0, 255, 256, block - 1, block, block + 1, 2 * block - 1, 3 * block, length - 1}) {
            assertEquals(baseAt(index), bases.charAt(index), "base " + index);
        }
        assertThrows(IndexOutOfBoundsException.class, () -> bases.charAt(length));
    }

    @Test
    void aSequenceOfExactlyOneBlockIsComplete() {
        int length = PackedBases.BLOCK;
        PackedBases bases = of(length);

        assertEquals(length, bases.length());
        assertEquals(baseAt(length - 1), bases.charAt(length - 1));
        assertThrows(IndexOutOfBoundsException.class, () -> bases.charAt(length));
    }

    @Test
    void aCharacterThatIsNotAsciiIsKeptAsAPlaceholder() {
        PackedBases bases = new PackedBases();
        bases.append('A');
        bases.append('é');

        assertEquals("A?", bases.toString());
    }
}
