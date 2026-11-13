package ca.canada.inspection.pipeline;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pipeline.RunMonitor.Step;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
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

        new InSilicoPcrPipeline(config(genomes, output)).run();

        assertEquals(lines(EXAMPLE.resolve("report.tsv")), lines(report(output)));
        assertTrue(Files.size(output.resolve("consolidated_report").resolve("report.html")) > 0);
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("isolate_05.fasta"));
        try (Stream<Path> files = Files.walk(output)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().startsWith("blast_query_"))
                    .toList(), "decompressed BLAST queries left in the results");
        }
    }

    @Test
    void readsGiveTheProductsOfTheirIsolateAndTheWindowGetsEveryLine() throws IOException {
        Recorder monitor = new Recorder();
        Path output = directory.resolve("reads-results");

        new InSilicoPcrPipeline(config(EXAMPLE.resolve("reads"), output), monitor).run();

        assertEquals(List.of("isolate_01 stxQ 118 target", "isolate_01 toxA 312 target"), products(report(output)));
        // As the application shows them: no program wrote to the console, every step was reported.
        assertEquals(Arrays.asList(Step.values()), monitor.steps);
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("BBDuk")), "no BBDuk output");
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("Tadpole")), "no Tadpole output");
        assertTrue(monitor.messages.contains("Completed read-level amplicon search"), monitor.messages.toString());
        assertTrue(monitor.messages.stream().anyMatch(line -> line.startsWith("Done in ")));
        assertFalse(monitor.blastProgress.isEmpty());
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("FASTQ mode Tadpole settings"));
    }

    @Test
    void readsAreExtendedForAnAssayWithoutAProductOfItsDeclaredSize() throws IOException {
        // toxA gives 312 bp; declaring 500 bp makes the target mode extend its reads.
        Path primers = Files.writeString(directory.resolve("primers.fasta"),
                Files.readString(PRIMERS).replace(">toxA-F size=312", ">toxA-F size=500"));
        Path output = directory.resolve("extension-results");
        PipelineConfig config = new PipelineConfig(EXAMPLE.resolve("reads"), output, primers, 2, 1e5,
                AmpliconOptions.withMismatches(0), false, TadpoleOptions.DEFAULTS, 0.3,
                ReadExtensionCaller.Mode.TARGET, InputCheck.Depth.QUICK);
        Recorder monitor = new Recorder();

        new InSilicoPcrPipeline(config, monitor).run();

        Path sampleDir = output.resolve("detailed_report").resolve("isolate_01");
        assertTrue(Files.size(BbtoolsCommands.extendedReads(sampleDir, "isolate_01")) > 0, "no extended reads");
        assertTrue(Files.isRegularFile(BbtoolsCommands.extendedReadsHits(sampleDir, "isolate_01")));
        assertTrue(monitor.messages.contains("Completed read extension"), monitor.messages.toString());
        assertTrue(monitor.programLines.stream().anyMatch(line -> line.contains("extend")), "Tadpole did not extend");
        // The 312 bp product is still reported, as a product of another size than the one declared.
        assertTrue(products(report(output)).stream().anyMatch(row -> row.startsWith("isolate_01 toxA 312 ")),
                products(report(output)).toString());
        assertTrue(Files.readString(output.resolve("QAlog.txt")).contains("read extension"));
    }

    @Test
    void faultyFilesAreNamedAndLeftOutAndTheOthersAreRun() throws IOException {
        Path input = Files.createDirectory(directory.resolve("broken"));
        Files.copy(EXAMPLE.resolve("genomes").resolve("isolate_03.fasta"), input.resolve("isolate_03.fasta"));
        Files.writeString(input.resolve("headers.fasta"), ">only_a_header\n");
        gzip(new byte[0], input.resolve("nolibrary_R1.fastq.gz"));
        gzip(new byte[0], input.resolve("nolibrary_R2.fastq.gz"));
        // A download that stopped early.
        byte[] reads = Files.readAllBytes(EXAMPLE.resolve("reads").resolve("isolate_01_R1.fastq.gz"));
        Files.write(input.resolve("cutshort_R1.fastq.gz"), Arrays.copyOf(reads, 5000));
        Files.copy(EXAMPLE.resolve("reads").resolve("isolate_01_R2.fastq.gz"), input.resolve("cutshort_R2.fastq.gz"));
        Recorder monitor = new Recorder();
        Path output = directory.resolve("broken-results");

        new InSilicoPcrPipeline(config(input, output), monitor).run();

        for (String expected : List.of("Warning: headers.fasta: the file holds no sequence",
                "Warning: nolibrary_R1.fastq.gz: the file holds no read",
                "Warning: nolibrary_R2.fastq.gz: the file holds no read",
                "Warning: cutshort_R1.fastq.gz: the file cannot be read (the gzip stream ends early")) {
            assertTrue(monitor.warnings.stream().anyMatch(line -> line.startsWith(expected)),
                    "no warning starting with: " + expected + " in " + monitor.warnings);
        }
        assertEquals(List.of("isolate_03 toxA 312 target"), products(report(output)));
        assertEquals(3, lines(output.resolve("QAlog.txt")).stream()
                .filter(line -> line.startsWith("Sample not analysed: ")).count());
    }

    @Test
    void aCancelledRunStopsBeforeTheNextProgramAndWritesNoReport() {
        Recorder monitor = new Recorder() {
            @Override
            public boolean isCancelled() {
                return steps.contains(Step.PRIMERS);
            }
        };
        Path output = directory.resolve("cancelled");

        try {
            new InSilicoPcrPipeline(config(EXAMPLE.resolve("genomes"), output), monitor).run();
            fail("the run went on");
        } catch (CancellationException expected) {
            // makeblastdb, the first program of an assembly run, was not started
        } catch (StageException expected) {
            assertTrue(expected.getCause() instanceof CancellationException, expected.toString());
        }
        assertFalse(Files.exists(output.resolve("consolidated_report").resolve("report.tsv")));
        assertEquals(List.of(Step.DEPENDENCIES, Step.INPUT_CHECK, Step.PRIMERS, Step.READS), monitor.steps);
    }

    private static PipelineConfig config(Path input, Path output) {
        return new PipelineConfig(input, output, PRIMERS, 2, 1e5, AmpliconOptions.withMismatches(0), false);
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
                .map(cells -> cells[sample] + " " + cells[gene] + " " + cells[size] + " " + cells[check])
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
