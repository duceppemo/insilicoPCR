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
        assertTrue(Files.readString(output.resolve("QAlog.txt")).matches("(?s).*\\n  Tools: BLAST\\+ 2\\.\\d+\\.\\d+, BBTools \\d+\\.\\d+, Java \\d.*"),
                "the programs' versions in QAlog.txt");
        assertFalse(Files.readString(output.resolve("QAlog.txt")).contains("Primers at contig ends"));
        // LF on every system (on Windows, report.tsv and QAlog.txt ended their lines in CRLF, the other files in LF).
        for (Path written : List.of(report(output), output.resolve("QAlog.txt"),
                output.resolve("detailed_report").resolve("isolate_01").resolve("isolate_01.tsv"))) {
            assertFalse(Files.readString(written).contains("\r"), written + ": LF line ends");
        }
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
        // Run again from the assembly on: no baiting twice (two baitings and the anchors' trimming, a sample).
        assertEquals(6, executed(monitor, "jgi.BBDuk").size());
        // The assembly: its k-mers loaded on the sample's threads, its contigs built on one (buildthreads=1).
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
                String pair = cells[header.indexOf("Sample")] + " " + cells[header.indexOf("Assay")];
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
        simulatePairs(genome, 40, random, input, "long");
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

    /**
     * Error-free pairs of 2 x 150 bases from fragments of 350 of {@code genome}, at {@code coverage}, on both
     * strands, as {@code name_R1.fastq.gz} and {@code name_R2.fastq.gz} in {@code folder}.
     */
    private static void simulatePairs(CharSequence genome, int coverage, java.util.Random random, Path folder,
                                      String name) throws IOException {
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();
        String quality = "I".repeat(150);
        for (int pair = 0; pair < coverage * genome.length() / 300; pair++) {
            int start = random.nextInt(genome.length() - 350);
            String fragment = genome.subSequence(start, start + 350).toString();
            String one = random.nextBoolean() ? reverseComplement(fragment) : fragment;
            first.append("@p").append(pair).append("/1\n").append(one, 0, 150).append("\n+\n").append(quality).append('\n');
            second.append("@p").append(pair).append("/2\n").append(reverseComplement(one).substring(0, 150))
                    .append("\n+\n").append(quality).append('\n');
        }
        gzip(first.toString().getBytes(StandardCharsets.UTF_8), folder.resolve(name + "_R1.fastq.gz"));
        gzip(second.toString().getBytes(StandardCharsets.UTF_8), folder.resolve(name + "_R2.fastq.gz"));
    }

    @Test
    void bothAllelesOfALocusOfTwoProductSizesAreCalledFromReads() throws IOException {
        // Two alleles, 3,000 and 2,900 bp (a deletion of 100 inside the product), 30x each: at the branch, the reads
        // extended from the forward primer took one allele and those from the reverse primer the other, and with 2
        // needed from each side neither allele was called, a plain negative (before 0.15.3).
        java.util.Random random = new java.util.Random(23);
        StringBuilder genome = new StringBuilder();
        while (genome.length() < 9_000) {
            genome.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        genome.replace(3_000, 3_020, forward);
        genome.replace(5_980, 6_000, reverseComplement(reverse)); // 3,001 to 6,000
        StringBuilder other = new StringBuilder(genome).delete(4_400, 4_500);
        Path input = Files.createDirectory(directory.resolve("allele-reads"));
        simulatePairs(genome, 30, random, input, "a");
        simulatePairs(other, 30, random, input, "b");
        // One sample of the two: its files joined.
        for (String mate : List.of("_R1.fastq.gz", "_R2.fastq.gz")) {
            byte[] both = concat(Files.readAllBytes(input.resolve("a" + mate)), Files.readAllBytes(input.resolve("b" + mate)));
            Files.write(input.resolve("het" + mate), both);
            Files.delete(input.resolve("a" + mate));
            Files.delete(input.resolve("b" + mate));
        }
        Path primers = Files.writeString(directory.resolve("allele-primers.fasta"), ">het-F\n" + forward + "\n>het-R\n"
                + reverse + "\n");
        Path output = directory.resolve("allele-results");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        assertEquals(List.of("het het 2900 ", "het het 3000 "), products(report(output)), () -> {
            try {
                return Files.readString(output.resolve("consolidated_report").resolve("report_fastq_diagnostics.tsv"));
            } catch (IOException e) {
                return e.toString();
            }
        });
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] both = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, both, first.length, second.length);
        return both;
    }

    /** A 14 kb genome with a 2,500 bp product at 5,001-7,500, its primers {@code forward} and {@code reverse}. */
    private static StringBuilder genomeWithLongProduct(java.util.Random random, String forward, String reverse) {
        StringBuilder genome = new StringBuilder();
        while (genome.length() < 14_000) {
            genome.append("ACGT".charAt(random.nextInt(4)));
        }
        genome.replace(5_000, 5_020, forward);
        genome.replace(7_480, 7_500, reverseComplement(reverse));
        return genome;
    }

    private void assertLongProductCalled(String name, CharSequence genome, String forward, String reverse,
                                         java.util.Random random) throws IOException {
        Path input = Files.createDirectory(directory.resolve(name + "-reads"));
        simulatePairs(genome, 40, random, input, name);
        Path primers = Files.writeString(directory.resolve(name + "-primers.fasta"), ">long-F\n" + forward
                + "\n>long-R\n" + reverse + "\n");
        Path output = directory.resolve(name + "-results");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        assertEquals(List.of(name + " long 2500 "), products(report(output)), () -> {
            try {
                return Files.readString(output.resolve("consolidated_report").resolve("report_fastq_diagnostics.tsv"));
            } catch (IOException e) {
                return e.toString();
            }
        });
    }

    /** The cells of {@code columns} of each row of {@code report}, joined by blanks, in the report's order. */
    private static List<String> cells(Path report, String... columns) throws IOException {
        List<String> rows = lines(report);
        List<String> header = List.of(rows.getFirst().split("\t", -1));
        return rows.stream().skip(1).map(row -> row.split("\t", -1)).map(row -> java.util.Arrays.stream(columns)
                .map(column -> row[header.indexOf(column)]).collect(java.util.stream.Collectors.joining(" "))).toList();
    }

    @Test
    void aProductAcrossTheOriginOfACircularSequenceIsReported() throws IOException {
        // A 3,000-bp plasmid marked circular=true: forward at 2,901-2,920, reverse at 81-100, a probe across the
        // origin (2,991-3,000 then 1-10). Written from its origin, the product was in two pieces and not reported;
        // the same sequence unmarked is linear, and has none.
        java.util.Random random = new java.util.Random(41);
        StringBuilder plasmid = new StringBuilder();
        while (plasmid.length() < 3_000) {
            plasmid.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        String probe = "TGGCAATCCGATTACGGACT";
        plasmid.replace(2_900, 2_920, forward);
        plasmid.replace(80, 100, reverseComplement(reverse));
        plasmid.replace(2_990, 3_000, probe.substring(0, 10));
        plasmid.replace(0, 10, probe.substring(10));
        Path input = Files.createDirectory(directory.resolve("circle-in"));
        Files.writeString(input.resolve("plasmid.fasta"), ">pA length=3000 circular=true\n" + plasmid + "\n");
        Files.writeString(input.resolve("linear.fasta"), ">pA length=3000\n" + plasmid + "\n");
        Path primers = Files.writeString(directory.resolve("circle-primers.fasta"), ">circ-F\n" + forward
                + "\n>circ-R\n" + reverse + "\n>circ-P\n" + probe + "\n");
        Path output = directory.resolve("circle-out");

        // At a strict e-value: the probe's site, cut in two halves of 10 bases by the origin, is found whole (on the
        // circle's junction) or not at all. At the default one, its halves are found too, the bases one cuts off
        // compared with the circle's other end.
        pipeline(PipelineConfig.builder(input, output, primers).threads(2).evalue(1e-3).build()).run();

        assertEquals(List.of("plasmid circ 2901-100 200 circ-P 2991-10 20"), cells(report(output), "Sample", "Assay",
                "GenomeLocation", "AmpliconSize", "Probe", "ProbeLocation", "ProbeSize"));
    }

    @Test
    void aProbeWhoseTrimmedEndLiesBeforeTheOriginStartsAtTheCirclesEnd() throws IOException {
        // A 26-base probe at 2,998-3,000 then 1-23, a mismatch at its 3rd base: BLAST aligns its bases 4 to 26, and
        // the 3 before them, past the origin backwards, gave ProbeLocation -2-23.
        java.util.Random random = new java.util.Random(47);
        StringBuilder plasmid = new StringBuilder();
        while (plasmid.length() < 3_000) {
            plasmid.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        String probe = "TGGCAATCCGATTACGGACTAGCTTC";
        plasmid.replace(2_900, 2_920, forward);
        plasmid.replace(80, 100, reverseComplement(reverse));
        String site = probe.substring(0, 2) + (probe.charAt(2) == 'G' ? 'C' : 'G') + probe.substring(3);
        plasmid.replace(2_997, 3_000, site.substring(0, 3));
        plasmid.replace(0, 23, site.substring(3));
        Path input = Files.createDirectory(directory.resolve("probe-origin-in"));
        Files.writeString(input.resolve("plasmid.fasta"), ">pX circular=true\n" + plasmid + "\n");
        Path primers = Files.writeString(directory.resolve("probe-origin-primers.fasta"), ">a-F\n" + forward
                + "\n>a-R\n" + reverse + "\n>a-P\n" + probe + "\n");
        Path output = directory.resolve("probe-origin-out");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).ampliconOptions(
                ca.canada.inspection.pcr.AmpliconOptions.builder().mismatches(1).build()).build()).run();

        assertEquals(List.of("plasmid a 2901-100 200 a-P 2998-23 26"), cells(report(output), "Sample", "Assay",
                "GenomeLocation", "AmpliconSize", "Probe", "ProbeLocation", "ProbeSize"));
    }

    @Test
    void aFolderWithNoReportNumberLeftIsRefusedBeforeTheRunsWork() throws IOException {
        // report(1000000).tsv would not be counted, and every run after it failed, after its BLAST work.
        Path output = directory.resolve("full-out");
        Files.createDirectories(output.resolve("consolidated_report"));
        Files.writeString(output.resolve("consolidated_report").resolve("report(999999).tsv"), "");
        Recorder monitor = new Recorder();

        PipelineException error = assertThrows(PipelineException.class,
                () -> pipeline(config(EXAMPLE.resolve("genomes"), output), monitor).run());

        assertTrue(error.getMessage().contains("no report number is left"), error.getMessage());
        assertFalse(Files.exists(output.resolve("primer_tmp.fasta")), "refused before the primers were prepared");
    }

    @Test
    void anAssemblyBrokenInsideAPrimerSiteIsNamedAsOneThatMaySpanTheBreak() throws IOException {
        // The forward primer at 1,001-1,020, the assembly breaking after 1,018: its last 2 bases are on the next
        // contig. Rejected (2 mismatches at its 3' end), the hit was never seen by the split check, and the
        // assay was negative without a word.
        java.util.Random random = new java.util.Random(59);
        StringBuilder genome = new StringBuilder();
        while (genome.length() < 3_000) {
            genome.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        genome.replace(1_000, 1_020, forward);
        genome.replace(1_480, 1_500, reverseComplement(reverse));
        Path input = Files.createDirectory(directory.resolve("cut-in"));
        Files.writeString(input.resolve("broken.fasta"), ">c1\n" + genome.substring(0, 1_018) + "\n>c2\n"
                + genome.substring(1_018) + "\n");
        Path primers = Files.writeString(directory.resolve("cut-primers.fasta"), ">cut-F\n" + forward + "\n>cut-R\n"
                + reverse + "\n");
        Path output = directory.resolve("cut-out");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        assertEquals(List.of(), products(report(output)));
        String qaLog = Files.readString(output.resolve("QAlog.txt"));
        assertTrue(qaLog.contains("Primers at contig ends") && qaLog.contains("assay cut")
                && qaLog.contains("its 3' end past the contig's end"), qaLog);
    }

    @Test
    void theMiniAssemblyGivesTheSameContigsOnOneThreadAsOnSixteen() throws IOException {
        // Its k-mers loaded on every thread, its contigs built on one: built on several, the contigs differed between
        // runs of the same reads (a circle written from another point, contigs numbered otherwise).
        java.util.Random random = new java.util.Random(53);
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        Path input = Files.createDirectory(directory.resolve("threads-reads"));
        StringBuilder all = new StringBuilder();
        for (int length : new int[]{1_200, 1_800, 2_500}) {
            StringBuilder circle = new StringBuilder();
            while (circle.length() < length) {
                circle.append("ACGT".charAt(random.nextInt(4)));
            }
            circle.replace(100, 120, forward);
            circle.replace(500, 520, reverseComplement(reverse));
            all.append(circle).append(circle, 0, 400).append("N".repeat(400));
        }
        simulatePairs(all, 40, random, input, "mix");
        Path primers = Files.writeString(directory.resolve("threads-primers.fasta"), ">c-F\n" + forward + "\n>c-R\n"
                + reverse + "\n");
        List<byte[]> assemblies = new java.util.ArrayList<>();
        for (int threads : new int[]{1, 16}) {
            Path output = directory.resolve("threads-" + threads);
            pipeline(PipelineConfig.builder(input, output, primers).threads(threads).build()).run();
            assemblies.add(Files.readAllBytes(output.resolve("detailed_report").resolve("mix").resolve("mix_assembly.fasta")));
        }

        assertTrue(assemblies.getFirst().length > 1_000, "contigs were made");
        assertTrue(java.util.Arrays.equals(assemblies.get(0), assemblies.get(1)), "the same contigs, byte for byte");
    }

    @Test
    void theMiniAssemblysCircleIsSearchedAcrossItsOrigin() throws IOException {
        // Reads of a 1,500-bp circle (baited whole), its 1,300-bp product across the origin (forward at 1,101, reverse
        // ending at 900): the assembly writes the circle from a point of its own, within the product here, with its
        // first 26 bases again at its end (1,526 bases, not called a self-loop), and the product was in pieces on it,
        // found on extended reads only (and whole or not, from run to run, on several threads).
        java.util.Random random = new java.util.Random(51);
        StringBuilder circle = new StringBuilder();
        while (circle.length() < 1_500) {
            circle.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "TGCAGTCCATGAGCTACGGA"; // not an adapter: the anchors are trimmed of those
        String reverse = "CTTGCCAGTAGCGTCATACG";
        circle.replace(1_100, 1_120, forward);
        circle.replace(880, 900, reverseComplement(reverse));
        Path input = Files.createDirectory(directory.resolve("loop-reads"));
        simulatePairs(circle + circle.substring(0, 400), 40, random, input, "loop"); // fragments across the origin
        Path primers = Files.writeString(directory.resolve("loop-primers.fasta"), ">mt-F\n" + forward + "\n>mt-R\n"
                + reverse + "\n");
        Path output = directory.resolve("loop-results");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        List<String> rows = cells(report(output), "Sample", "Assay", "AmpliconSize", "Contig", "GenomeLocation");
        assertEquals(1, rows.size(), rows::toString);
        assertTrue(rows.getFirst().startsWith("loop mt 1300 contig_"), rows::toString);
        // Round the contig's origin: it starts after it ends.
        String[] location = rows.getFirst().substring(rows.getFirst().lastIndexOf(' ') + 1).split("-");
        assertTrue(Integer.parseInt(location[0]) > Integer.parseInt(location[1]), rows::toString);
    }

    @Test
    void aTandemRepeatOutsideTheProductDoesNotStopTheExtension() throws IOException {
        // (AG)100 200 to 400 bases before the forward primer: the forward seeds' reads, extended both ways, loop in
        // it on the side away from the product. Their outer ends were taken for the product's: the assay left the
        // rounds at once, inconclusive, and the 2,500 bp product was not found.
        java.util.Random random = new java.util.Random(29);
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        StringBuilder genome = genomeWithLongProduct(random, forward, reverse);
        genome.replace(4_600, 4_800, "AG".repeat(100));
        assertLongProductCalled("outrep", genome, forward, reverse, random);
    }

    @Test
    void aWrongTurnClosedFromOneSideDoesNotStopTheSearchForTheProduct() throws IOException {
        // Elsewhere, a copy of the 200 bases after the forward primer, followed by the reverse primer's site: reads
        // extended from the reverse primer there run into the copy and close a short product that does not exist.
        // It settled the assay at once, the read pairs then rejecting it, and the real 2,500 bp was not looked for.
        java.util.Random random = new java.util.Random(31);
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        StringBuilder genome = genomeWithLongProduct(random, forward, reverse);
        String copy = genome.substring(5_020, 5_220);
        genome.replace(11_000, 11_220, copy + reverseComplement(reverse));
        assertLongProductCalled("wrongturn", genome, forward, reverse, random);
    }

    @Test
    void readsExtendedIntoATandemRepeatAreInconclusiveNotNegative() throws IOException {
        // A 600 bp product with (CAG)30 in its middle: the extension, which follows the better-supported branch,
        // loops in the repeat to the end of its reach, and the assay was a plain negative, NOT_CLOSED_BY_EXTENSION.
        // Beside it, a 2,500 bp product that takes several rounds: the repeat's assay, out of the rounds at once,
        // must keep its outcome while the other goes on (it lost it, and read as NOT_IN_READS).
        java.util.Random random = new java.util.Random(17);
        StringBuilder genome = new StringBuilder();
        while (genome.length() < 14_000) {
            genome.append("ACGT".charAt(random.nextInt(4)));
        }
        String forward = "GATCGGAAGAGCACACGTCT";
        String reverse = "CTTGCCAGTAGCGTCATACG";
        genome.replace(3_000, 3_020, forward);
        genome.replace(3_255, 3_345, "CAG".repeat(30));
        genome.replace(3_580, 3_600, reverseComplement(reverse)); // the product: 3,001 to 3,600
        String longForward = "TGACCTAGGCAATCGGTTCA";
        String longReverse = "AGGTCATTGCCGATAGCTTC";
        genome.replace(7_000, 7_020, longForward);
        genome.replace(9_480, 9_500, reverseComplement(longReverse)); // 7,001 to 9,500
        Path input = Files.createDirectory(directory.resolve("repeat-reads"));
        simulatePairs(genome, 40, random, input, "rep");
        Path primers = Files.writeString(directory.resolve("repeat-primers.fasta"), ">rep-F\n" + forward + "\n>rep-R\n"
                + reverse + "\n>long-F\n" + longForward + "\n>long-R\n" + longReverse + "\n");
        Path output = directory.resolve("repeat-results");

        pipeline(PipelineConfig.builder(input, output, primers).threads(2).build()).run();

        List<String> diagnostics = lines(output.resolve("consolidated_report").resolve("report_fastq_diagnostics.tsv"));
        assertTrue(diagnostics.stream().anyMatch(row -> row.startsWith("rep\trep\tEXTENSION_IN_TANDEM_REPEAT\t")
                && row.contains("tandem repeat of a 3-base unit")), diagnostics.toString());
        assertEquals(List.of("rep long 2500 "), products(report(output)));
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
                && line.contains("BBTools cannot be given the file") && line.contains("equals sign")), monitor.warnings.toString());
        assertEquals(List.of("isolate_03 toxA 312 target"), products(report(output)));
        assertEquals(1, lines(output.resolve("QAlog.txt")).stream()
                .filter(line -> line.startsWith("Sample not analysed: ")).count());
    }

    @Test
    void readSetsLeftOutForTheirNamesAreTheFilesFaultAndTheAssembliesBesideThemAreRun() throws IOException {
        // Two read sets BBTools cannot be given for their names (a comma, an equals sign) were counted as BBDuk failing
        // on every read set, a fault of the program's: the run failed, the assembly beside them with it.
        Path input = Files.createDirectory(directory.resolve("all-refused"));
        Files.copy(EXAMPLE.resolve("genomes").resolve("isolate_03.fasta"), input.resolve("isolate_03.fasta"));
        for (String name : List.of("a=1", "b,2")) {
            Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R1.fastq.gz"), input.resolve(name + "_R1.fastq.gz"));
            Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R2.fastq.gz"), input.resolve(name + "_R2.fastq.gz"));
        }
        Recorder monitor = new Recorder();
        Path output = directory.resolve("all-refused-results");

        pipeline(config(input, output), monitor).run();

        assertEquals(List.of("isolate_03 toxA 312 target"), products(report(output)));
        assertEquals(2, monitor.warnings.stream().filter(line -> line.contains("BBTools cannot be given the file"))
                .count(), monitor.warnings.toString());
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
        // Refused with the paths' checks, before any step, and before the output folder is made (since 0.15.4).
        assertEquals(List.of(), monitor.steps);
        assertTrue(monitor.messages.stream().noneMatch(line -> line.startsWith("Checking the input files")),
                monitor.messages.toString());
        assertFalse(Files.exists(directory.resolve("results")), "no output folder left behind");
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
        PipelineContext context = new PipelineContext(config, new RuntimeDependencies(layout), directories,
                InputCheck.scan(reads, InputCheck.Depth.QUICK, 2).samples(), primers, budgetGiB, List.of(), monitor);
        // The baiting searches its first catch for the primers: their database comes first, as in a run.
        BlastDatabaseBuilder.build(context);
        return context;
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
        int gene = header.indexOf("Assay");
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
