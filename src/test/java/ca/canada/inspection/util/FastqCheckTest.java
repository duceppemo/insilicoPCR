package ca.canada.inspection.util;

import ca.canada.inspection.util.FastqCheck.Fault;
import ca.canada.inspection.util.FastqCheck.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqCheckTest {

    private static final String READ_1 = "@r1 first\nACGTN\n+\nIIII#\n";
    private static final String READ_2 = "@r2\nGGCC\n+r2\nIIII\n";

    @TempDir
    Path dir;

    private Result check(String name, String content) throws IOException {
        return FastqCheck.check(Files.writeString(dir.resolve(name), content));
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    @Test
    void wellFormedReadsAreCounted() throws IOException {
        Result result = check("ok.fastq", READ_1 + READ_2);

        assertTrue(result.valid());
        assertEquals(2, result.records());
        assertEquals("", result.detail());
    }

    @Test
    void windowsLineEndsAMissingLastLineEndAndTrailingBlankLinesAreFine() throws IOException {
        assertEquals(2, check("crlf.fastq", (READ_1 + READ_2).replace("\n", "\r\n")).records());
        assertTrue(check("noeol.fastq", READ_1 + "@r2\nGGCC\n+\nIIII").valid());
        assertEquals(2, check("blank.fastq", READ_1 + READ_2 + "\n\n").records());
    }

    @Test
    void aReadWithoutBasesIsARead() throws IOException {
        assertEquals(2, check("zero.fastq", "@trimmed\n\n+\n\n" + READ_2).records());
        assertEquals(2, check("zero_last.fastq", READ_2 + "@trimmed\n\n+\n").records());
    }

    @Test
    void anEmptyFileHoldsNoRead() throws IOException {
        assertEquals(Fault.EMPTY, check("empty.fastq", "").fault());
        assertEquals(Fault.EMPTY, check("blank.fastq", "\n\n").fault());
        // What an empty library looks like after demultiplexing: a gzip stream of nothing.
        Result result = FastqCheck.check(Files.write(dir.resolve("empty.fastq.gz"), gzip("")));
        assertEquals(Fault.EMPTY, result.fault());
        assertEquals("the file is empty", result.detail());
    }

    @Test
    void aFastaFileIsNotFastq() throws IOException {
        Result result = check("fasta.fastq", ">contig\nACGT\n");

        assertEquals(Fault.NOT_FASTQ, result.fault());
        assertEquals("read 1 (line 1): the header line does not start with @", result.detail());
    }

    @Test
    void aQualityLineOfAnotherLengthIsNamedWithItsReadAndLine() throws IOException {
        Result result = check("short.fastq", READ_1 + "@r2\nGGCC\n+\nIII\n" + READ_2);

        assertEquals(Fault.MALFORMED, result.fault());
        assertEquals(1, result.records());
        assertEquals("read 2 (line 8): the quality line has 3 characters for 4 bases", result.detail());
    }

    @Test
    void aMissingSeparatorIsMalformed() throws IOException {
        Result result = check("nosep.fastq", READ_1 + "@r2\nGGCC\nIIII\n");

        assertEquals(Fault.MALFORMED, result.fault());
        assertEquals("read 2 (line 7): the third line of the record does not start with +", result.detail());
    }

    @Test
    void aCharacterThatIsNotABaseOrAQualityIsMalformed() throws IOException {
        assertEquals("read 1 (line 2): the sequence line holds a character that is not a base ('7')",
                check("digit.fastq", "@r\nAC7T\n+\nIIII\n").detail());
        assertEquals("read 1 (line 4): the quality line holds a character that is not a quality score (byte 0x20)",
                check("space.fastq", "@r\nACGT\n+\nII I\n").detail());
    }

    @Test
    void aBlankLineBetweenReadsIsMalformed() throws IOException {
        Result result = check("gap.fastq", READ_1 + "\n" + READ_2);

        assertEquals(Fault.MALFORMED, result.fault());
        assertEquals("read 2 (line 6): blank line before this line", result.detail());
    }

    @Test
    void aFileThatEndsInsideAReadIsIncomplete() throws IOException {
        Result result = check("cut.fastq", READ_1 + "@r2\nGGCC\n+\n");

        assertEquals(Fault.TRUNCATED, result.fault());
        assertEquals("it ends inside read 2, after 1 complete read", result.detail());
        assertEquals(Fault.TRUNCATED, check("cut2.fastq", READ_1 + "@r2\nGG").fault());
    }

    @Test
    void aGzipStreamCutShortIsUnreadable() throws IOException {
        // Reads that do not compress to nothing, so that cutting the file loses some of them.
        StringBuilder reads = new StringBuilder();
        java.util.Random random = new java.util.Random(7);
        for (int i = 0; i < 2000; i++) {
            StringBuilder bases = new StringBuilder();
            for (int j = 0; j < 100; j++) {
                bases.append("ACGT".charAt(random.nextInt(4)));
            }
            reads.append("@r").append(i).append('\n').append(bases).append("\n+\n").append("I".repeat(100)).append('\n');
        }
        byte[] whole = gzip(reads.toString());
        Path cut = Files.write(dir.resolve("cut.fastq.gz"), Arrays.copyOf(whole, whole.length / 2));

        Result result = FastqCheck.check(cut);

        assertEquals(Fault.UNREADABLE, result.fault());
        assertTrue(result.detail().startsWith("the gzip stream ends early, after "), result.detail());
        assertTrue(result.records() > 0 && result.records() < 2000, "reads before the cut: " + result.records());
        assertEquals(2000, FastqCheck.check(Files.write(dir.resolve("whole.fastq.gz"), whole)).records());
    }

    @Test
    void aFileNamedGzThatIsNotGzipIsUnreadable() throws IOException {
        Result result = check("plain.fastq.gz", READ_1);

        assertEquals(Fault.UNREADABLE, result.fault());
        assertTrue(result.detail().startsWith("not a gzip file"), result.detail());
    }

    @Test
    void severalGzipMembersAreReadToTheLast() throws IOException {
        // bcl-convert and "cat a.gz b.gz" write one file of several gzip members.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(gzip(READ_1));
        bytes.write(gzip(READ_2));

        assertEquals(2, FastqCheck.check(Files.write(dir.resolve("members.fastq.gz"), bytes.toByteArray())).records());
    }

    @Test
    void theQuickCheckStopsAfterTheFirstReads() throws IOException {
        Path file = Files.writeString(dir.resolve("late.fastq"), READ_1 + READ_2 + "@r3\nGGCC\n+\nI\n");

        assertEquals(Fault.MALFORMED, FastqCheck.check(file).fault());
        Result quick = FastqCheck.check(file, 1);
        assertTrue(quick.valid());
        assertEquals(1, quick.records());
    }
}
