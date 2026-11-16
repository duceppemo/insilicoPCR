package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrimerServiceTest {

    @Test
    void nonDegeneratePrimerIsKeptAsIs() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "ACGTACGT"));
        assertEquals(Map.of("assay-F", "ACGTACGT"), expanded);
    }

    @Test
    void degeneratePrimerExpandsToAllCombinations() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "ARYTACG"));
        assertEquals(4, expanded.size()); // R -> A/G, Y -> T/C
        assertTrue(expanded.containsKey("assay-F_0"));
        assertTrue(expanded.values().containsAll(java.util.List.of("AATTACG", "AACTACG", "AGTTACG", "AGCTACG")),
                "unexpected expansions: " + expanded.values());
    }

    @Test
    void lowercaseSequencesAreNormalized() {
        Map<String, String> expanded = PrimerService.expand(map("assay-F", "acgtacg"));
        assertEquals("ACGTACG", expanded.get("assay-F"));
    }

    @Test
    void nonConformingPrimerIdsAreRejectedAtLoadTime() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("16S_27F", "ACGT")));   // no dash
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-fwd", "ACGT"))); // bad type char
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("-F", "ACGT")));        // empty assay name
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-", "ACGT")));    // dash at end
    }

    @Test
    void primerIdsBlastCannotKeepAreRefusedSayingWhy() {
        // makeblastdb wrote Café-F as Caf##-F and succeeded: every hit of the primer was then dropped.
        PipelineException error = assertThrows(PipelineException.class,
                () -> PrimerService.expand(map("Caf\u00e9-F", "ACGTACGT")));
        assertTrue(error.getMessage().contains("(\u00e9)") && error.getMessage().contains("such as Cafe-F"),
                error.getMessage());
        assertEquals("stx_-F", PrimerService.asciiSuggestion("stx\u0394-F"));
        assertDoesNotThrow(() -> PrimerService.expand(map("16S.v2_(a)-F", "ACGTACGT")));
    }

    @Test
    void invalidCharactersAreRejected() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACGU")));
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("assay-F", "ACG T")));
    }

    private static Map<String, String> map(String id, String seq) {
        Map<String, String> primers = new LinkedHashMap<>();
        primers.put(id, seq);
        return primers;
    }
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    @Test
    void loadAndPrepareReadsMultiLineFastaExpandsAndWritesDatabase() throws java.io.IOException {
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("primers.fasta"),
                ">assay-F\nACGT\nacgt\n\n>assay-R\nACRTACG\n");
        java.nio.file.Path input = java.nio.file.Files.writeString(dir.resolve("in.fasta"), ">c\nACGT\n");
        java.nio.file.Path out = dir.resolve("out");
        var config = PipelineConfig.builder(input, out, primerFile).build();
        var directories = PipelineDirectories.create(out);

        PrimerPanel prepared = PrimerService.loadAndPrepare(config, directories);
        Map<String, String> primers = prepared.sequences();

        assertTrue(prepared.expectedSizes().isEmpty());

        assertEquals("ACGTACGT", primers.get("assay-F")); // lines joined, uppercased
        assertEquals("ACATACG", primers.get("assay-R_0"));   // R expanded to A/G
        assertEquals("ACGTACG", primers.get("assay-R_1"));
        String db = java.nio.file.Files.readString(directories.primerDatabase());
        assertTrue(db.contains(">assay-R_0"));
    }

    @Test
    void everyIupacCodeExpandsToItsBases() {
        Map<String, char[]> expected = Map.ofEntries(
                Map.entry("R", "AG".toCharArray()), Map.entry("Y", "TC".toCharArray()),
                Map.entry("S", "GC".toCharArray()), Map.entry("W", "AT".toCharArray()),
                Map.entry("K", "TG".toCharArray()), Map.entry("M", "AC".toCharArray()),
                Map.entry("B", "TGC".toCharArray()), Map.entry("D", "ATG".toCharArray()),
                Map.entry("H", "ATC".toCharArray()), Map.entry("V", "ACG".toCharArray()),
                Map.entry("N", "ATCG".toCharArray()));
        for (var entry : expected.entrySet()) {
            Map<String, String> expanded = PrimerService.expand(map("x-F", "AAAAAA" + entry.getKey()));
            assertEquals(entry.getValue().length, expanded.size(), "code " + entry.getKey());
        }
        // Two Ns expand to 16 combinations.
        assertEquals(16, PrimerService.expand(map("x-F", "AAAAANN")).size());
    }

    @Test
    void anOligoShorterThanBlastsWordIsRefusedAndABadCharacterNamed() {
        // BLAST finds no site of an oligo under 7 bases: the assay was negative everywhere, without a word.
        PipelineException shortOne = assertThrows(PipelineException.class, () -> PrimerService.expand(map("x-F", "ACGTAC")));
        assertTrue(shortOne.getMessage().startsWith("Primer x-F is 6 bases long: BLAST finds no site"), shortOne.getMessage());
        assertEquals(1, PrimerService.expand(map("x-F", "ACGTACG")).size());
        PipelineException bad = assertThrows(PipelineException.class, () -> PrimerService.expand(map("x-F", "ACGT.ACG")));
        assertTrue(bad.getMessage().startsWith("Primer sequence of x-F holds characters that are not bases (.)"),
                bad.getMessage());
    }

    @Test
    void blankSequencesAreRejected() {
        assertThrows(RuntimeException.class, () -> PrimerService.expand(map("x-F", "   ")));
    }

    @Test
    void headerDescriptionIsNotPartOfThePrimerId() throws java.io.IOException {
        // Before, the whole header was the ID: BLAST named the primer "assay-F", no hit
        // matched "assay-F forward primer", and the assay was silently never reported.
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("described.fasta"),
                ">assay-F forward primer, Smith 2010\nACGTACG\n>assay-R\treverse\nACGTACG\n");

        PrimerService.PrimerFile file = PrimerService.read(primerFile);

        assertEquals(java.util.List.of("assay-F", "assay-R"), java.util.List.copyOf(file.sequences().keySet()));
        assertTrue(file.expectedSizes().isEmpty());
        assertEquals(Map.of("assay-F", "ACGTACG", "assay-R", "ACGTACG"), PrimerService.expand(file.sequences()));
    }

    @Test
    void theAssaysOfAMultiplexAreReadFromTheHeaders() throws java.io.IOException {
        // Singleplex and multiplex assays in one file: only those sharing a mix= pair across assays.
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("mixed.fasta"),
                ">a-F mix=panel1\nACGT\n>a-R\nACGT\n>b-F size=300 mix=panel1\nACGT\n>b-R\nACGT\n"
                        + ">c-F\nACGT\n>c-R\nACGT\n");

        PrimerService.PrimerFile file = PrimerService.read(primerFile);

        assertTrue(file.mixGroups().shareAReaction("a", "b"));
        assertFalse(file.mixGroups().shareAReaction("a", "c"));
        assertEquals(java.util.List.of("b"), java.util.List.copyOf(file.expectedSizes().assays()));
    }

    @Test
    void productSizesDeclaredInHeadersAreRead() throws java.io.IOException {
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("sized.fasta"),
                ">its-F size=420-450 other=575\nACGTACG\n>its-R\nACRTACG\n>plain-F\nACGTACG\n>plain-R\nACGTACG\n");
        java.nio.file.Path input = java.nio.file.Files.writeString(dir.resolve("in2.fasta"), ">c\nACGT\n");
        var config = PipelineConfig.builder(input, dir.resolve("out2"), primerFile).build();

        PrimerPanel prepared = PrimerService.loadAndPrepare(config,
                PipelineDirectories.create(dir.resolve("out2")));

        assertEquals(java.util.Set.of("its-F", "its-R_0", "its-R_1", "plain-F", "plain-R"), prepared.ids());
        assertEquals("target", prepared.expectedSizes().labelText("its", 420));
        assertEquals("other", prepared.expectedSizes().labelText("its", 580));
        assertEquals("unexpected", prepared.expectedSizes().labelText("its", 100));
        assertEquals("", prepared.expectedSizes().labelText("plain", 100));
    }

    @Test
    void unreadableProductSizeNamesThePrimerAndTheFile() throws java.io.IOException {
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("bad.fasta"),
                ">assay-F size=big\nACGT\n>assay-R\nACGT\n");

        PipelineException error = assertThrows(PipelineException.class, () -> PrimerService.read(primerFile));

        assertTrue(error.getMessage().startsWith("Primer assay-F: cannot read the product size 'big' in 'size=big'."),
                error.getMessage());
        assertTrue(error.getMessage().endsWith("(primer file " + primerFile + ")"), error.getMessage());
    }

    @Test
    void anAssayWithoutAForwardOrAReversePrimerIsRejectedByName() throws java.io.IOException {
        // Before, such an assay was left out of every report without a word: it looked negative.
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("incomplete.fasta"),
                ">toxA-F size=312\nACGT\n>stxQ-F\nACGT\n>stxQ-R\nACGT\n>stxQ-P\nACGT\n>probeOnly-P\nACGT\n");

        PipelineException error = assertThrows(PipelineException.class, () -> PrimerService.read(primerFile));

        assertEquals("Every assay needs a forward (-F) and a reverse (-R) primer, but probeOnly-F, probeOnly-R, toxA-R"
                + " are missing from the primer file " + primerFile, error.getMessage());
    }

    @Test
    void aBadPrimerIdIsRejectedWhenTheFileIsRead() throws java.io.IOException {
        java.nio.file.Path primerFile = java.nio.file.Files.writeString(dir.resolve("badid.fasta"),
                ">assay-F\nACGT\n>assay_R\nACGT\n");

        PipelineException error = assertThrows(PipelineException.class, () -> PrimerService.read(primerFile));

        assertTrue(error.getMessage().contains("naming convention: assay_R (the role letter ends the ID"), error.getMessage());
    }

    @org.junit.jupiter.api.Test
    void aPrimerFileThatWouldChangeResultsSilentlyIsRejected() throws java.io.IOException {
        java.nio.file.Path emptyProbe = java.nio.file.Files.writeString(dir.resolve("probe.fasta"),
                ">toxA-F\nACGTACGTACGT\n>toxA-R\nTTTTGGGGCCCC\n>toxA-P\n");
        // Left out, the probe-less assay would be reported on its primers alone.
        assertEquals("Primer toxA-P has no sequence in the primer file " + emptyProbe,
                org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class,
                        () -> PrimerService.read(emptyProbe)).getMessage());

        java.nio.file.Path twice = java.nio.file.Files.writeString(dir.resolve("twice.fasta"),
                ">toxB-F\nAAAAAAAAAAAA\n>toxB-F\nGGGGGGGGGGGG\n>toxB-R\nCCCCCCCCCCCC\n");
        assertEquals("Primer ID toxB-F appears twice in the primer file " + twice
                        + "; give each primer its own ID (toxB-F_1, toxB-F_2)",
                org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class,
                        () -> PrimerService.read(twice)).getMessage());

        java.nio.file.Path degenerate = java.nio.file.Files.writeString(dir.resolve("n.fasta"),
                ">wide-F\nACGTNNNNNNNNNACGT\n>wide-R\nACGTACGTACGT\n");
        java.nio.file.Path out = dir.resolve("out3");
        var config = PipelineConfig.builder(degenerate, out, degenerate).build();
        var error = org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class,
                () -> PrimerService.loadAndPrepare(config, PipelineDirectories.create(out)));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().startsWith("Primer wide-F is too degenerate"),
                error.getMessage());
    }

    @org.junit.jupiter.api.Test
    void aPrimerFileThatWouldLoseAPrimerSilentlyIsRejected() throws java.io.IOException {
        // X-F_1 would replace the second sequence of the degenerate X-F (R: A or G).
        java.nio.file.Path clash = java.nio.file.Files.writeString(dir.resolve("clash.fasta"),
                ">X-F\nACGTRACGTACG\n>X-F_1\nTTTTTTTTTTTT\n>X-R\nCCCCCCCCCCCC\n");
        java.nio.file.Path out = dir.resolve("out4");
        var config = PipelineConfig.builder(clash, out, clash).build();
        assertEquals("Primer X-F is degenerate: its sequences are named X-F_0 to X-F_1, and X-F_1 is also the ID"
                        + " of a primer; give that primer another ID",
                assertThrows(PipelineException.class,
                        () -> PrimerService.loadAndPrepare(config, PipelineDirectories.create(out))).getMessage());

        java.nio.file.Path noHeader = java.nio.file.Files.writeString(dir.resolve("plain.txt"), "ACGTACGTACGT\n");
        assertEquals("The primer file " + noHeader + " has a sequence before its first header line (a line starting"
                + " with > and the primer ID)", assertThrows(PipelineException.class,
                () -> PrimerService.read(noHeader)).getMessage());

        java.nio.file.Path empty = java.nio.file.Files.writeString(dir.resolve("empty.fasta"), "\n");
        assertTrue(assertThrows(PipelineException.class, () -> PrimerService.read(empty)).getMessage()
                .startsWith("The primer file " + empty + " has no primer"));
    }

    @Test
    void aPrimerFileSavedByNotepadIsRead() throws java.io.IOException {
        // UTF-8 with a byte order mark and CRLF line ends: refused for "a sequence before its first header line".
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] text = ">toxA-F size=312\r\nTTTCCTCATGCAATTCAAAACC\r\n>toxA-R\r\nATGTCCGTAATGTAGGCGAAA\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] both = new byte[bom.length + text.length];
        System.arraycopy(bom, 0, both, 0, bom.length);
        System.arraycopy(text, 0, both, bom.length, text.length);
        java.nio.file.Path notepad = java.nio.file.Files.write(dir.resolve("notepad.fasta"), both);

        PrimerService.PrimerFile file = PrimerService.read(notepad);

        assertEquals(java.util.Set.of("toxA-F", "toxA-R"), file.sequences().keySet());
        assertEquals("TTTCCTCATGCAATTCAAAACC", file.sequences().get("toxA-F"));
    }
}
