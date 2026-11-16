package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlastQueryTest {

    private static final String FASTA = ">contig_1\nACGTACGTAC\nGGTTAACC\n";

    @TempDir
    Path directory;

    @Test
    void aPlainFastaIsSearchedWhereItIs() throws IOException {
        Path fasta = Files.writeString(directory.resolve("sample.fasta"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));

        try (BlastQuery query = BlastQuery.of(fasta, scratch)) {
            assertEquals(fasta, query.path());
        }

        assertTrue(Files.exists(fasta));
        assertTrue(filesIn(scratch).isEmpty());
    }

    @Test
    void aGzippedFastaIsDecompressedForTheSearchOnly() throws IOException {
        Path fasta = Files.write(directory.resolve("sample.fasta.gz"), gzip(FASTA));
        Path scratch = Files.createDirectory(directory.resolve("scratch"));
        Path plain;

        try (BlastQuery query = BlastQuery.of(fasta, scratch)) {
            plain = query.path();
            assertNotEquals(fasta, plain);
            assertEquals(scratch, plain.getParent());
            assertEquals(FASTA, Files.readString(plain));
        }

        assertFalse(Files.exists(plain));
        assertTrue(Files.exists(fasta));
    }

    @Test
    void aFastaTooLongForBlastIsSearchedFromACopy() throws IOException {
        // On Windows, blastn cannot open a path of 260 characters or more ("File is not accessible").
        Path fasta = Files.writeString(directory.resolve("sample.fasta"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));
        Path copy;

        try (BlastQuery query = BlastQuery.of(fasta, scratch, true)) {
            copy = query.path();
            assertEquals(scratch, copy.getParent());
            assertEquals(FASTA, Files.readString(copy));
        }

        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(fasta));
    }

    @Test
    void aFastaWithAByteOrderMarkIsSearchedFromACopyWithoutIt() throws IOException {
        // blastn: "CFastaReader: Near line 1, there's a line that doesn't look like plausible data".
        byte[] text = FASTA.getBytes(StandardCharsets.UTF_8);
        byte[] marked = new byte[text.length + 3];
        marked[0] = (byte) 0xEF;
        marked[1] = (byte) 0xBB;
        marked[2] = (byte) 0xBF;
        System.arraycopy(text, 0, marked, 3, text.length);
        Path fasta = Files.write(directory.resolve("notepad.fasta"), marked);
        Path gzipped = Files.write(directory.resolve("notepad.fasta.gz"), gzipBytes(marked));

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertNotEquals(fasta, query.path());
            assertEquals(FASTA, Files.readString(query.path()));
        }
        try (BlastQuery query = BlastQuery.of(gzipped, directory)) {
            assertEquals(FASTA, Files.readString(query.path()));
        }
        assertTrue(Files.exists(fasta));
    }

    @Test
    void theLongestCopyNameIsTheLongestNameOfACopy() throws IOException {
        // WindowsPathLimit counts it among the files a run writes.
        Path fasta = Files.write(directory.resolve("s.fasta.gz"), gzip(FASTA));
        for (int i = 0; i < 20; i++) {
            try (BlastQuery query = BlastQuery.of(fasta, directory)) {
                String name = query.path().getFileName().toString();
                assertTrue(name.length() <= BlastQuery.LONGEST_NAME.length(), name);
                assertTrue(name.startsWith("blast_query_") && name.endsWith(".fasta"), name);
            }
        }
    }

    @Test
    void theExtensionIsReadWhateverItsCase() throws IOException {
        Path fasta = Files.write(directory.resolve("SAMPLE.FNA.GZ"), gzip(FASTA));

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertEquals(FASTA, Files.readString(query.path()));
        }
    }

    @Test
    void everyMemberOfAConcatenatedGzipIsRead() throws IOException {
        // bgzip and "cat a.gz b.gz" both write several gzip members into one file.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(gzip(">contig_1\nACGT\n"));
        bytes.write(gzip(">contig_2\nTTGG\n"));
        Path fasta = Files.write(directory.resolve("sample.fa.gz"), bytes.toByteArray());

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            assertEquals(">contig_1\nACGT\n>contig_2\nTTGG\n", Files.readString(query.path()));
        }
    }

    @Test
    void aFileThatIsNotGzipLeavesNothingBehind() throws IOException {
        Path fasta = Files.writeString(directory.resolve("sample.fasta.gz"), FASTA);
        Path scratch = Files.createDirectory(directory.resolve("scratch"));

        IOException error = assertThrows(IOException.class, () -> BlastQuery.of(fasta, scratch));

        assertTrue(error.getMessage().startsWith("Unable to decompress " + fasta), error.getMessage());
        assertTrue(filesIn(scratch).isEmpty());
    }

    private static java.util.List<Path> filesIn(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.toList();
        }
    }

    private static byte[] gzip(String text) throws IOException {
        return gzipBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzipBytes(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content);
        }
        return bytes.toByteArray();
    }

    @Test
    void aSequenceLongerThanAPieceIsCopiedInOverlappingPiecesAndItsHitsPutBackByTheirOwner() throws IOException {
        // 2.5 pieces long: pieces at 0, 999,000 and 1,998,000, each sharing 1,000 bases with the next. A contig
        // named like a piece is a contig of the user's: its hits stay its own.
        StringBuilder bases = new StringBuilder();
        java.util.Random random = new java.util.Random(3);
        while (bases.length() < 2_500_000) {
            bases.append("ACGT".charAt(random.nextInt(4)));
        }
        Path fasta = Files.writeString(directory.resolve("long.fasta"), ">short one\nACGTACGT\n>"
                + BlastQuery.PIECE_ID + "1\nACGTACGTAC\n>chr1 long\n" + bases + "\n");

        try (BlastQuery query = BlastQuery.of(fasta, directory)) {
            List<String> pieces = query.pieceIds();
            assertEquals(3, pieces.size());
            java.util.Map<String, Integer> lengths = ca.canada.inspection.util.SequenceFileUtils.sequenceLengths(query.path());
            assertEquals(java.util.Map.of("short", 8, BlastQuery.PIECE_ID + "1", 10, pieces.get(0), 1_000_000,
                    pieces.get(1), 1_000_000, pieces.get(2), 502_000), lengths);
            java.util.function.BiFunction<String, int[], String> hit = (id, at) -> String.join("\t", id, "a-F", "20",
                    "0", "0", "1e-5", "40", "20", "20", String.valueOf(at[0]), String.valueOf(at[1]), "ACGT", "1", "20", "ACGT");

            // In the second half of the overlap of the first two pieces: the second piece's hit, put back on chr1.
            assertEquals(hit.apply("chr1", new int[]{999_511, 999_530}), query.toContig(hit.apply(pieces.get(1),
                    new int[]{511, 530})));
            // In the first half: the first piece's; the second's copy is dropped, cut or not.
            assertEquals(hit.apply("chr1", new int[]{999_011, 999_030}), query.toContig(hit.apply(pieces.get(0),
                    new int[]{999_011, 999_030})));
            assertEquals(null, query.toContig(hit.apply(pieces.get(1), new int[]{11, 30})));
            assertEquals(null, query.toContig(hit.apply(pieces.get(0), new int[]{999_600, 999_619})));
            // The last piece owns everything after its first half overlap.
            assertEquals(hit.apply("chr1", new int[]{2_499_981, 2_500_000}), query.toContig(hit.apply(pieces.get(2),
                    new int[]{501_981, 502_000})));
            // Other sequences, the one named like a piece among them, as they are.
            String own = hit.apply(BlastQuery.PIECE_ID + "1", new int[]{1, 10});
            assertEquals(own, query.toContig(own));
            assertEquals("short\ta-F", query.toContig("short\ta-F"));
        }
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("blast_query_")).toList());
        }
    }

    @Test
    void thePieceIdsStartLikeNoSequenceOfTheFile() {
        String prefix = BlastQuery.pieceIdPrefix(java.util.Set.of("chr1", BlastQuery.PIECE_ID + "1"));
        assertTrue(prefix.startsWith(BlastQuery.PIECE_ID) && prefix.endsWith("_") && prefix.length() > BlastQuery.PIECE_ID.length() + 1,
                prefix);
    }

    @Test
    void aCircleGetsAJunctionPieceThatOwnsTheSitesNearItsOrigin() throws IOException {
        // An 1,800-bp circle written with its first 2 bases again at its end, as Tadpole writes one (1,802 bases):
        // its junction piece is its bases 1,301-1,800 then 1-500, and owns the sites starting at its own 251 to
        // 750 (the circle's 1,551 to 1,800, then 1 to 250); the circle owns those from 251 to 1,550.
        StringBuilder bases = new StringBuilder();
        java.util.Random random = new java.util.Random(5);
        while (bases.length() < 1800) {
            bases.append("ACGT".charAt(random.nextInt(4)));
        }
        String circle = bases.toString();
        Path fasta = Files.writeString(directory.resolve("circ.fasta"), ">contig_3,len=1802,class=long/medium/self-loop\n"
                + circle + circle.substring(0, 2) + "\n>linear\nACGTACGTAC\n");

        try (BlastQuery query = BlastQuery.of(fasta, directory, java.util.Map.of(
                "contig_3,len=1802,class=long/medium/self-loop", 1800))) {
            List<String> pieces = query.pieceIds();
            assertEquals(1, pieces.size());
            String junction = sequenceOf(query.path(), pieces.getFirst());
            assertEquals(circle.substring(1300) + circle.substring(0, 500), junction);
            String id = "contig_3,len=1802,class=long/medium/self-loop";
            java.util.function.BiFunction<String, int[], String> hit = (contig, at) -> String.join("\t", contig, "a-F",
                    "20", "0", "0", "1e-5", "40", "20", "20", String.valueOf(at[0]), String.valueOf(at[1]), "ACGT", "1",
                    "20", "ACGT");

            // Across the origin: the junction's, at the circle's 1,791 to 1,810.
            assertEquals(hit.apply(id, new int[]{1791, 1810}), query.toContig(hit.apply(pieces.getFirst(),
                    new int[]{491, 510})));
            // Past the origin: at the circle's start (junction 601-620: 101-120), on either strand.
            assertEquals(hit.apply(id, new int[]{120, 101}), query.toContig(hit.apply(pieces.getFirst(),
                    new int[]{620, 601})));
            // The circle's copies of those sites, cut by its ends or whole, are dropped...
            assertEquals(null, query.toContig(hit.apply(id, new int[]{1791, 1802})));
            assertEquals(null, query.toContig(hit.apply(id, new int[]{101, 120})));
            assertEquals(null, query.toContig(hit.apply(id, new int[]{1, 10})));
            // ...as are the junction's copies of the circle's own, cut by the junction's ends or whole.
            assertEquals(null, query.toContig(hit.apply(pieces.getFirst(), new int[]{1, 20})));
            assertEquals(null, query.toContig(hit.apply(pieces.getFirst(), new int[]{981, 1000})));
            assertEquals(hit.apply(id, new int[]{900, 919}), query.toContig(hit.apply(id, new int[]{900, 919})));
            // A linear sequence's hits, as they are.
            assertEquals(hit.apply("linear", new int[]{1, 10}), query.toContig(hit.apply("linear", new int[]{1, 10})));
        }
    }

    /** The bases of record {@code id} of {@code fasta}. */
    private static String sequenceOf(Path fasta, String id) throws IOException {
        StringBuilder bases = new StringBuilder();
        boolean in = false;
        for (String line : Files.readAllLines(fasta)) {
            if (line.startsWith(">")) {
                in = line.substring(1).split("\\s")[0].equals(id);
            } else if (in) {
                bases.append(line.strip());
            }
        }
        return bases.toString();
    }
}
