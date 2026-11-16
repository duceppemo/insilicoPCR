package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FastaScannerTest {

    @TempDir
    Path dir;

    @Test
    void headersAndBasesComeAcrossFilesWrappedOrNot() throws IOException {
        Path first = Files.writeString(dir.resolve("a.fasta"), ">c1 len=8\nACGT\nAC GT\r\n>c2\n" + "G".repeat(100_000));
        Path second = dir.resolve("b.fasta.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(second))) {
            out.write(">c3 plasmid\nTTTT\n".getBytes(StandardCharsets.UTF_8));
        }
        List<String> headers = new ArrayList<>();
        StringBuilder bases = new StringBuilder();
        try (FastaScanner scanner = new FastaScanner(List.of(first, second))) {
            String header;
            while ((header = scanner.nextHeader(base -> bases.append((char) base))) != null) {
                headers.add(header);
            }
        }
        assertEquals(List.of(">c1 len=8", ">c2", ">c3 plasmid"), headers);
        assertEquals("ACGTACGT" + "G".repeat(100_000) + "TTTT", bases.toString());
    }

    @Test
    void lengthsAreCountedWithoutLineEndsOrBlanks() throws IOException {
        Path fasta = Files.writeString(dir.resolve("a.fasta"), ">c1 len=8\nACGT\nAC GT\n>c2\tdesc\n" + "G".repeat(500));
        assertEquals(Map.of("c1", 8, "c2", 500), SequenceFileUtils.sequenceLengths(fasta));
    }

    @Test
    void anIdEndsAtTheFirstBlankAndTheDescriptionFollows() {
        assertEquals("contig_6,len=1534", SequenceFileUtils.fastaId(">contig_6,len=1534 cov=26.3"));
        assertEquals("cov=26.3 x", SequenceFileUtils.fastaDescription(">contig_6,len=1534\tcov=26.3 x "));
        assertEquals("", SequenceFileUtils.fastaDescription(">read_1"));
        assertEquals("read_1", SequenceFileUtils.fastaId("> read_1"));
    }

    @Test
    void anEmptyFileHasNoRecord() throws IOException {
        try (FastaScanner scanner = new FastaScanner(List.of(Files.writeString(dir.resolve("e.fasta"), "")))) {
            assertNull(scanner.nextHeader(null));
        }
    }

    @Test
    void commentsAreNotBasesAsBlastReadsThem() throws IOException {
        // BLAST drops a line's rest after ;, and lines that start with # or !: counted, the bases after a
        // comment were shifted from BLAST's positions.
        Path fasta = Files.writeString(dir.resolve("comments.fasta"),
                ";file comment\n#another\n>c1 a;b\nAAAAA;GGGG\n#GGGG\n!GGGG\nCC#C!\n>c2\nTT\n");
        assertEquals(Map.of("c1", 8, "c2", 2), SequenceFileUtils.sequenceLengths(fasta));
        StringBuilder bases = new StringBuilder();
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            assertEquals(">c1 a;b", scanner.nextHeader(null));
            scanner.nextHeader(base -> bases.append((char) base));
        }
        assertEquals("AAAAACCC", bases.toString());
    }

    @Test
    void charactersBlastDropsAreNotBases() throws IOException {
        // An alignment's gaps and other non-nucleotide characters: BLAST leaves them out of its
        // positions, so a template or a length that kept them was shifted from BLAST's.
        Path fasta = Files.writeString(dir.resolve("gapped.fasta"), ">g\nAC--GT..1*nr\n");
        assertEquals(Map.of("g", 6), SequenceFileUtils.sequenceLengths(fasta));
        StringBuilder bases = new StringBuilder();
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            scanner.nextHeader(null);
            scanner.nextHeader(base -> bases.append((char) base));
        }
        assertEquals("ACGTnr", bases.toString());
    }
}
