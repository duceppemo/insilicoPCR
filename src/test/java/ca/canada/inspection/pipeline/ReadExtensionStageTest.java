package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerId;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.util.SequenceFileUtils;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.FastqDiagnostics.Assembly;
import ca.canada.inspection.pcr.FastqDiagnostics.Finding;
import ca.canada.inspection.pcr.FastqDiagnostics.ReadsWithOligo;
import ca.canada.inspection.pcr.FastqDiagnostics.Status;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TestRows;
import ca.canada.inspection.pcr.ReadExtensionCaller.Outcome;
import ca.canada.inspection.pcr.ReadExtensionCaller.Seed;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadExtensionStageTest {

    private static final String PRIMER = "ACGTACGTACGTACGTACGT";
    private static final PrimerPanel PRIMERS = PrimerPanel.of(Map.of("long-F", PRIMER, "long-R", PRIMER));

    @TempDir
    Path dir;

    @Test
    void seedsAreWrittenUnderTheirOwnIdsOncePerAssayTheyServe() throws IOException {
        Path reads = Files.writeString(dir.resolve("reads.fasta"),
                ">read_0 a/1\nAAAA\n>read_1 a/2\nCCCC\n>read_2 b/1\nGGGG\n");
        // read_1 carries a primer of two assays: one copy each, judged for its own assay.
        List<Seed> seeds = List.of(new Seed("extended_read_0", "read_1", "long", PrimerId.Role.FORWARD),
                new Seed("extended_read_1", "read_2", "long", PrimerId.Role.REVERSE),
                new Seed("extended_read_2", "read_1", "other", PrimerId.Role.REVERSE));
        Path seedsFile = dir.resolve("seeds.fasta");

        Map<String, BaitedReads.ReadRecord> used = ReadExtensionStage.writeSeeds(seeds, reads, seedsFile);

        assertEquals(List.of(">extended_read_0 long-F read_1", "CCCC", ">extended_read_1 long-R read_2", "GGGG",
                ">extended_read_2 other-R read_1", "CCCC"), Files.readAllLines(seedsFile));
        assertEquals(Set.of("read_1", "read_2"), used.keySet());
        assertEquals("a/2", used.get("read_1").description());
        assertThrows(PipelineException.class, () -> ReadExtensionStage.writeSeeds(
                List.of(new Seed("extended_read_0", "read_9", "long", PrimerId.Role.FORWARD)), reads, seedsFile));
    }

    @Test
    void productOnExtendedReadsIsCalledFromTheBlastTable() throws IOException {
        // Four extended reads of 1300 bases; each holds the forward primer at 101-120 and the
        // reverse primer at 1155-1174: a 1074 bp product, from two seeds of each primer.
        StringBuilder fasta = new StringBuilder();
        StringBuilder hits = new StringBuilder(BlastTsv.HEADER + "\n");
        for (int i = 0; i < 4; i++) {
            // Tadpole wraps its output: lengths must be read over several lines.
            fasta.append(">extended_read_").append(i).append('\n')
                    .append("A".repeat(700)).append('\n').append("A".repeat(600)).append('\n');
            hits.append(hit("extended_read_" + i, "long-F", 101, 120, 1, 20));
            hits.append(hit("extended_read_" + i, "long-R", 1155, 1174, 20, 1));
        }
        Path extended = Files.writeString(dir.resolve("S_extendedReads.fasta"), fasta.toString());
        Path hitsFile = Files.writeString(dir.resolve("S_extendedReads.blast.txt"), hits.toString());
        List<Seed> seeds = List.of(new Seed("extended_read_0", "read_5", "long", PrimerId.Role.FORWARD),
                new Seed("extended_read_1", "read_6", "long", PrimerId.Role.FORWARD),
                new Seed("extended_read_2", "read_7", "long", PrimerId.Role.REVERSE),
                new Seed("extended_read_3", "read_8", "long", PrimerId.Role.REVERSE));
        AmpliconOptions options = AmpliconOptions.withMismatches(0);

        Map<String, Integer> lengths = SequenceFileUtils.sequenceLengths(extended);
        var extendedHits = ReadExtensionStage.extendedHits("S", hitsFile, extended, lengths, PRIMERS, options, warning -> {});
        Outcome outcome = ReadExtensionCaller.evaluate("S", extendedHits, lengths, seeds, PRIMERS, options, 5000).get("long");

        assertEquals(1300, lengths.get("extended_read_0"));
        assertEquals(1074, outcome.closed().getFirst().size());
        assertEquals("2 of 2 forward-seeded and 2 of 2 reverse-seeded reads", outcome.closed().getFirst().supportSummary());
    }

    @Test
    void candidateProductsAreCutFromTheirExtendedReadsAndCheckedAgainstTheReadPairs() throws IOException {
        // One extended read of 700 bases holding a product at 101-600; Tadpole wraps its output.
        java.util.Random random = new java.util.Random(11);
        StringBuilder bases = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            bases.append("ACGT".charAt(random.nextInt(4)));
        }
        String extendedRead = bases.toString();
        Path extended = Files.writeString(dir.resolve("S_extendedReads.fasta"), ">other\nACGT\n>extended_read_3 x\n"
                + extendedRead.substring(0, 350) + "\n" + extendedRead.substring(350) + "\n");
        ReadExtensionCaller.Product product = new ReadExtensionCaller.Product("long", 500, Set.of(500), 2, 2, 2, 2,
                TestRows.row("S", "long", 101, 600, "extended_read_3", ""));

        List<String> sequences = ReadExtensionStage.candidateSequences(List.of(product), extended);
        assertEquals(List.of(extendedRead.substring(100, 600)), sequences);

        // Interleaved pairs of 100-base reads from fragments of 300 bases, every 4 bases of the read.
        StringBuilder fastq = new StringBuilder();
        for (int start = 0; start + 300 <= 700; start += 4) {
            String forward = extendedRead.substring(start, start + 100);
            String reverse = new StringBuilder(extendedRead.substring(start + 200, start + 300)).reverse().toString()
                    .replace('A', 't').replace('T', 'a').replace('C', 'g').replace('G', 'c').toUpperCase();
            fastq.append("@p").append(start).append("/1\n").append(forward).append("\n+\n").append("I".repeat(100))
                    .append("\n@p").append(start).append("/2\n").append(reverse).append("\n+\n")
                    .append("I".repeat(100)).append("\n");
        }
        Path reads = Files.writeString(dir.resolve("S_extensionCandidateReads.fastq"), fastq.toString());

        var verdict = ReadExtensionStage.tally(sequences, reads, true, null).verdict(0);
        assertEquals(ca.canada.inspection.pcr.PairSupport.Kind.SUPPORTED, verdict.kind(), verdict.summary());
        assertTrue(verdict.paired());

        // The same file read as unpaired reads: each read is its own fragment, and the windows are shorter.
        var single = ReadExtensionStage.tally(sequences, reads, false, null).verdict(0);
        assertTrue(!single.paired() && single.window() <= 100, single.summary());
    }

    @Test
    void rowsCalledByExtensionFollowTheReadLevelRowsOfASample() {
        Sample sample = new Sample("S");
        sample.setFileType(Sample.FileType.FASTQ);
        ReportRow row = TestRows.row("S", "long", 101, 1174, "extended_read_0", "FASTQ read-extension call");
        ReadExtensionStage.Result extension = new ReadExtensionStage.Result(Map.of(), Map.of("S", List.of(row)),
                List.of());

        Map<String, List<ReportRow>> rows = ReportGenerator.extraRows(
                TestContexts.writingTo(java.nio.file.Path.of("unused"), Map.of("S", sample)),
                Map.of("S", new ReadEvidence(Map.of(), Map.of(), Map.of())), extension);

        assertEquals(Map.of("S", List.of(row)), rows);
        assertTrue(ReadExtensionStage.Result.NONE.of("S").isEmpty());
    }

    @Test
    void logAndQaLinesReportTheExtension() {
        Finding called = new Finding("S", "long", Status.CALLED_BY_EXTENSION, Assembly.SPLIT_ACROSS_CONTIGS,
                new ReadsWithOligo(6134, 5424, 0), 579, "reads extended through the baited reads hold a product of"
                + " 1074 bp (142 of 150 forward-seeded and 145 of 150 reverse-seeded reads)", "");
        Finding notClosed = new Finding("T", "long", Status.NOT_CLOSED_BY_EXTENSION, Assembly.NOT_ASSEMBLED,
                new ReadsWithOligo(19, 7, 0), 144, "gave no product held by at least 2 extended reads seeded from each primer", "");
        Finding wrongTurn = new Finding("V", "long", Status.EXTENSION_NOT_SUPPORTED_BY_PAIRS, Assembly.NOT_ASSEMBLED,
                new ReadsWithOligo(52, 46, 0), 480, "extended reads hold a product of 727 bp (44 of 52 forward-seeded"
                + " and 37 of 46 reverse-seeded reads), not reported: the 300-base window at 191-490 is held by 1 read"
                + " pair, against 14 for the median window", "");
        Finding repeat = new Finding("W", "long", Status.EXTENSION_THROUGH_UNRESOLVED_REPEAT, Assembly.NOT_ASSEMBLED,
                new ReadsWithOligo(50, 50, 0), 480, "not reported: bases 228-827 are a repeat", "");
        FastqDiagnosticsStage.Result result = new FastqDiagnosticsStage.Result(Path.of("report_fastq_diagnostics.tsv"),
                List.of(called, wrongTurn, repeat, notClosed),
                List.of("read extension could not be run for sample U (boom)"));

        List<String> log = result.logLines();
        assertTrue(log.getFirst().startsWith("Note: sample S, assay long: product in no contig and in no read,"
                + " called by extending reads (reads extended through the baited reads hold a product of 1074 bp"),
                log.getFirst());
        assertTrue(log.contains("Note: 1 FASTQ-mode negative(s) have both primers in the reads, but neither a read nor"
                + " the extended reads hold a complete amplicon; see report_fastq_diagnostics.tsv"), log.toString());
        assertTrue(log.contains("Warning: read extension could not be run for sample U (boom)"));
        assertTrue(log.stream().anyMatch(line -> line.startsWith("Warning: sample V, assay long: extended reads join"
                + " the primers, but read pairs do not support the product; not reported (extended reads hold a"
                + " product of 727 bp")), log.toString());
        assertTrue(log.stream().anyMatch(line -> line.startsWith("Warning: sample W, assay long: extended reads join"
                + " the primers through a repeat longer than the read pairs can bridge; not reported")),
                log.toString());

        List<String> on = result.qaLogLines();
        assertTrue(on.stream().anyMatch(line -> line.startsWith("FASTQ mode: 1 assay(s) have a product in no contig"
                + " and in no read, called by extending reads (rows marked 'FASTQ read-extension call'")), on.toString());
        assertTrue(on.stream().anyMatch(line -> line.startsWith("FASTQ mode: 1 negative assay(s) have both primers in"
                + " the reads, but neither a read nor the extended reads hold a complete amplicon")), on.toString());

        assertTrue(on.stream().anyMatch(line -> line.startsWith("FASTQ mode WARNING: 2 assay(s) have a product that"
                + " extended reads of both primers hold but read pairs do not confirm; NOT reported")), on.toString());
    }

    /** One BLAST TSV line: a perfect full-length primer hit at positions {@code qstart-qend}. */
    private static String hit(String read, String primerId, int qstart, int qend, int sstart, int send) {
        return String.join("\t", read, primerId, "20", "0", "0", "0.001", "40.1", "20", "20",
                String.valueOf(qstart), String.valueOf(qend), PRIMER, String.valueOf(sstart), String.valueOf(send),
                PRIMER) + "\n";
    }

    @Test
    void anExtensionThatRunsOutOfMemoryIsANoteAndTheRunGoesOn() throws IOException {
        // Tadpole stood in for: it stops for lack of memory, as with -XX:+ExitOnOutOfMemoryError.
        List<String> started = new java.util.ArrayList<>();
        RunMonitor monitor = new RunMonitor() {
            @Override
            public void runProgram(Path directory, List<String> command, String program) {
                started.add(program);
                throw new ProgramFailedException(program, BbtoolsCommands.OUT_OF_MEMORY_EXIT, false, program + " failed");
            }

            @Override
            public void info(String message) {
            }
        };
        Sample sample = new Sample("S");
        sample.setFileType(Sample.FileType.FASTQ);
        PipelineDirectories directories = new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve(PipelineDirectories.CONSOLIDATED));
        SampleFiles files = directories.sample("S");
        Files.createDirectories(files.directory());
        Files.writeString(files.doubleTargetMatches(), "");
        Files.writeString(files.baitedReadsFasta(), ">read_0 a/1\nACGT\n>read_1 a/2\nTTTT\n");
        // Both primers in the reads, no product yet: the assay is extended.
        ReadEvidence evidence = new ReadEvidence(Map.of("long-F", Set.of("read_0"), "long-R", Set.of("read_1")),
                Map.of(), Map.of());
        ReadExtensionStage.Settings settings = new ReadExtensionStage.Settings(
                new ReadLevelStage.Settings(Path.of("blastn"), Path.of("primer_tmp.fasta"), 1e5, 1, 0.3, monitor),
                new BbtoolsCommands("java", Path.of("bbtools.jar"), 4, 1), ReadExtensionCaller.Mode.UNREPORTED);

        ReadExtensionStage.Result result = ReadExtensionStage.run(settings, directories, Map.of("S", sample), PRIMERS,
                AmpliconOptions.withMismatches(0), Map.of("S", evidence));

        assertEquals(List.of("Tadpole"), started);
        assertEquals(List.of("read extension could not be run for sample S ("
                + BbtoolsCommands.outOfMemoryMessage("Tadpole", "", 4) + ")"), result.notes());
        assertTrue(result.rows().isEmpty());
    }
}
