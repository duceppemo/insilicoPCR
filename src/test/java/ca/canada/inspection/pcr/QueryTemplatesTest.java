package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class QueryTemplatesTest {

    @TempDir
    Path dir;

    private static BlastResult on(String contig) {
        return new BlastResult("s", contig, "a-F", 0, 0, 1, 4, 1, 4, 4, 4, "ACGT", "ACGT", "ACGT");
    }

    private List<Path> files() throws IOException {
        Path plain = dir.resolve("a.fasta");
        Files.writeString(plain, ">c1 first contig\nACGT\nacgt\n>c2\nTTTT\n>c3\nGG\n");
        Path gz = dir.resolve("b.fasta.gz");
        try (var out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            out.write(">c4\tx\nCCCC\nAAAA\n".getBytes(StandardCharsets.UTF_8));
        }
        return List.of(plain, gz);
    }

    @Test
    void readsQueriesInReportOrderAcrossWrappedAndGzippedFiles() throws IOException {
        try (QueryTemplates templates = new QueryTemplates(files())) {
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases().toString());
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases().toString(), "same query again");
            assertEquals("GG", templates.apply(on("c3")).bases().toString(), "c2 skipped");
            TrimmedEnds.Template c4 = templates.apply(on("c4"));
            assertEquals("CCCCAAAA", c4.bases().toString());
            assertEquals(8, c4.length());
            assertEquals(Set.of(), templates.missing());
        }
    }

    @Test
    void queriesOutOfOrderAreFoundByReadingAgain() throws IOException {
        try (QueryTemplates templates = new QueryTemplates(files())) {
            assertEquals("CCCCAAAA", templates.apply(on("c4")).bases().toString());
            assertEquals("TTTT", templates.apply(on("c2")).bases().toString());
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases().toString());
        }
    }

    @Test
    void unknownQueriesGetNoTemplateAndAreRemembered() throws IOException {
        try (QueryTemplates templates = new QueryTemplates(files())) {
            assertNull(templates.apply(on("lcl|c1")));
            assertEquals("TTTT", templates.apply(on("c2")).bases().toString());
            assertNull(templates.apply(on("ghost")));
            assertEquals(Set.of("lcl|c1", "ghost"), templates.missing());
        }
    }

    @Test
    void aContigOnOneLineIsReadLikeAWrappedOne() throws IOException {
        // Longer than the block the files are read in (65536 characters) and than one block of bases.
        String contig = "ACGTTGCA".repeat(200_000);
        Path fasta = Files.writeString(dir.resolve("long.fasta"), ">first\nAC\n>long one line\n" + contig + "\n>last\nGG");

        try (QueryTemplates templates = new QueryTemplates(List.of(fasta))) {
            TrimmedEnds.Template template = templates.apply(on("long"));
            assertEquals(1_600_000, template.length());
            assertEquals(contig, template.bases().toString());
            assertEquals("GG", templates.apply(on("last")).bases().toString(), "no line end after the last record");
        }
    }

    @Test
    void windowsLineEndsBlankLinesAndBlanksAreNotBases() throws IOException {
        Path fasta = Files.writeString(dir.resolve("crlf.fasta"),
                "text before the first record\r\n>c1 x\r\nAC GT\r\n\r\n  acgt\t\r\n>c2\r\nTT\r\n");

        try (QueryTemplates templates = new QueryTemplates(List.of(fasta))) {
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases().toString());
            assertEquals("TT", templates.apply(on("c2")).bases().toString());
        }
    }

    @Test
    void aGreaterThanSignInsideALineIsNotAHeader() throws IOException {
        Path fasta = Files.writeString(dir.resolve("odd.fasta"), ">c1 a>b\nAC>c9\nGT\n>c2\nTT\n");

        try (QueryTemplates templates = new QueryTemplates(List.of(fasta))) {
            assertEquals("AC>c9GT", templates.apply(on("c1")).bases().toString());
            assertNull(templates.apply(on("c9")));
        }
    }

    @Test
    void aFileWithoutAFinalLineEndDoesNotHideTheNextFile() throws IOException {
        Path first = Files.writeString(dir.resolve("1.fasta"), ">c1\nACGT");
        Path second = Files.writeString(dir.resolve("2.fasta"), ">c2\nTTTT\n");

        try (QueryTemplates templates = new QueryTemplates(List.of(first, second))) {
            assertEquals("ACGT", templates.apply(on("c1")).bases().toString());
            assertEquals("TTTT", templates.apply(on("c2")).bases().toString());
        }
    }
}
