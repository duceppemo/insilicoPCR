package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastTsvTest {

    @TempDir
    Path dir;

    @Test
    void headerIsPrependedAndContentPreserved() throws IOException {
        Path tsv = Files.writeString(dir.resolve("sample.tsv"), "row1\nrow2\n");
        BlastTsv.prependHeader(tsv);

        List<String> lines = Files.readAllLines(tsv);
        assertEquals(3, lines.size());
        assertEquals(BlastTsv.HEADER, lines.get(0));
        assertEquals(List.of("row1", "row2"), lines.subList(1, 3));
    }

    @Test
    void missingFileBecomesHeaderOnly() throws IOException {
        Path tsv = dir.resolve("absent.tsv");
        BlastTsv.prependHeader(tsv);
        assertEquals(List.of(BlastTsv.HEADER), Files.readAllLines(tsv));
    }

    @Test
    void headerMatchesTheOutfmtColumns() {
        // The header must stay in sync with the BLAST -outfmt specification.
        String expected = BlastTsv.OUTFMT.replaceFirst("^6 ", "").replace(' ', '\t');
        assertEquals(expected, BlastTsv.HEADER);
        assertTrue(BlastTsv.OUTFMT.startsWith("6 "));
    }
}
