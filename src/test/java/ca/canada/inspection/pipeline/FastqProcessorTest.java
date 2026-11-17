package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastqProcessorTest {

    @Test
    void onlyTheFirstBaitStoppingOnTheReadsLeavesTheSampleOut() {
        // BBDuk stopping on a sample's reads (exit 1) failed the run, every other sample with it.
        ProgramFailedException refused = new ProgramFailedException("BBDuk", 1, false, "BBDuk failed with exit code 1");
        assertTrue(FastqProcessor.refusedTheReads(true, refused));
        // Not the reads' fault: out of memory (run again alone), killed, or a later step on the program's own files.
        assertFalse(FastqProcessor.refusedTheReads(true, new ProgramFailedException("BBDuk", 1, true, "out of memory")));
        assertFalse(FastqProcessor.refusedTheReads(true, new ProgramFailedException("BBDuk", 137, false, "killed")));
        assertFalse(FastqProcessor.refusedTheReads(false, refused));
    }

    @Test
    void aTadpoleKLongerThanTheReadsIsNamedWhenItLeavesTheAssemblyEmpty(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // --tadpole-k 200 on 150-base reads: an empty mini-assembly, said by nothing but blastn's "Query is Empty".
        java.nio.file.Path reads = java.nio.file.Files.writeString(dir.resolve("r.fq"),
                "@r1\n" + "A".repeat(150) + "\n+\n" + "I".repeat(150) + "\n@r2\n" + "C".repeat(120) + "\n+\n" + "I".repeat(120) + "\n");
        java.nio.file.Path empty = java.nio.file.Files.createFile(dir.resolve("a.fasta"));
        assertEquals("Warning: --tadpole-k 200 is longer than the reads of sample s (150 bases at most): its"
                + " mini-assembly is empty, and its products are looked for in the reads only",
                FastqProcessor.longerThanTheReads(200, empty, reads, "s"));
        assertEquals(null, FastqProcessor.longerThanTheReads(150, empty, reads, "s"), "k as long as a read");
        java.nio.file.Path built = java.nio.file.Files.writeString(dir.resolve("b.fasta"), ">c\nACGT\n");
        assertEquals(null, FastqProcessor.longerThanTheReads(200, built, reads, "s"), "an assembly was built");
    }

    @Test
    void onlyReadSetsBbdukRefusedCanMakeTheFaultTheProgramsNotTheirNames() {
        PipelineException bbduk = new ProgramFailedException("BBDuk", 1, false, "BBDuk failed with exit code 1");
        PipelineException named = new BbtoolsCommands.UnusablePath("BBTools reads a comma in a file name");
        // Every read set BBDuk was given refused, two of them: the program's fault (a BBTools that cannot run).
        assertEquals(java.util.List.of("a", "b"), FastqProcessor.programAtFault(java.util.Map.of("b", bbduk, "a", bbduk), 2, 3));
        // Two left out for their names, none refused by BBDuk: the files' fault (it failed the run, assemblies too).
        assertEquals(java.util.List.of(), FastqProcessor.programAtFault(java.util.Map.of("a", named, "b", named), 2, 3));
        // BBDuk refused the one set it was given, beside an assembly: that sample's fault.
        assertEquals(java.util.List.of(), FastqProcessor.programAtFault(java.util.Map.of("a", named, "b", bbduk), 2, 3));
        // The run's only sample: refused by BBDuk, the program's; left out for its name, the file's.
        assertEquals(java.util.List.of("a"), FastqProcessor.programAtFault(java.util.Map.of("a", bbduk), 1, 1));
        assertEquals(java.util.List.of(), FastqProcessor.programAtFault(java.util.Map.of("a", named), 1, 1));
    }
}
