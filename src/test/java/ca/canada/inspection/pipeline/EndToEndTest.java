package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.FastqDiagnostics;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pipeline.RunMonitor.Step;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Whole runs on the five-isolate example (docs/examples/five-isolates) with the BLAST+ and
 * BBTools of the checkout, as the command line and the application run them. Skipped
 * when the tools are not there (a checkout without Git LFS); exclude with
 * {@code -Dsurefire.excludedGroups=end-to-end} for a quick build.
 */
@Tag("end-to-end")
class EndToEndTest {

    private static final Path EXAMPLE = Path.of("docs", "examples", "five-isolates").toAbsolutePath();
    private static final Path PRIMERS = EXAMPLE.resolve("primers.fasta");

    @TempDir
    Path directory;

    @BeforeAll
    static void requireTools() {
        AppPaths.RuntimeLayout layout;
        try {
            layout = AppPaths.discover();
        } catch (RuntimeException e) {
            assumeTrue(false, "BLAST+ and BBTools are not in runtime/: " + e.getMessage());
            return;
        }
        // Without Git LFS the tools are small pointer files.
        assumeTrue(sizeOf(layout.blastnExecutable()) > 100_000, "blastn is a Git LFS pointer");
        assumeTrue(sizeOf(layout.bbmapDirectory().resolve("bbtools.jar")) > 100_000, "bbtools.jar is a Git LFS pointer");
    }

