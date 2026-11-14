package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Sample;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** QAlog.txt names a run's report and samples, and a run is reopened from them. */
class PreviousRunTest {

    @TempDir
    Path dir;

    private Path reads;
    private Path output;

    /** A run of the samples zeta (a read pair) and alpha (an assembly) that wrote report(1).tsv. */
    private Path finishedRun() throws IOException {
        reads = Files.createDirectories(dir.resolve("reads"));
        output = Files.createDirectories(dir.resolve("results"));
        Map<String, Sample> samples = new LinkedHashMap<>();
        samples.put("zeta", sample("zeta", "zeta_R1.fastq.gz", "zeta_R2.fastq.gz"));
        samples.put("alpha", sample("alpha", "alpha.fasta"));
        // Not a sample of the run: added to the folder later, or never a sequence file.
        Files.writeString(reads.resolve("md5sums.txt"), "");
        Files.writeString(reads.resolve("late.fasta"), ">c\nACGT\n");
        Path report = Files.createDirectories(output.resolve(PipelineDirectories.CONSOLIDATED)).resolve("report(1).tsv");
        Files.writeString(report, "Sample\tGene\n");

        QaLogWriter.write(TestContexts.writingTo(output, samples), "0.0.0", report,
                List.of("FASTQ mode Tadpole settings: k=31"));
        return report;
    }

    private Sample sample(String name, String... files) throws IOException {
        Sample sample = new Sample();
        sample.setName(name);
        for (String file : files) {
            sample.addFile(Files.writeString(reads.resolve(file), ""));
        }
        return sample;
    }

    @Test
    void theLogNamesTheReportTheSamplesAndTheirFilesOnly() throws IOException {
        finishedRun();

        List<String> lines = Files.readAllLines(output.resolve("QAlog.txt"));
        assertTrue(lines.contains("Consolidated report: consolidated_report/report(1).tsv"), lines.toString());
        int inputs = lines.indexOf("Input File(s) :");
        assertEquals(List.of(reads.resolve("alpha.fasta").toString(), reads.resolve("zeta_R1.fastq.gz").toString(),
                        reads.resolve("zeta_R2.fastq.gz").toString(), "", "Sample analysed: zeta",
                        "Sample analysed: alpha", "FASTQ mode Tadpole settings: k=31"),
                lines.subList(inputs + 1, lines.size()));
        // An application before 0.15.1 reads the input list up to the blank line.
        assertEquals(3, QaLogInputs.read(output.resolve("QAlog.txt")).size());
    }

    @Test
    void theRunIsReopenedWithItsOwnReportAndSamples() throws IOException {
        Path report = finishedRun();
        // A newer report from another run in the same folder is not this run's.
        Path newer = Files.writeString(report.resolveSibling("report(2).tsv"), "Sample\tGene\n");
        Files.setLastModifiedTime(newer, FileTime.from(Instant.now().plusSeconds(60)));

        PreviousRun run = PreviousRun.open(output);

        assertEquals(report, run.report());
        assertEquals(List.of("zeta", "alpha"), run.samples());
    }

    @Test
    void aMovedRunFolderIsReopened() throws IOException {
        finishedRun();
        Path moved = Files.move(output, dir.resolve("archive"));

        PreviousRun run = PreviousRun.open(moved);

        assertEquals(moved.resolve("consolidated_report").resolve("report(1).tsv"), run.report());
        assertEquals(List.of("zeta", "alpha"), run.samples());
    }

    @Test
    void aLogWrittenBefore0151IsReadFromItsInputList() throws IOException {
        Path run = Files.createDirectories(dir.resolve("old"));
        Path reports = Files.createDirectories(run.resolve("consolidated_report"));
        Files.writeString(reports.resolve("report.tsv"), "Sample\n");
        Path latest = Files.writeString(reports.resolve("report(1).tsv"), "Sample\n");
        Files.setLastModifiedTime(latest, FileTime.from(Instant.now().plusSeconds(60)));
        Files.writeString(run.resolve("QAlog.txt"), "In Silico PCR version: 0.15.0\nInput File(s) :\n"
                + "/gone/S_R1.fastq.gz\n/gone/S_R2.fastq.gz\n/gone/T.fasta\n\nSample not analysed: T\n");

        PreviousRun previous = PreviousRun.open(run);

        assertEquals(latest, previous.report());
        assertEquals(List.of("S"), previous.samples());
    }

    @Test
    void aFolderWithoutALogOrWithoutItsReportIsAMessage() throws IOException {
        PipelineException noLog = assertThrows(PipelineException.class, () -> PreviousRun.open(dir));
        assertEquals("QAlog.txt not found in " + dir + ": not the output folder of a finished run", noLog.getMessage());

        Path report = finishedRun();
        Files.delete(report);
        PipelineException noReport = assertThrows(PipelineException.class, () -> PreviousRun.open(output));
        assertEquals("The report of the run in " + output + " is missing: " + report, noReport.getMessage());
    }

    @Test
    void aLogWrittenBefore0151WithoutItsReportsOrSamplesIsAMessage() throws IOException {
        Path run = Files.createDirectories(dir.resolve("old"));
        Path log = Files.writeString(run.resolve("QAlog.txt"), "In Silico PCR version: 0.15.0\nInput File(s) :\n"
                + "/gone/notes.txt\n");

        PipelineException noFolder = assertThrows(PipelineException.class, () -> PreviousRun.open(run));
        assertEquals("Consolidated report folder not found: " + run.resolve("consolidated_report"), noFolder.getMessage());

        Files.createDirectories(run.resolve("consolidated_report"));
        PipelineException noReport = assertThrows(PipelineException.class, () -> PreviousRun.open(run));
        assertEquals("No consolidated report found in " + run.resolve("consolidated_report"), noReport.getMessage());

        Files.writeString(run.resolve("consolidated_report").resolve("report.tsv"), "Sample\n");
        PipelineException noSample = assertThrows(PipelineException.class, () -> PreviousRun.open(run));
        assertEquals("No input samples were found in " + log, noSample.getMessage());
    }

    @Test
    void aReportOutsideTheOutputFolderIsNamedInFull() throws IOException {
        output = Files.createDirectories(dir.resolve("results"));
        reads = Files.createDirectories(dir.resolve("reads"));
        Path elsewhere = Files.writeString(dir.resolve("report.tsv"), "Sample\n");

        QaLogWriter.write(TestContexts.writingTo(output, Map.of()), "0.0.0", elsewhere, List.of());

        assertEquals(elsewhere, PreviousRun.open(output).report());
        assertEquals(List.of(), PreviousRun.open(output).samples());
    }
}
