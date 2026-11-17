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

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("holds symbolic links or junctions where a run writes"),
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

    @org.junit.jupiter.api.Test
    void theNamesARunWritesAreMatchedWhateverTheirCaseWhereTheSystemIgnoresIt() {
        // On Windows and macOS, a link named Detailed_Report is the folder the run writes in: it was let through.
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written("Detailed_Report", true));
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written("QALOG.txt", true));
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written("Primer_Tmp.fasta.nhr", true));
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written(".insilicoPCR.LOCK", true));
        org.junit.jupiter.api.Assertions.assertFalse(PipelineDirectories.written("reads", true));
        // Where case counts (Linux), LOG.TXT is not log.txt: a link by that name was refused (0.15.4).
        org.junit.jupiter.api.Assertions.assertFalse(PipelineDirectories.written("LOG.TXT.d", false));
        org.junit.jupiter.api.Assertions.assertFalse(PipelineDirectories.written("Detailed_Report", false));
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written("QAlog(1).txt", false));
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.written(".insilicoPCR.lock", false));
    }

    @org.junit.jupiter.api.Test
    void onWindowsOnlyAFolderReparsePointIsTakenForALink() {
        // Java reads every Windows reparse point that is not a symbolic link as "other", and, without following it,
        // not as a folder: a junction (a folder once followed) but also a deduplicated file, which made a reused
        // output folder refused (0.15.4).
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.leadsElsewhere(attributes(false, true, false),
                true, true), "a junction");
        org.junit.jupiter.api.Assertions.assertFalse(PipelineDirectories.leadsElsewhere(attributes(false, true, false),
                false, true), "a deduplicated file");
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.leadsElsewhere(attributes(true, false, false),
                false, true), "a symbolic link");
        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.leadsElsewhere(attributes(false, true, false),
                false, false), "a pipe elsewhere");
        org.junit.jupiter.api.Assertions.assertFalse(PipelineDirectories.leadsElsewhere(attributes(false, false, true),
                true, true), "a folder");
    }

    private static java.nio.file.attribute.BasicFileAttributes attributes(boolean link, boolean other, boolean folder) {
        return new java.nio.file.attribute.BasicFileAttributes() {
            public java.nio.file.attribute.FileTime lastModifiedTime() { return null; }
            public java.nio.file.attribute.FileTime lastAccessTime() { return null; }
            public java.nio.file.attribute.FileTime creationTime() { return null; }
            public boolean isRegularFile() { return !link && !other && !folder; }
            public boolean isDirectory() { return folder; }
            public boolean isSymbolicLink() { return link; }
            public boolean isOther() { return other; }
            public long size() { return 0; }
            public Object fileKey() { return null; }
        };
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aPipeWhereARunWritesIsRefusedAsALinkIs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // Java reads a Windows junction as "other", as it does a pipe: the one test of that rule this system allows.
        java.nio.file.Path output = java.nio.file.Files.createDirectories(dir.resolve("run"));
        Process mkfifo = new ProcessBuilder("mkfifo", output.resolve("QAlog.txt.part").toString()).start();
        org.junit.jupiter.api.Assumptions.assumeTrue(mkfifo.waitFor() == 0, "mkfifo");

        org.junit.jupiter.api.Assertions.assertTrue(PipelineDirectories.leadsElsewhere(output.resolve("QAlog.txt.part")));
        org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class, () -> PipelineDirectories.create(output));
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aJunctionWhereARunWritesIsRefused(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // A junction (mklink /J, made without administrator rights, unlike a symbolic link) named detailed_report: a
        // run wrote through it, into the folder it points to.
        java.nio.file.Path elsewhere = java.nio.file.Files.createDirectories(dir.resolve("elsewhere"));
        java.nio.file.Path output = java.nio.file.Files.createDirectories(dir.resolve("run"));
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                output.resolve("detailed_report").toString(), elsewhere.toString()).redirectErrorStream(true).start();
        mklink.getInputStream().readAllBytes();
        org.junit.jupiter.api.Assertions.assertEquals(0, mklink.waitFor(), "mklink /J");
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.isSymbolicLink(output.resolve("detailed_report")));

        PipelineException error = org.junit.jupiter.api.Assertions.assertThrows(PipelineException.class,
                () -> PipelineDirectories.create(output));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("junctions"), error.getMessage());
    }
}
