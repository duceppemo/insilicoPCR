package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHitParser;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.util.ProcessRunner;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FASTQ mode: searches the first-pass baited reads directly for amplicons, so a
 * target the mini-assembly omitted or split (#7) can still be called when single
 * reads or merged pairs hold it. Shared by the command-line and GUI pipelines; run
 * after the primer BLAST database exists.
 */
public final class ReadLevelStage {

    /**
     * @param blastn          blastn executable
     * @param primerDatabase  primer FASTA with its BLAST database
     * @param evalue          blastn e-value, as for contigs
     * @param threads         samples searched in parallel
     */
    public record Settings(Path blastn, Path primerDatabase, double evalue, int threads) {
    }

    private ReadLevelStage() {
    }

    /** @return read-level evidence per FASTQ sample name */
    public static Map<String, ReadEvidence> run(Settings settings, Path detailedDir, Map<String, Sample> samples,
                                                Map<String, String> primers, AmpliconOptions options) {
        List<Sample> fastq = samples.values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
        Map<String, ReadEvidence> evidence = new ConcurrentHashMap<>();
        ParallelStageRunner.run("Read-level amplicon search", fastq, settings.threads(), sample ->
                evidence.put(sample.getName(), searchSample(settings, detailedDir.resolve(sample.getName()),
                        sample, primers, options)));
        return Map.copyOf(evidence);
    }

    private static ReadEvidence searchSample(Settings settings, Path sampleDir, Sample sample,
                                             Map<String, String> primers, AmpliconOptions options) {
        String name = sample.getName();
        Path fasta = BbtoolsCommands.baitedReadsFasta(sampleDir, name);
        Map<String, Integer> lengths = new HashMap<>();
        Map<String, String> descriptions = new HashMap<>();
        writeReadsFasta(readInputs(sampleDir, name), fasta, lengths, descriptions);

        Path hitsFile = BbtoolsCommands.baitedReadsHits(sampleDir, name);
        if (lengths.isEmpty()) {
            return new ReadEvidence(Map.of(), Map.of(), Map.of());
        }
        ProcessRunner.run(null,
                settings.blastn().toString(),
                "-task", "blastn-short",
                "-query", fasta.toString(),
                "-db", settings.primerDatabase().toString(),
                "-evalue", Double.toString(settings.evalue()),
                "-num_alignments", "1000000",
                "-num_threads", "1",
                "-outfmt", BlastTsv.OUTFMT,
                "-out", hitsFile.toString());
        BlastTsv.prependHeader(hitsFile);

        Sample reads = new Sample();
        reads.setName(name);
        reads.setFileType("reads");
        descriptions.forEach(reads::addContig);
        try (BufferedReader reader = Files.newBufferedReader(hitsFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                BlastHitParser.parse(line, name, primers, options)
                        .ifPresent(hit -> reads.addBlastResult(hit.subjectID(), hit));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse read-level BLAST output: " + hitsFile, e);
        }
        return ReadLevelCaller.evaluate(reads, lengths, primers, options);
    }

    /** Merged pairs plus unmerged mates when BBMerge ran (paired input), else the first-pass reads. */
    private static List<Path> readInputs(Path sampleDir, String name) {
        Path merged = BbtoolsCommands.mergedReads(sampleDir, name);
        Path unmerged = BbtoolsCommands.unmergedReads(sampleDir, name);
        if (Files.isRegularFile(merged) && Files.isRegularFile(unmerged)) {
            return List.of(merged, unmerged);
        }
        return List.of(BbtoolsCommands.targetMatches(sampleDir, name));
    }

    /**
     * Writes FASTQ records as FASTA named {@code read_<n>}: SRA-style mates share a
     * name, and reads with the same BLAST query ID would be treated as one sequence.
     * The original header is kept as the description.
     */
    static void writeReadsFasta(List<Path> fastqFiles, Path fasta, Map<String, Integer> lengths,
                                Map<String, String> descriptions) {
        int index = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(fasta, StandardCharsets.UTF_8)) {
            for (Path fastq : fastqFiles) {
                try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(fastq)) {
                    String header;
                    while ((header = reader.readLine()) != null) {
                        if (header.isBlank()) {
                            continue;
                        }
                        String sequence = reader.readLine();
                        String plus = reader.readLine();
                        String quality = reader.readLine();
                        if (!header.startsWith("@") || sequence == null || plus == null || quality == null) {
                            throw new PipelineException("Malformed FASTQ record in " + fastq + " near: " + header);
                        }
                        String id = "read_" + index++;
                        String original = header.substring(1).trim();
                        writer.write(">" + id + " " + original);
                        writer.newLine();
                        writer.write(sequence.trim());
                        writer.newLine();
                        lengths.put(id, sequence.trim().length());
                        descriptions.put(id, original);
                    }
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write baited reads FASTA: " + fasta, e);
        }
    }
}
