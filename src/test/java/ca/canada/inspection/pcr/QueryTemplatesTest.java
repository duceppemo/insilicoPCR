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
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases());
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases(), "same query again");
            assertEquals("GG", templates.apply(on("c3")).bases(), "c2 skipped");
            TrimmedEnds.Template c4 = templates.apply(on("c4"));
            assertEquals("CCCCAAAA", c4.bases());
            assertEquals(8, c4.length());
            assertEquals(Set.of(), templates.missing());
        }
    }

    @Test
    void queriesOutOfOrderAreFoundByReadingAgain() throws IOException {
        try (QueryTemplates templates = new QueryTemplates(files())) {
            assertEquals("CCCCAAAA", templates.apply(on("c4")).bases());
            assertEquals("TTTT", templates.apply(on("c2")).bases());
            assertEquals("ACGTacgt", templates.apply(on("c1")).bases());
        }
    }

    @Test
    void unknownQueriesGetNoTemplateAndAreRemembered() throws IOException {
        try (QueryTemplates templates = new QueryTemplates(files())) {
            assertNull(templates.apply(on("lcl|c1")));
            assertEquals("TTTT", templates.apply(on("c2")).bases());
            assertNull(templates.apply(on("ghost")));
            assertEquals(Set.of("lcl|c1", "ghost"), templates.missing());
        }
    }
}
