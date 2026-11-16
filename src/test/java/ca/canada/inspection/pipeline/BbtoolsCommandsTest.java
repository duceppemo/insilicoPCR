package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BbtoolsCommandsTest {
    /** A file argument as the commands give it without a comma: its absolute path. */
    private static String abs(String name) {
        return Path.of(name).toAbsolutePath().normalize().toString();
    }

    private final BbtoolsCommands commands = new BbtoolsCommands("java", Path.of("bbtools.jar"), 6, 4);
    private final List<Path> paired = List.of(Path.of("s_R1.fastq.gz"), Path.of("s_R2.fastq.gz"));

    @Test
    void firstBaitUsesPrimerDatabaseShortestPrimerAndMismatchBudget() {
        List<String> command = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"), 17, 2).arguments();

        assertTrue(command.contains("jgi.BBDuk"));
        assertTrue(command.contains("ref=" + abs("primers.fasta")));
        assertTrue(command.contains("k=17"));
        assertTrue(command.contains("hdist=2"));
        assertTrue(command.contains("in1=" + abs("s_R1.fastq.gz")));
        assertTrue(command.contains("in2=" + abs("s_R2.fastq.gz")));
        assertTrue(command.contains("outm=" + abs("out.fq.gz")));
        assertTrue(command.contains("overwrite=t"));
        assertTrue(command.contains("-Xmx6g"));
        assertTrue(command.contains("-XX:+ExitOnOutOfMemoryError"));
        assertTrue(command.contains("threads=4"));
        assertTrue(command.contains("ordered=t"), "reads in the order they came, every run");
    }

    @Test
    void secondBaitIsExactRegardlessOfMismatchBudget() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), paired, Path.of("second.fq.gz")).arguments();

        assertTrue(command.contains("ref=" + abs("first.fq.gz")));
        assertTrue(command.contains("hdist=0"));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("k=")));
    }

    @Test
    void singleEndReadsUseInOption() {
        List<String> command = commands.secondBait(Path.of("first.fq.gz"), List.of(Path.of("s.fastq.gz")),
                Path.of("second.fq.gz")).arguments();

        assertTrue(command.contains("in=" + abs("s.fastq.gz")));
        assertFalse(command.stream().anyMatch(argument -> argument.startsWith("in1=")));
    }

    @Test
    void assemblyRunsTadpoleWithOverwriteAndGivenSettings() {
        List<String> command = commands.assemble(Path.of("second.fq.gz"), Path.of("asm.fasta"),
                new TadpoleOptions(25, 2, 1, 80)).arguments();

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp", "bbtools.jar", "assemble.Tadpole",
                "in=" + abs("second.fq.gz"), "out=" + abs("asm.fasta"), "overwrite=t", "threads=4",
                "k=25", "mincountseed=2", "mincov=1", "mincontig=80"), command);
    }

    @Test
    void defaultTadpoleSettingsKeepShortContigs() {
        // Tadpole's own mincontig=500 drops contigs spanning short amplicons (#7).
        assertEquals(List.of("k=31", "mincountseed=2", "mincov=1", "mincontig=60"),
                TadpoleOptions.DEFAULTS.arguments());
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(0, 2, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(31, 0, 1, 60));
        assertThrows(IllegalArgumentException.class, () -> new TadpoleOptions(31, 2, 1, 0));
    }

    @Test
    void rejectsMoreThanTwoReadFiles() {
        List<Path> three = List.of(Path.of("a"), Path.of("b"), Path.of("c"));
        assertThrows(IllegalArgumentException.class,
                () -> commands.secondBait(Path.of("first.fq.gz"), three, Path.of("second.fq.gz")).arguments());
    }

    @Test
    void readsOfCandidateProductsAreFetchedFromTheInputWithTheirMates() {
        List<String> command = commands.recruit(Path.of("candidates.fasta"), paired, Path.of("reads.fq.gz"), 31).arguments();

        assertTrue(command.contains("jgi.BBDuk"));
        assertTrue(command.contains("ref=" + abs("candidates.fasta")));
        assertTrue(command.contains("in1=" + abs("s_R1.fastq.gz")));
        assertTrue(command.contains("in2=" + abs("s_R2.fastq.gz")));
        assertTrue(command.contains("outm=" + abs("reads.fq.gz")));
        assertTrue(command.contains("interleaved=t"), "mates stay together");
        assertTrue(command.contains("hdist=0"));
        assertTrue(command.contains("k=31"));
    }

    @Test
    void extensionFollowsTheBetterSupportedBranchBothWays() {
        List<String> command = commands.extend(Path.of("seeds.fasta"), Path.of("second.fq.gz"),
                Path.of("extended.fasta"), 31, 2000).arguments();

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp", "bbtools.jar", "assemble.Tadpole",
                "in=" + abs("seeds.fasta"), "out=" + abs("extended.fasta"), "mode=extend", "el=2000", "er=2000", "extra=" + abs("second.fq.gz"),
                "k=31", "branchmult1=1", "branchmult2=1", "mincountextend=2", "overwrite=t", "threads=4"), command);
    }

    @Test
    void mergeKeepsUnmergedMates() {
        List<String> command = commands.mergePairs(Path.of("first.fq.gz"), Path.of("m.fq.gz"), Path.of("u.fq.gz")).arguments();

        assertTrue(command.contains("jgi.BBMerge"));
        assertTrue(command.contains("in=" + abs("first.fq.gz")));
        assertTrue(command.contains("interleaved=t"));
        assertTrue(command.contains("out=" + abs("m.fq.gz")));
        assertTrue(command.contains("outu=" + abs("u.fq.gz")));
        assertTrue(command.contains("ordered=t"), "reads in the order they came, every run");
    }

    @Test
    void exitCodeThreeOrBbtoolsSayingSoIsRunningOutOfMemory() {
        assertTrue(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 3, false, "Tadpole failed")));
        assertTrue(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 1, true, "Tadpole failed")));
        assertFalse(BbtoolsCommands.ranOutOfMemory(new ProgramFailedException("Tadpole", 1, false, "Tadpole failed")));
        assertFalse(BbtoolsCommands.ranOutOfMemory(new PipelineException("Tadpole failed with exit code 3")));
    }

    @Test
    void runningOutOfMemoryBelowTheCapSaysToRaiseTheHeap() {
        String message = BbtoolsCommands.outOfMemoryMessage("Tadpole", " on sample s", 8);

        assertTrue(message.startsWith("Tadpole ran out of memory on sample s with -Xmx8g, all it had. Give the BBTools"
                + " programs more with --bbtools-memory <GB>"), message);
        assertTrue(message.endsWith("use fewer threads (-t) instead."), message);
    }

    @Test
    void runningOutOfMemoryAtTheCapDoesNotSayToRaiseTheHeap() {
        String message = BbtoolsCommands.outOfMemoryMessage("BBDuk", "", 64);

        assertTrue(message.startsWith("BBDuk ran out of memory with -Xmx64g, the most insilicoPCR gives a BBTools"
                + " program."), message);
        assertFalse(message.contains("-Xmx option"), message);
    }

    @Test
    void singleEndReadsAreNotTakenForInterleavedPairs() {
        // With interleaved=t, BBDuk took consecutive reads for mates: an odd count stopped it,
        // and each matching read brought its unrelated neighbour along.
        List<String> single = commands.firstBait(Path.of("primers.fasta"), List.of(Path.of("s.fastq.gz")),
                Path.of("out.fq.gz"), 18, 0).arguments();
        assertTrue(single.contains("in=" + abs("s.fastq.gz")));
        assertTrue(single.contains("interleaved=f"), single.toString());
        assertTrue(!single.contains("interleaved=t"), single.toString());

        List<String> pairs = commands.firstBait(Path.of("primers.fasta"), paired, Path.of("out.fq.gz"), 18, 0).arguments();
        assertTrue(pairs.contains("interleaved=t"), "two files: mates stay together");
    }

    @Test
    void filesAreNamedFromTheOutputFolderSoACommaAboveItIsLeftOut(@TempDir Path dir) {
        // BBTools splits a file argument at its commas: the folders the files share are left out.
        Path run = dir.resolve("results, run 2");
        Path sampleDir = run.resolve("detailed_report").resolve("s");
        BbtoolsCommands.Command command = commands.firstBait(run.resolve("primer_tmp.fasta"),
                List.of(run.resolve("reads").resolve("s.fastq.gz")), sampleDir.resolve("s_targetMatches.fastq.gz"), 17, 0);

        assertEquals(sampleDir.toAbsolutePath(), command.directory());
        assertTrue(command.arguments().contains("ref=" + Path.of("..", "..", "primer_tmp.fasta")), command::toString);
        assertTrue(command.arguments().contains("in=" + Path.of("..", "..", "reads", "s.fastq.gz")), command::toString);
        assertTrue(command.arguments().contains("outm=s_targetMatches.fastq.gz"), command::toString);
        assertTrue(command.arguments().contains("-cp") && command.arguments().contains("bbtools.jar"));
    }

    @Test
    void aCommaLeftInAFileArgumentIsAClearError(@TempDir Path dir) {
        Path reads = dir.resolve("reads, lane 1").resolve("s.fastq.gz");
        PipelineException error = assertThrows(PipelineException.class,
                () -> commands.secondBait(dir.resolve("out").resolve("first.fq.gz"), List.of(reads),
                        dir.resolve("out").resolve("second.fq.gz")));
        assertEquals("BBTools reads a comma in a file name as a list of files, and cannot read "
                + reads.toAbsolutePath().normalize() + ": remove the comma from the name of the file or of its folder",
                error.getMessage());
    }

    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aCommaInAFolderReachedThroughALinkIsNamedFromTheRealFolders(@TempDir Path dir) throws java.io.IOException {
        // /scratch as a link, the reads elsewhere: ".." is resolved from the real folder a program
        // runs in, not from the link's path.
        Path real = java.nio.file.Files.createDirectories(dir.resolve("real").resolve("deep"));
        Path link = java.nio.file.Files.createSymbolicLink(dir.resolve("link, 1"), real);
        Path sampleDir = java.nio.file.Files.createDirectories(link.resolve("out").resolve("s"));
        Path reads = java.nio.file.Files.writeString(java.nio.file.Files.createDirectories(link.resolve("reads"))
                .resolve("s.fastq"), "");

        BbtoolsCommands.Command command = commands.secondBait(sampleDir.resolve("first.fq.gz"), List.of(reads),
                sampleDir.resolve("second.fq.gz"));

        String in = command.arguments().stream().filter(argument -> argument.startsWith("in=")).findFirst()
                .orElseThrow().substring(3);
        assertEquals(Path.of("..", "..", "reads", "s.fastq").toString(), in, "from the real folder, not the link");
        String ref = command.arguments().stream().filter(argument -> argument.startsWith("ref=")).findFirst()
                .orElseThrow().substring(4);
        assertEquals("first.fq.gz", ref);
        assertTrue(java.nio.file.Files.isSameFile(sampleDir.toRealPath().resolve(in), reads));
    }

    @Test
    void aJarAtAPathWithAccentsIsStartedThroughTheLauncher() {
        // BBTools looked for its resources in "Donn??es" and stopped: no FASTQ sample could be run.
        Path jar = Path.of("Données Rémi", "bbmap", "bbtools.jar");
        List<String> command = new BbtoolsCommands("java", jar, 6, 4)
                .assemble(Path.of("in.fq.gz"), Path.of("out.fasta"), TadpoleOptions.DEFAULTS).arguments();

        assertEquals(List.of("java", "-ea", "-Xmx6g", "-XX:+ExitOnOutOfMemoryError", "-cp",
                BbtoolsCommands.launcherClassPath(), BbtoolsLauncher.class.getName(),
                jar.toAbsolutePath().normalize().toString(), "assemble.Tadpole"), command.subList(0, 9));
        assertTrue(BbtoolsLauncher.needed(jar));
        assertFalse(BbtoolsLauncher.needed(Path.of("C:", "My Apps (x86)", "bbtools.jar")));
    }

    @Test
    void theLauncherGivesBbtoolsTheJarsPathAsItIs() throws java.net.MalformedURLException {
        Path jar = Path.of("Données Rémi", "bbtools.jar").toAbsolutePath().normalize();
        String url = BbtoolsLauncher.asIs(jar).toString();

        assertTrue(url.startsWith("file:/"), url);
        assertFalse(url.contains("%"), url);
        assertTrue(url.endsWith("/Données Rémi/bbtools.jar"), url);
    }

    @Test
    void aJarAtAPathWithAccentsAndAHashOrAPercentIsRefusedSayingWhy() {
        // "D:\Données\C# tools": # ends a URL's path, and encoded, it makes BBTools garble the é.
        for (String folder : List.of("Données C# tools", "Données 100%")) {
            Path jar = Path.of(folder, "bbtools.jar");
            PipelineException error = assertThrows(PipelineException.class, () -> new BbtoolsCommands("java", jar, 6, 4)
                    .assemble(Path.of("in.fq.gz"), Path.of("out.fasta"), TadpoleOptions.DEFAULTS));
            assertTrue(error.getMessage().contains("a letter outside ASCII and a # or a %"), error.getMessage());
        }
        // Without an accent, BBTools takes the jar from -cp: a # or a % is no trouble.
        assertDoesNotThrow(() -> new BbtoolsCommands("java", Path.of("C# tools 100%", "bbtools.jar"), 6, 4)
                .assemble(Path.of("in.fq.gz"), Path.of("out.fasta"), TadpoleOptions.DEFAULTS));
    }

    @Test
    @org.junit.jupiter.api.Tag("end-to-end")
    void bbdukRunsFromAnInstallationWhosePathHasAccents(@TempDir Path dir) throws java.io.IOException {
        Path bbmap = AppPaths.applicationRoot().resolve("runtime").resolve("common").resolve("bbmap");
        Path jar = bbmap.resolve("bbtools.jar");
        Path primes = bbmap.resolve("resources").resolve("primes.txt.gz");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(jar)
                && java.nio.file.Files.size(jar) > 100_000 && java.nio.file.Files.size(primes) > 1_000,
                "BBTools is not in runtime/ (Git LFS)");
        Path installed = java.nio.file.Files.createDirectories(dir.resolve("Données Rémi").resolve("bbmap"));
        java.nio.file.Files.copy(jar, installed.resolve("bbtools.jar"));
        java.nio.file.Files.copy(primes, java.nio.file.Files.createDirectories(installed.resolve("resources"))
                .resolve("primes.txt.gz"));
        Path example = Path.of("docs", "examples", "five-isolates").toAbsolutePath();
        Path out = java.nio.file.Files.createDirectories(dir.resolve("out")).resolve("baited.fastq.gz");
        String javaCommand = ProcessHandle.current().info().command().orElse("java");
        List<String> lines = new java.util.ArrayList<>();
        RunMonitor monitor = new RunMonitor() {
            @Override
            public java.util.function.Consumer<String> programOutput() {
                return lines::add;
            }
        };

        new BbtoolsCommands(javaCommand, installed.resolve("bbtools.jar"), 1, 1)
                .firstBait(example.resolve("primers.fasta"), List.of(example.resolve("reads").resolve("isolate_01_R1.fastq.gz"),
                        example.resolve("reads").resolve("isolate_01_R2.fastq.gz")), out, 20, 0)
                .run(monitor, "BBDuk");

        assertFalse(lines.stream().anyMatch(line -> line.contains("Cannot find")), String.join("\n", lines));
        assertTrue(java.nio.file.Files.size(out) > 1_000, String.join("\n", lines));
    }
}
