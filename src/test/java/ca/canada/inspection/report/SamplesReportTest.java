package ca.canada.inspection.report;

import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.TestRows;
import ca.canada.inspection.util.InputCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SamplesReportTest {

    @TempDir
    Path dir;

    @Test
    void everySampleIsListedAnalysedOrNotWithItsProductsCounted() throws IOException {
        // A sample with no product has no row in report.tsv: only QAlog.txt's lines named the negatives.
        Sample positive = sample("iso1", Sample.FileType.FASTA, "iso1.fasta");
        Sample negative = sample("iso2", Sample.FileType.FASTA, "iso2.fasta");
        Sample reads = sample("lib", Sample.FileType.FASTQ, "lib_R1.fastq.gz", "lib_R2.fastq.gz");
        List<InputCheck.Issue> issues = List.of(
                new InputCheck.Issue("cut", List.of("cut_R1.fq.gz"), "the file is cut short", "gzip ends early"),
                new InputCheck.Issue("cut", List.of("cut_R2.fq.gz"), "the pair holds fewer reads", ""),
                new InputCheck.Issue("=bad", List.of("=bad.fasta"), "the file holds no sequence", ""));
        Path report = dir.resolve("report(1).tsv");

        Path written = SamplesReport.write(report, List.of(reads, negative, positive), issues, List.of(
                TestRows.row("iso1", "toxA", 1, 312, "c1", ""), TestRows.row("iso1", "stxQ", 1, 118, "c1", ""),
                TestRows.row("lib", "toxA", 1, 312, "contig_1", "")));

        assertEquals(dir.resolve("report(1)_samples.tsv"), written);
        assertEquals(List.of(
                "Sample\tStatus\tInput\tFiles\tProducts\tReason",
                "'=bad\tnot-analysed\tassembly\t'=bad.fasta\t\tthe file holds no sequence",
                "cut\tnot-analysed\treads\tcut_R1.fq.gz; cut_R2.fq.gz\t\tthe file is cut short (gzip ends early);"
                        + " the pair holds fewer reads",
                "iso1\tanalysed\tassembly\tiso1.fasta\t2\t",
                "iso2\tanalysed\tassembly\tiso2.fasta\t0\t",
                "lib\tanalysed\treads\tlib_R1.fastq.gz; lib_R2.fastq.gz\t1\t"), Files.readAllLines(written));
    }

    private Sample sample(String name, Sample.FileType type, String... files) {
        Sample sample = new Sample(name);
        sample.setFileType(type);
        for (String file : files) {
            sample.addFile(dir.resolve(file));
        }
        return sample;
    }
}
