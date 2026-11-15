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
}