    @Test
    void theAssembliesGiveTheReportOfTheExample() throws IOException {
        Path genomes = Files.createDirectory(directory.resolve("genomes"));
        try (Stream<Path> files = Files.list(EXAMPLE.resolve("genomes"))) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                // Two of the five gzipped, to cover both ways an assembly is read.
                if (name.equals("isolate_02.fasta") || name.equals("isolate_04.fasta")) {
                    gzip(file, genomes.resolve(name + ".gz"));
                } else {
                    Files.copy(file, genomes.resolve(name));
                }
            }
        }
        Path output = directory.resolve("results");

        RunResult result = pipeline(config(genomes, output)).run();

        assertEquals(lines(EXAMPLE.resolve("report.tsv")), lines(report(output)));
        assertEquals(report(output), result.report());
        assertEquals(output.resolve("consolidated_report").resolve("report.html"), result.html());
        assertEquals(List.of("isolate_01", "isolate_02", "isolate_03", "isolate_04", "isolate_05"), result.samples());
        assertEquals(result.samples(), PreviousRun.open(output).samples());
        assertTrue(Files.size(output.resolve("consolidated_report").resolve("report.html")) > 0);
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("isolate_05.fasta"));
        assertFalse(Files.readString(output.resolve("QAlog.txt")).contains("Primers at contig ends"));
        try (Stream<Path> files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("blast_query_"))
                    .toList(), "decompressed BLAST queries left in the results");
        }
    }

    @Test
    void aSequenceLongerThanAPieceIsSearchedInPiecesAndItsProductsKeepTheirPlace() throws IOException {
        // A chromosome-long sequence gave a weak primer site an e-value over the cut-off, which a draft of
        // the same genome kept: it is searched in overlapping pieces, its hits put back on it. Here the toxA
        // product of isolate_01 (4001-4312 there) straddles the end of the first piece.
        StringBuilder isolate = new StringBuilder();
        for (String line : lines(EXAMPLE.resolve("genomes").resolve("isolate_01.fasta"))) {
            if (!line.startsWith(">")) {
                isolate.append(line.strip());
            }
        }
        java.util.Random random = new java.util.Random(7);
        StringBuilder chromosome = new StringBuilder();
        int site = BlastQuery.PIECE_LENGTH - 400; // 0-based start of the copied stretch
        while (chromosome.length() < site) {
            chromosome.append("ACGT".charAt(random.nextInt(4)));
        }
        chromosome.append(isolate, 3900, 4412); // bases 3901-4412 of isolate_01
        while (chromosome.length() < 1_500_000) {
            chromosome.append("ACGT".charAt(random.nextInt(4)));
        }
        Path genomes = Files.createDirectory(directory.resolve("chromosome"));
        StringBuilder fasta = new StringBuilder(">chr1 a sequence longer than a piece\n");
        for (int i = 0; i < chromosome.length(); i += 80) {
            fasta.append(chromosome, i, Math.min(chromosome.length(), i + 80)).append('\n');
        }
        Files.writeString(genomes.resolve("big.fasta"), fasta);
        Path output = directory.resolve("chromosome-results");

        pipeline(config(genomes, output)).run();

        // Where it is on the sequence: 101 bases into the stretch copied from 3901.
        int start = site + (4001 - 3901) + 1;
        String expected = "big\ttoxA\t" + start + "-" + (start + 311) + "\t312\tchr1";
        assertTrue(lines(report(output)).stream().anyMatch(row -> row.startsWith(expected)),
                expected + " in " + lines(report(output)));
        try (Stream<Path> files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("blast_query_"))
                    .toList(), "the copy in pieces left in the results");
        }
    }

    @Test
    void aPrimerSiteCutByTheStartOfAPieceGetsTheVerdictItGetsInAShortContig() throws IOException {
        // toxA-F starts at 999,000 of a long sequence, a base before the second piece (999,001 on): that piece
        // holds it cut, and BLAST trims its first 5 bases there, past a mismatch at its base 5. Kept, the cut copy
        // passed as a site of no mismatch, with 5 unaligned 5' bases; the same site in a short contig has one.
        StringBuilder isolate = new StringBuilder();
        for (String line : lines(EXAMPLE.resolve("genomes").resolve("isolate_01.fasta"))) {
            if (!line.startsWith(">")) {
                isolate.append(line.strip());
            }
        }
        StringBuilder stretch = new StringBuilder(isolate.substring(3900, 4412)); // toxA-F at its base 101
        int mismatch = 100 + 4; // toxA-F's base 5
        stretch.setCharAt(mismatch, stretch.charAt(mismatch) == 'A' ? 'C' : 'A');
        java.util.Random random = new java.util.Random(5);
        java.util.function.IntFunction<String> randomBases = length -> {
            StringBuilder bases = new StringBuilder();
            while (bases.length() < length) {
                bases.append("ACGT".charAt(random.nextInt(4)));
            }
            return bases.toString();
        };
        String chromosome = randomBases.apply(999_000 - 1 - 100) + stretch + randomBases.apply(600_000);
        Path genomes = Files.createDirectory(directory.resolve("cut"));
        Files.writeString(genomes.resolve("big.fasta"), ">chr1\n" + chromosome + "\n");
        Files.writeString(genomes.resolve("small.fasta"), ">ctg1\n" + randomBases.apply(2_000) + stretch
                + randomBases.apply(2_000) + "\n");
        Path output = directory.resolve("cut-results");

        pipeline(PipelineConfig.builder(genomes, output, PRIMERS).threads(2)
                .ampliconOptions(ca.canada.inspection.pcr.AmpliconOptions.withMismatches(1)).build()).run();

        // Gene, size, primers, mismatches and 5' unaligned bases: the same for both.
        List<String> rows = lines(report(output));
        java.util.function.Function<String, List<String>> verdicts = sample -> rows.stream()
                .filter(row -> row.startsWith(sample + "\t"))
                .map(row -> {
                    String[] cells = row.split("\t", -1);
                    return String.join(" ", cells[1], cells[3], cells[6], cells[7], cells[8], cells[9], cells[10],
                            cells[11], cells[17], cells[18]);
                }).sorted().toList();
        List<String> small = verdicts.apply("small");
        assertTrue(small.stream().anyMatch(verdict -> verdict.startsWith("toxA ")), small.toString());
        assertEquals(small, verdicts.apply("big"));
    }

    @Test
    void aProductAcrossABreakInTheAssemblyIsWarnedAbout() throws IOException {
        // isolate_01 gives toxA at 4001-4312 of its one contig; cut the contig at 4150.
        List<String> lines = lines(EXAMPLE.resolve("genomes").resolve("isolate_01.fasta"));
        String sequence = String.join("", lines.subList(1, lines.size()));
        Path genomes = Files.createDirectory(directory.resolve("broken"));
        Files.writeString(genomes.resolve("isolate_01.fasta"), ">part_a\n" + sequence.substring(0, 4150) + "\n>part_b\n"
                + sequence.substring(4150) + "\n");
        Recorder monitor = new Recorder();
        Path output = directory.resolve("broken-results");

        pipeline(config(genomes, output), monitor).run();

        assertEquals(List.of("isolate_01 stxQ 118 target"), products(report(output)));
        List<String> warnings = monitor.warnings.stream().filter(line -> line.contains("contig")).toList();
        assertEquals(1, warnings.size(), monitor.warnings.toString());
        // Nothing is lost at the cut, so the smallest product across it is the real one.
        assertTrue(warnings.getFirst().startsWith("Warning: sample isolate_01, assay toxA: no product, but the"
                + " primers bind near the ends of two contigs:"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("Joined, they would give a product of at least 312 bp"),
                warnings.getFirst());
        assertTrue(Files.readString(output.resolve("QAlog.txt"))
                .contains("Primers at contig ends: sample isolate_01, assay toxA:"));
        String html = Files.readString(output.resolve("consolidated_report").resolve("report.html"));
        assertTrue(html.contains("Primers at contig ends (FASTA input)"));
        assertTrue(html.contains("no product, primers at contig ends"));
    }

    @Test
    void readsGiveTheProductsOfTheirIsolateAndTheWindowGetsEveryLine() throws IOException {
        Recorder monitor = new Recorder();
        Path output = directory.resolve("reads-results");

        pipeline(config(EXAMPLE.resolve("reads"), output), monitor).run();

        assertEquals(List.of("isolate_01 stxQ 118 target", "isolate_01 toxA 312 target"), products(report(output)));
        // As the application shows them: no program wrote to the console, every step was reported.
        assertEquals(Arrays.asList(Step.values()), monitor.steps);
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("BBDuk")), "no BBDuk output");
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("Tadpole")), "no Tadpole output");
        assertTrue(monitor.messages.contains("Completed read-level amplicon search"), monitor.messages.toString());
        assertTrue(monitor.messages.stream().anyMatch(line -> line.startsWith("Done in ")));
        assertFalse(monitor.blastProgress.isEmpty());
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("  Tadpole assembly (FASTQ, --tadpole-...): k="));
        assertTrue(monitor.messages.contains("  BLAST e-value (-e): 100000"), "the run's log lists its settings");
    }

    @Test
    void samplesProcessedAtOnceShareTheThreads() throws IOException {
        Path reads = twoSamples("first", "second");
        Recorder monitor = new Recorder();
        Path output = directory.resolve("two-samples-results");

        pipeline(config(reads, output), monitor).run();

        // Two threads: both samples at once with one thread each, or, on a small heap, one after the other.
        ChildJavaMemory.Share share = ChildJavaMemory.share(ChildJavaMemory.budgetGiB(), 2, 2);
        assertTrue(monitor.messages.stream().anyMatch(line -> line.startsWith("BBTools: ")
                && line.endsWith(share.threads() + " threads and -Xmx" + share.memoryGiB() + "g")), monitor.messages.toString());
        List<String> commands = monitor.programLines.stream().filter(line -> line.startsWith("Executing ")).toList();
        assertFalse(commands.isEmpty());
        for (String command : commands) {
            assertTrue(command.matches(".*\\bthreads=" + share.threads() + "[,\\]].*"), command);
        }
        assertEquals(List.of("first stxQ 118 target", "first toxA 312 target",
                "second stxQ 118 target", "second toxA 312 target"), products(report(output)));
    }

    @ParameterizedTest
    @EnumSource(OutOfMemory.class)
    void aSampleThatRunsOutOfMemoryIsRunAgainAloneWithAllTheMemory(OutOfMemory how) throws IOException {
        Recorder monitor = new Recorder();
        Path output = directory.resolve("out-of-memory-results");
        // 8 GiB and 2 threads: the two samples get 4 GiB and 1 thread each, alone 8 GiB and 2 threads.
        PipelineContext context = readContext(twoSamples("big", "small"), output, 8, monitor, how);

        FastqProcessor.process(context);

        assertEquals(List.of("Warning: Tadpole ran out of memory on sample big with -Xmx4g; it will be run again"
                + " after the other samples, alone, with -Xmx8g"), monitor.warnings);
        assertTrue(monitor.messages.contains(
                "Running the samples that ran out of memory again, one at a time, with 2 threads and -Xmx8g: big"),
                monitor.messages.toString());
        assertTrue(monitor.messages.contains("Completed sample big"), monitor.messages.toString());
        // Run again from the assembly on: no baiting twice.
        assertEquals(4, executed(monitor, "jgi.BBDuk").size());
        assertEquals(List.of("threads=1", "threads=2"), executed(monitor, "assemble.Tadpole"));
        // Only "small" is left to merge at once: it runs alone, with everything, and so does "big" after it.
        assertEquals(List.of("threads=2", "threads=2"), executed(monitor, "jgi.BBMerge"));
        assertTrue(monitor.messages.contains("BBTools: one sample at a time, with 2 threads and -Xmx8g"),
                monitor.messages.toString());
        for (String sample : List.of("big", "small")) {
            Path assembly = context.directories().sample(sample).assembly();
            assertTrue(Files.size(assembly) > 0, assembly.toString());
        }
    }

    @ParameterizedTest
    @EnumSource(OutOfMemory.class)
    void aSampleThatRunsOutOfMemoryAloneFailsTheRunWithAHint(OutOfMemory how) throws IOException {
        Recorder monitor = new Recorder();
        // 4 GiB: one sample at a time already, with all of it.
        PipelineContext context = readContext(twoSamples("big", "small"), directory.resolve("results"), 4, monitor,
                how);

        PipelineException error = assertThrows(PipelineException.class, () -> FastqProcessor.process(context));

        assertTrue(RunFailure.isForTheUser(error));
        assertTrue(RunFailure.lines(error).stream().anyMatch(line -> line.contains(
                "Tadpole ran out of memory on sample big with -Xmx4g, all it had. Give the BBTools programs more with"
                        + " --bbtools-memory")), RunFailure.lines(error).toString());
        assertTrue(monitor.warnings.isEmpty(), monitor.warnings.toString());
    }

    @Test
    void readsAreExtendedForAnAssayWithoutAProductOfItsDeclaredSize() throws IOException {
        // toxA gives 312 bp; declaring 500 bp makes the target mode extend its reads.
        Path primers = Files.writeString(directory.resolve("primers.fasta"),
                Files.readString(PRIMERS).replace(">toxA-F size=312", ">toxA-F size=500"));
        Path output = directory.resolve("extension-results");
        PipelineConfig config = PipelineConfig.builder(EXAMPLE.resolve("reads"), output, primers).threads(2)
                .readMinFraction(0.3).readExtension(ReadExtensionCaller.Mode.TARGET).inputCheck(InputCheck.Depth.QUICK)
                .build();
        Recorder monitor = new Recorder();

        pipeline(config, monitor).run();

        Path sampleDir = output.resolve("detailed_report").resolve("isolate_01");
        assertTrue(Files.size(new SampleFiles(sampleDir, "isolate_01").extendedReads()) > 0, "no extended reads");
        assertTrue(Files.isRegularFile(new SampleFiles(sampleDir, "isolate_01").extendedReadsHits()));
        assertTrue(monitor.messages.contains("Completed read extension"), monitor.messages.toString());
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("extend")), "Tadpole did not extend");
        // The 312 bp product is still reported, as a product of another size than the one declared.
        assertTrue(products(report(output)).stream().anyMatch(row -> row.startsWith("isolate_01 toxA 312 ")),
                products(report(output)).toString());
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("  Read extension (FASTQ, --read-extension): target"));
        assertCalledAssaysAreReported(output);
    }

    /**
     * The FASTQ diagnostics and the report decide separately whether an assay was called from the
     * reads: the two must agree, or the diagnostics would say "called" for an assay with no row.
     */
    private static void assertCalledAssaysAreReported(Path output) throws IOException {
        Path diagnostics = output.resolve("consolidated_report").resolve("report_fastq_diagnostics.tsv");
        List<String> reported = products(report(output)).stream()
                .map(row -> String.join(" ", List.of(row.split(" ")).subList(0, 2))).toList();
        List<String> rows = lines(diagnostics);
        List<String> header = List.of(rows.getFirst().split("\t", -1));
        for (String row : rows.subList(1, rows.size())) {
            String[] cells = row.split("\t", -1);
            if (FastqDiagnostics.Status.valueOf(cells[header.indexOf("Status")]).isCalled()) {
                String pair = cells[header.indexOf("Sample")] + " " + cells[header.indexOf("Gene")];
                assertTrue(reported.contains(pair), pair + " is called in the diagnostics but has no row: " + reported);
            }
        }
    }

    @Test
    void aProductLongerThanTheBaitedReadsReachIsClosedInRounds() throws IOException {
        // A 2,500 bp product in 40x reads of 2 x 150 bases (fragments of 350): the baited reads run out about a
        // fragment from each primer, and the read extension, through them only, never closed it (before
        // 0.15.3, nothing over 1.5 to 2 kb). Rounds bait more reads from the ends of the extended reads.
        java.util.Random random = new java.util.Random(13);
        StringBuilder genome = new StringBuilder();
        while (genome.length() < 12_000) {
            genome.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        genome.replace(5_000, 5_020, forward);
        genome.replace(7_480, 7_500, reverseComplement(reverse)); // the product: 5,001 to 7,500
        Path input = Files.createDirectory(directory.resolve("long-reads"));
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        String quality = "I".repeat(150);
        for (int pair = 0; pair < 40 * genome.length() / 300; pair++) {
            int start = random.nextInt(genome.length() - 350);
            String fragment = genome.substring(start, start + 350);
            boolean flip = random.nextBoolean();
            String one = flip ? reverseComplement(fragment) : fragment;
            first.append("@p").append(pair).append("/1\n").append(one, 0, 150).append("\n+\n").append(quality).append('\n');
            second.append("@p").append(pair).append("/2\n").append(reverseComplement(one).substring(0, 150))
                    .append("\n+\n").append(quality).append('\n');
        }
        gzip(first.toString().getBytes(StandardCharsets.UTF_8), input.resolve("long_R1.fastq.gz"));
        gzip(second.toString().getBytes(StandardCharsets.UTF_8), input.resolve("long_R2.fastq.gz"));
        Path primers = Files.writeString(directory.resolve("long-primers.fasta"), ">long-F\n" + forward + "\n>long-R\n"
                + reverse + "\n");
        Path output = directory.resolve("long-results");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        assertEquals(List.of("long long 2500 "), products(report(output)), () -> {
            try {
                return Files.readString(output.resolve("consolidated_report").resolve("report_fastq_diagnostics.tsv"));
            } catch (IOException e) {
                return e.toString();
            }
        });
    }

    private static String reverseComplement(CharSequence bases) {
        StringBuilder complement = new StringBuilder(bases.length());
        for (int i = bases.length() - 1; i >= 0; i--) {
            complement.append(switch (bases.charAt(i)) {
                case 'A' -> 'T';
                case 'C' -> 'G';
                case 'G' -> 'C';
                default -> 'A';
            });
        }
        return complement.toString();
    }

    @Test
    void faultyFilesAreNamedAndLeftOutAndTheOthersAreRun() throws IOException {
        Path input = Files.createDirectory(directory.resolve("broken"));
        Files.copy(EXAMPLE.resolve("genomes").resolve("isolate_03.fasta"), input.resolve("isolate_03.fasta"));
        Files.writeString(input.resolve("headers.fasta"), ">only_a_header\n");
        // An aligned FASTA whose first sequence line is all gaps: the input check passes it, blastn refuses it.
        Files.writeString(input.resolve("gapped.fasta"), ">c1\n" + "-".repeat(60) + "\nACGTACGTACGTACGT\n");
        gzip(new byte[0], input.resolve("nolibrary_R1.fastq.gz"));
        gzip(new byte[0], input.resolve("nolibrary_R2.fastq.gz"));
        // A download that stopped early.
        byte[] reads = Files.readAllBytes(EXAMPLE.resolve("reads").resolve("isolate_01_R1.fastq.gz"));
        Files.write(input.resolve("cutshort_R1.fastq.gz"), Arrays.copyOf(reads, 5000));
        Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R2.fastq.gz"), input.resolve("cutshort_R2.fastq.gz"));
        Recorder monitor = new Recorder();
        Path output = directory.resolve("broken-results");

        pipeline(config(input, output), monitor).run();

        for (String expected : List.of("Warning: headers.fasta: the file holds no sequence",
                "Warning: nolibrary_R1.fastq.gz: the file holds no read",
                "Warning: nolibrary_R2.fastq.gz: the file holds no read",
                "Warning: cutshort_R1.fastq.gz: the file cannot be read (the gzip stream ends early",
                "Warning: gapped.fasta: blastn cannot read the file")) {
            assertTrue(monitor.warnings.stream().anyMatch(line -> line.startsWith(expected)),
                    "no warning starting with: " + expected + " in " + monitor.warnings);
        }
        assertEquals(List.of("isolate_03 toxA 312 target"), products(report(output)));
        assertEquals(4, lines(output.resolve("QAlog.txt")).stream()
                .filter(line -> line.startsWith("Sample not analysed: ")).count());
        assertTrue(Files.readString(output.resolve("consolidated_report").resolve("report.html"))
                .contains("gapped.fasta"), "named in the HTML report's Input not analysed");
    }

    @Test
    void readsBbtoolsCannotBeGivenLeaveTheirSampleOutAndTheOthersAreRun() throws IOException {
        // BBTools reads a path from its last '=': a read set named run=2 failed the whole run, the assembly
        // beside it too. As for an assembly blastn refuses, only its sample is left out.
        Path input = Files.createDirectory(directory.resolve("equals"));
        Files.copy(EXAMPLE.resolve("genomes").resolve("isolate_03.fasta"), input.resolve("isolate_03.fasta"));
        Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R1.fastq.gz"), input.resolve("run=2_R1.fastq.gz"));
        Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R2.fastq.gz"), input.resolve("run=2_R2.fastq.gz"));
        Recorder monitor = new Recorder();
        Path output = directory.resolve("equals-results");

        pipeline(config(input, output), monitor).run();

        assertTrue(monitor.warnings.stream().anyMatch(line -> line.startsWith("Warning: run=2_R1.fastq.gz")
                && line.contains("BBDuk cannot read the reads") && line.contains("equals sign")), monitor.warnings.toString());
        assertEquals(List.of("isolate_03 toxA 312 target"), products(report(output)));
        assertEquals(1, lines(output.resolve("QAlog.txt")).stream()
                .filter(line -> line.startsWith("Sample not analysed: ")).count());
    }

    @Test
    void whenBlastnRefusesEveryAssemblyTheRunFailsAsBefore() throws IOException {
        // Not one faulty file among good ones, which would be left out: blastn itself, its database, the disk.
        Path input = Files.createDirectory(directory.resolve("allgapped"));
        Files.writeString(input.resolve("gapped.fasta"), ">c1\n" + "-".repeat(60) + "\nACGTACGTACGTACGT\n");

        PipelineException error = assertThrows(PipelineException.class, () -> pipeline(
                config(input, directory.resolve("allgapped-results")), new Recorder()).run());

        assertEquals("BLAST failed", error.getMessage());
        assertTrue(error.getCause() instanceof ProgramFailedException, String.valueOf(error.getCause()));
    }

    @Test
    void aPrimerFileWithAnIncompleteAssayStopsTheRunBeforeTheInputIsRead() throws IOException {
        Path primers = Files.writeString(directory.resolve("primers.fasta"), ">toxA-F\nACGTACGTACGTACGTACGT\n");
        Recorder monitor = new Recorder();
        PipelineConfig config = PipelineConfig.builder(EXAMPLE.resolve("reads"), directory.resolve("results"), primers)
                .threads(2).build();

        PipelineException error = assertThrows(PipelineException.class,
                () -> pipeline(config, monitor).run());

        assertTrue(error.getMessage().contains("toxA-R is missing"), error.getMessage());
        assertEquals(List.of(Step.DEPENDENCIES), monitor.steps);
        assertTrue(monitor.messages.stream().noneMatch(line -> line.startsWith("Checking the input files")),
                monitor.messages.toString());
    }

    @Test
    void aCancelledRunStopsBeforeTheNextProgramAndWritesNoReport() {
        Recorder monitor = new Recorder() {
            @Override
            public boolean isCancelled() {
                return steps.contains(Step.INPUT_CHECK);
            }
        };
        Path output = directory.resolve("cancelled");

        // Stopped as the input check ends: no later step starts, and no report or QAlog is written.
        assertThrows(CancellationException.class,
                () -> pipeline(config(EXAMPLE.resolve("genomes"), output), monitor).run());
        assertFalse(Files.exists(output.resolve("consolidated_report").resolve("report.tsv")));
        assertFalse(Files.exists(output.resolve("QAlog.txt")));
        assertEquals(List.of(Step.DEPENDENCIES, Step.PRIMERS, Step.INPUT_CHECK), monitor.steps);
    }

    @Test
    void aRunStoppedWhileWritingItsReportsLeavesNone() {
        // Stopped once report.tsv is written: it stayed, unnamed by any QAlog, though the window says a
        // stopped run writes no report.
        Path output = directory.resolve("stopped-in-reports");
        Path report = output.resolve("consolidated_report").resolve("report.tsv");
        Recorder monitor = new Recorder() {
            @Override
            public boolean isCancelled() {
                return Files.exists(report);
            }
        };

        assertThrows(CancellationException.class,
                () -> pipeline(config(EXAMPLE.resolve("genomes"), output), monitor).run());
        assertFalse(Files.exists(report));
        assertFalse(Files.exists(output.resolve("consolidated_report").resolve("report.html")));
        assertFalse(Files.exists(output.resolve("QAlog.txt")));
    }

    /** Copies of the example reads under the given sample names. */
    private Path twoSamples(String first, String second) throws IOException {
        Path reads = Files.createDirectory(directory.resolve(first + "-" + second));
        for (String sample : List.of(first, second)) {
            for (String mate : List.of("R1", "R2")) {
                Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_" + mate + ".fastq.gz"),
                        reads.resolve(sample + "_" + mate + ".fastq.gz"));
            }
        }
        return reads;
    }

    /** How a BBTools child stops for lack of memory. */
    enum OutOfMemory {
        /** The Java runtime, with {@code -XX:+ExitOnOutOfMemoryError}: exit code 3. */
        JAVA_RUNTIME("echo 'Exception in thread \"main\" java.lang.OutOfMemoryError: Java heap space (stand-in)'"
                + " >&2; exit " + BbtoolsCommands.OUT_OF_MEMORY_EXIT),
        /** BBTools itself, when a k-mer table sized from the heap is full: its message, exit code 1. */
        BBTOOLS("echo 'java.lang.OutOfMemoryError' >&2; echo >&2; echo '" + BbtoolsCommands.OUT_OF_MEMORY_LINE
                + "' >&2; echo 'Try increasing the -Xmx flag (stand-in).' >&2; exit 1");

        final String script;

        OutOfMemory(String script) {
            this.script = script;
        }
    }

    /**
     * What the FASTQ stage needs, with a {@code java} that stands in for running out of
     * memory: Tadpole on sample {@code big} with a 4 GiB heap stops as {@code how} says.
     */
    private PipelineContext readContext(Path reads, Path output, int budgetGiB, RunMonitor monitor, OutOfMemory how)
            throws IOException {
        assumeTrue(!AppPaths.isWindows(), "the stand-in java is a shell script");
        AppPaths.RuntimeLayout real = AppPaths.discover();
        Path java = directory.resolve("java");
        Files.writeString(java, """
                #!/bin/sh
                case "$*" in
                  *-Xmx4g*assemble.Tadpole*big_doubleTargetMatches*)
                    %s ;;
                esac
                exec '%s' "$@"
                """.formatted(how.script, real.javaExecutable()));
        assertTrue(java.toFile().setExecutable(true));
        AppPaths.RuntimeLayout layout = new AppPaths.RuntimeLayout(real.appRoot(), real.bbmapDirectory(),
                real.blastBinDirectory(), java);
        PipelineConfig config = config(reads, output);
        PipelineDirectories directories = PipelineDirectories.create(output);
        PrimerPanel primers = PrimerService.loadAndPrepare(config, directories);
        return new PipelineContext(config, new RuntimeDependencies(layout), directories,
                InputCheck.scan(reads, InputCheck.Depth.QUICK, 2).samples(), primers, budgetGiB, List.of(), monitor);
    }

    /** The {@code threads=} of each run of a BBTools program, sorted; Tadpole logs itself as Tadpole1 or 2. */
    private static List<String> executed(Recorder monitor, String program) {
        return monitor.programLines.stream()
                .filter(line -> line.matches("Executing " + program.replace(".", "\\.") + "\\d? .*"))
                .map(line -> line.replaceFirst(".*\\b(threads=\\d+).*", "$1"))
                .sorted()
                .toList();
    }

    /** A run reporting to the console, its programs untracked: the front ends track theirs. */
    private static InSilicoPcrPipeline pipeline(PipelineConfig config) {
        return new InSilicoPcrPipeline(config, RunMonitor.CONSOLE);
    }

    private static InSilicoPcrPipeline pipeline(PipelineConfig config, RunMonitor monitor) {
        return new InSilicoPcrPipeline(config, monitor);
    }

    private static PipelineConfig config(Path input, Path output) {
        return PipelineConfig.builder(input, output, PRIMERS).threads(2).build();
    }

    private static Path report(Path output) {
        return output.resolve("consolidated_report").resolve("report.tsv");
    }

    /** Sample, gene, size and size check of each product; contig names depend on the assembly. */
    private static List<String> products(Path report) throws IOException {
        List<String> rows = lines(report);
        List<String> header = List.of(rows.getFirst().split("\t", -1));
        int sample = header.indexOf("Sample");
        int gene = header.indexOf("Gene");
        int size = header.indexOf("AmpliconSize");
        int check = header.indexOf("SizeCheck");
        return rows.stream().skip(1).map(row -> row.split("\t", -1))
                .map(cells -> cells[sample] + " " + cells[gene] + " " + cells[size] + " " + (check < 0 ? "" : cells[check]))
                .sorted().toList();
    }

    private static List<String> lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(line -> line.replace("\r", "")).toList();
    }

    private static void gzip(Path source, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(source);
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            in.transferTo(out);
        }
    }

    private static void gzip(byte[] content, Path target) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            out.write(content);
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** What the application's window would show. */
    private static class Recorder implements RunMonitor {
        final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        final List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        final List<String> programLines = Collections.synchronizedList(new ArrayList<>());
        final List<Step> steps = Collections.synchronizedList(new ArrayList<>());
        final List<Integer> blastProgress = Collections.synchronizedList(new ArrayList<>());
        private final ExternalProcessTracker tracker = new ExternalProcessTracker();

        @Override
        public void info(String message) {
            messages.add(message);
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
        }

        @Override
        public Consumer<String> programOutput() {
            return programLines::add;
        }

        @Override
        public ExternalProcessTracker processes() {
            return tracker;
        }

        @Override
        public void stepDone(Step step) {
            steps.add(step);
        }

        @Override
        public void blastProgress(int done, int total) {
            blastProgress.add(done);
        }
    }
}
