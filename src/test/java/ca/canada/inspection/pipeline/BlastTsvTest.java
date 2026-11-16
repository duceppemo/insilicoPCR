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

    private static final String PRIMER = "ACGTACGTACGTACGTACGT";
    private static final ca.canada.inspection.pcr.PrimerPanel PRIMERS =
            ca.canada.inspection.pcr.PrimerPanel.of(java.util.Map.of("a-F", PRIMER, "a-R", PRIMER));

    /** A line of blastn's table: the whole primer at 101-120 of {@code contig}, with {@code mismatches}. */
    private static String hit(String contig, int mismatches) {
        return String.join("\t", contig, "a-F", "20", String.valueOf(mismatches), "0", "1e-5", "40.1",
                "20", "20", "101", "120", PRIMER, "1", "20", PRIMER);
    }

    /** Keeps the accepted hits of {@code tsv}, and gives them as taken: one pass for the table and the hits. */
    private static ca.canada.inspection.model.Hits keep(Path tsv) {
        ca.canada.inspection.model.Hits hits = new ca.canada.inspection.model.Hits();
        BlastTsv.keepAccepted(tsv, List.of(), PRIMERS, ca.canada.inspection.pcr.AmpliconOptions.withMismatches(1), hits);
        return hits;
    }

    @Test
    void theTableKeepsTheAcceptedHitsUnderItsHeader() throws IOException {
        // blastn lists every alignment (1.5 million on a genome for a 40-assay panel), a few thousand of them
        // primer sites: the table kept them all, then was copied again to add its header.
        Path tsv = Files.writeString(dir.resolve("sample.tsv"), hit("c1", 0) + "\n" + hit("c2", 5) + "\n\n" + hit("c3", 1) + "\n");

        ca.canada.inspection.model.Hits hits = keep(tsv);

        // The same hits taken, the table not read again for them.
        assertEquals(List.of("c1", "c3"), hits.byOligo().get("a-F").stream().map(hit -> hit.queryID()).toList());

        assertEquals(List.of(BlastTsv.HEADER, hit("c1", 0), hit("c3", 1)), Files.readAllLines(tsv));
    }

    @Test
    void anIdThatIsNotUtf8IsReadAsTheFastaIs() throws IOException {
        // blastn copies a FASTA ID's bytes: Québec_c1 saved as Latin-1 stopped the run when read strictly.
        Path tsv = Files.write(dir.resolve("latin1.tsv"),
                (hit("Qu\u00e9bec_c1", 0) + "\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        keep(tsv);

        assertEquals(List.of(BlastTsv.HEADER, hit("Qu\uFFFDbec_c1", 0)), Files.readAllLines(tsv));
    }

    @Test
    void missingFileBecomesHeaderOnly() throws IOException {
        Path tsv = dir.resolve("absent.tsv");
        keep(tsv);
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
        Path hits = Files.writeString(dir.resolve("s.tsv"), hit("c1", 0) + "\n");

        keep(hits);

        assertEquals(Files.getPosixFilePermissions(usual), Files.getPosixFilePermissions(hits));
        assertTrue(Files.notExists(dir.resolve("s.tsv.part")));
    }
}
