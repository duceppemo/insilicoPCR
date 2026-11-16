package ca.canada.inspection.pipeline;

class PipelineDirectoriesTest {
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void anOutputFolderHoldingLinksWhereARunWritesIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // A reused folder with primer_tmp.fasta, or detailed_report/<sample>, a link elsewhere: the run wrote through
        // it, overwriting (or deleting) the file it points to.
        java.nio.file.Path decoy = java.nio.file.Files.writeString(dir.resolve("decoy.txt"), "keep me");
        java.nio.file.Path output = java.nio.file.Files.createDirectories(dir.resolve("run").resolve("detailed_report")
                .resolve("s")).getParent().getParent();
        java.nio.file.Files.createSymbolicLink(output.resolve("detailed_report").resolve("s").resolve("s.tsv.part"), decoy);

        PipelineException error = org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class,
                () -> PipelineDirectories.create(output));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("holds symbolic links where a run writes"),
                error.getMessage());
        org.junit.jupiter.api.Assertions.assertEquals("keep me", java.nio.file.Files.readString(decoy));
        // The output folder itself may be a link (to a scratch disk, say).
        java.nio.file.Path real = java.nio.file.Files.createDirectory(dir.resolve("real"));
        java.nio.file.Path link = java.nio.file.Files.createSymbolicLink(dir.resolve("link"), real);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> PipelineDirectories.create(link));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void linksWhereARunDoesNotWriteAreNoConcernOfIts(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws java.io.IOException {
        // An input folder inside the output folder, its reads linked from a shared store, and a .venv: refused
        // before 0.15.3, the message saying "where a run writes".
        java.nio.file.Path store = java.nio.file.Files.writeString(dir.resolve("store.fasta"), ">c\nACGT\n");
        java.nio.file.Path output = java.nio.file.Files.createDirectories(dir.resolve("project"));
        java.nio.file.Path reads = java.nio.file.Files.createDirectories(output.resolve("reads"));
        java.nio.file.Files.createSymbolicLink(reads.resolve("isolate.fasta"), store);
        java.nio.file.Files.createDirectories(output.resolve(".venv").resolve("bin"));
        java.nio.file.Files.createSymbolicLink(output.resolve(".venv").resolve("bin").resolve("python"), store);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> PipelineDirectories.create(output));

        // Where a run writes, at its top: refused.
        java.nio.file.Files.createSymbolicLink(output.resolve("QAlog.txt"), store);
        org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class, () -> PipelineDirectories.create(output));
    }
}
