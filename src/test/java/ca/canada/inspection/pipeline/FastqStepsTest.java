package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The FASTQ steps of a run with BBTools and blastn stood in for (the end-to-end tests run them, with the
 * tools under runtime/): what each step runs, on which files, in which order.
 */
class FastqStepsTest {

    private static final String FORWARD = "GATCCTAGGCATTGCAGTCA";
    private static final String READ = FORWARD + "ACGTTGCAACGTTGCAACGTTGCA";

    @TempDir
    Path dir;

    /** Stand-ins: BBDuk baits two pairs, or copies its input when trimming; Tadpole writes a contig; blastn
     *  finds the forward primer on the first read it is given. */
    private final class Programs implements RunMonitor {
        final List<String> run = Collections.synchronizedList(new ArrayList<>());
        final List<List<String>> commands = Collections.synchronizedList(new ArrayList<>());
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void runProgram(Path directory, List<String> command, String program) {
            run.add(program);
            commands.add(command);
            try {
                switch (program) {
                    case "BBDuk" -> {
                        if (command.contains("ref=adapters")) {
                            Files.copy(path(directory, command, "in="), path(directory, command, "out="));
                        } else if (command.stream().noneMatch(argument -> argument.startsWith("edist="))
                                && command.contains("k=19")) {
                            // The substitution bait: one pair of the first bait's, and one of its own.
                            writeFastq(path(directory, command, "outm="), "p", 1, "q", 1);
                        } else {
                            writeFastq(path(directory, command, "outm="), 2);
                        }
                    }
                    case "BBMerge" -> {
                        writeFastq(path(directory, command, "out="), 0);
                        writeFastq(path(directory, command, "outu="), 2);
                    }
                    case "Tadpole" -> Files.writeString(path(directory, command, "out="),
                            ">contig_0,len=44,cov=10.0,gc=0.5,class=long/medium/unanchored\n" + READ + "\n");
                    case "blastn" -> {
                        Path out = Path.of(command.get(command.indexOf("-out") + 1));
                        Files.writeString(out, String.join("\t", "r0", "a-F", "20", "0", "0", "0.001", "40.1", "20",
                                "20", "1", "20", FORWARD, "1", "20", FORWARD) + "\n");
                    }
                    default -> throw new AssertionError("unexpected program " + program + ": " + command);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void info(String message) {
            messages.add(message);
        }
    }

    private static Path path(Path directory, List<String> command, String option) {
        String value = command.stream().filter(argument -> argument.startsWith(option)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + option + " in " + command)).substring(option.length());
        return directory == null ? Path.of(value) : directory.resolve(value);
    }

    /** Pairs of {@link #READ}, mates one after the other, gzipped (BBTools writes what the name says). */
    private static void writeFastq(Path file, int pairs) throws IOException {
        writeFastq(file, "p", pairs);
    }

    /** {@code pairs} pairs named {@code <prefix>0}, {@code <prefix>1}... for each prefix and count given. */
    private static void writeFastq(Path file, Object... prefixesAndPairs) throws IOException {
        StringBuilder reads = new StringBuilder();
        for (int i = 0; i < prefixesAndPairs.length; i += 2) {
            for (int pair = 0; pair < (int) prefixesAndPairs[i + 1]; pair++) {
                for (int mate = 1; mate <= 2; mate++) {
                    reads.append('@').append(prefixesAndPairs[i]).append(pair).append('/').append(mate).append('\n')
                            .append(READ).append("\n+\n").append("I".repeat(READ.length())).append('\n');
                }
            }
        }
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(reads.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private PipelineContext context(Programs programs) throws IOException {
        return context(programs, 0);
    }

    private PipelineContext context(Programs programs, int mismatches) throws IOException {
        return context(programs, mismatches, "");
    }

    /** {@code moreOligos}: FASTA records added to the primer file. */
    private PipelineContext context(Programs programs, int mismatches, String moreOligos) throws IOException {
        Path input = Files.createDirectories(dir.resolve("reads"));
        writeFastq(input.resolve("s_R1.fastq.gz"), 1);
        writeFastq(input.resolve("s_R2.fastq.gz"), 1);
        Path primerFile = Files.writeString(dir.resolve("primers.fasta"), ">a-F\n" + FORWARD + "\n>a-R\n"
                + "TGACTGCAATGCCTAGGATC\n" + moreOligos);
        Path runtime = Files.createDirectories(dir.resolve("runtime"));
        Path bbmap = Files.createDirectories(runtime.resolve("bbmap"));
        Files.writeString(bbmap.resolve(AppPaths.BBTOOLS_JAR), "");
        Path blast = Files.createDirectories(runtime.resolve("blast"));
        AppPaths.RuntimeLayout layout = new AppPaths.RuntimeLayout(runtime, bbmap, blast, Path.of("java"));
        Path output = dir.resolve("out");
        PipelineConfig config = PipelineConfig.builder(input, output, primerFile).threads(1)
                .ampliconOptions(ca.canada.inspection.pcr.AmpliconOptions.withMismatches(mismatches)).build();
        PipelineDirectories directories = PipelineDirectories.create(output);
        PrimerPanel primers = PrimerService.loadAndPrepare(config, directories, warning -> { });
        return new PipelineContext(config, new RuntimeDependencies(layout), directories,
                InputCheck.scan(input, InputCheck.Depth.QUICK, 1).samples(), primers, 8, List.of(), programs);
    }

    @Test
    void theReadsAreBaitedTwiceTheAnchorsTrimmedInBetweenThenAssembledAndMerged() throws IOException {
        Programs programs = new Programs();
        PipelineContext context = context(programs);

        FastqProcessor.process(context);

        assertEquals(List.of("BBDuk", "blastn", "BBDuk", "BBDuk", "Tadpole", "BBMerge"), programs.run);
        SampleFiles files = context.directories().sample("s");
        // The second baiting baits from the anchors trimmed of adapters, not from the anchors as they were.
        List<String> trim = programs.commands.get(2);
        List<String> second = programs.commands.get(3);
        assertTrue(trim.contains("ref=adapters") && trim.contains("tbo") && trim.contains("interleaved=t")
                && trim.contains("rieb=f") && !trim.contains("tpe"), "each mate trimmed on its own: " + trim);
        assertEquals(files.baitAnchorsTrimmed().getFileName().toString(),
                path(null, second, "ref=").getFileName().toString());
        // The anchors: the read with an accepted primer site (blastn's r0), and its mate.
        assertEquals(8, countLines(files.baitAnchors()), "one pair, four lines a read");
        assertTrue(programs.messages.contains("Sample s: 1 first-bait read(s) hold an accepted primer site"),
                programs.messages.toString());
    }

    @Test
    void atThreeMismatchesTheReadsAreBaitedForSubstitutionsTooEachPairOnce() throws IOException {
        Programs programs = new Programs();
        PipelineContext context = context(programs, 3);

        FastqProcessor.process(context);

        assertEquals(List.of("BBDuk", "BBDuk", "blastn", "BBDuk", "BBDuk", "Tadpole", "BBMerge"), programs.run);
        List<String> first = programs.commands.get(0);
        List<String> substitutions = programs.commands.get(1);
        assertTrue(first.contains("edist=2"), first.toString());
        assertTrue(substitutions.contains("hdist=3") && substitutions.contains("k=19")
                && substitutions.stream().noneMatch(argument -> argument.startsWith("edist=")), substitutions.toString());
        SampleFiles files = context.directories().sample("s");
        // p0 and p1 from the first bait, q0 added; p0 not twice.
        assertEquals(3 * 8, countLines(files.targetMatches()));
        assertTrue(Files.notExists(files.moreMatches()));
        assertTrue(programs.messages.contains("Sample s: 2 read(s) within 3 substitutions of an oligo added to the"
                + " first bait's"), programs.messages.toString());
    }

    @Test
    void aShortProbeBaitsInAPassOfItsOwnAndThePrimersKeepTheirLongerKmers() throws IOException {
        Programs programs = new Programs();
        // 20-base primers have k=17 at -m 1; the 15-base probe 14, which would have been every oligo's.
        PipelineContext context = context(programs, 1, ">a-P\nCCTAGGCATTGCAGT\n");

        FastqProcessor.process(context);

        assertEquals(List.of("BBDuk", "BBDuk", "blastn", "BBDuk", "BBDuk", "Tadpole", "BBMerge"), programs.run);
        Path database = context.directories().primerDatabase();
        List<String> shortPass = programs.commands.get(0);
        List<String> longPass = programs.commands.get(1);
        assertTrue(shortPass.contains("k=14") && path(null, shortPass, "ref=").endsWith("primer_tmp.fasta.k14"),
                shortPass.toString());
        assertTrue(longPass.contains("k=17") && path(null, longPass, "ref=").endsWith("primer_tmp.fasta.k17"),
                longPass.toString());
        assertEquals(">a-P\nCCTAGGCATTGCAGT\n", Files.readString(database.resolveSibling("primer_tmp.fasta.k14")));
        assertEquals(List.of(">a-F", ">a-R"), Files.readAllLines(database.resolveSibling("primer_tmp.fasta.k17")).stream()
                .filter(line -> line.startsWith(">")).toList());
        SampleFiles files = context.directories().sample("s");
        assertEquals(2 * 8, countLines(files.targetMatches()), "the same two pairs from both passes, once each");
        assertTrue(Files.notExists(files.moreMatches()));
        assertTrue(programs.messages.contains("First baiting in 2 passes, so that short oligos do not shorten the k-mers"
                + " of the others: oligos of 15 bases with k=14; oligos of 20 bases with k=17"), programs.messages.toString());
    }

    @Test
    void belowThreeMismatchesThereIsNoSubstitutionBait() throws IOException {
        Programs programs = new Programs();
        FastqProcessor.process(context(programs, 2));

        assertEquals(List.of("BBDuk", "blastn", "BBDuk", "BBDuk", "Tadpole", "BBMerge"), programs.run);
    }

    private static int countLines(Path gzipped) throws IOException {
        try (var in = new java.util.zip.GZIPInputStream(Files.newInputStream(gzipped))) {
            return (int) new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().count();
        }
    }
}
