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
    void bytesThatAreNotUtf8AreCopiedAsTheyAre() throws IOException {
        // blastn copies a FASTA ID's bytes: Québec_c1 saved as Latin-1 stopped the run here.
        byte[] row = "Qu\u00e9bec_c1\tp-F\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        Path tsv = Files.write(dir.resolve("latin1.tsv"), row);
        BlastTsv.prependHeader(tsv);

        byte[] header = (BlastTsv.HEADER + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] expected = java.util.Arrays.copyOf(header, header.length + row.length);
        System.arraycopy(row, 0, expected, header.length, row.length);
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, Files.readAllBytes(tsv));
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

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void theHitFileKeepsTheFolderUsualPermissions() throws IOException {
        // A temporary file is its owner's only; the hit file was too, to colleagues sharing the folder.
        Path usual = Files.writeString(dir.resolve("usual.txt"), "");
        Path hits = Files.writeString(dir.resolve("s.tsv"), "a\tb\n");

        BlastTsv.prependHeader(hits);

        assertEquals(Files.getPosixFilePermissions(usual), Files.getPosixFilePermissions(hits));
        assertTrue(Files.notExists(dir.resolve("s.tsv.part")));
    }
}
