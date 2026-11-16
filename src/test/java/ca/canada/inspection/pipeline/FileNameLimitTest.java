package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileNameLimitTest {

    @TempDir
    Path dir;

    @Test
    void aSampleWhoseFilesNamesWouldBeTooLongIsAnIssueOfItsOwn() {
        // A 247-character name: <name>.tsv.part could not be made, and the run failed with every sample; the reads
        // of one hung it, BBDuk never ending.
        PipelineDirectories directories = new PipelineDirectories(dir, dir.resolve("detailed_report"),
                dir.resolve(PipelineDirectories.CONSOLIDATED));
        Sample longOne = new Sample("L".repeat(247));
        longOne.setFileType(Sample.FileType.FASTA);
        Sample reads = new Sample("R".repeat(230));
        reads.setFileType(Sample.FileType.FASTQ);
        Sample fine = new Sample("isolate_01");
        fine.setFileType(Sample.FileType.FASTQ);

        List<ca.canada.inspection.util.InputCheck.Issue> issues = FileNameLimit.tooLong(directories,
                List.of(longOne, reads, fine));

        assertEquals(List.of("L".repeat(247), "R".repeat(230)), issues.stream().map(issue -> issue.sample()).toList());
        assertTrue(issues.getFirst().detail().startsWith("its result files' names would have 256 bytes"),
                issues.getFirst().detail());
    }
}
