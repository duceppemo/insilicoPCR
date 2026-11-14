package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHitParser;
import ca.canada.inspection.pcr.QueryTemplates;
import ca.canada.inspection.pcr.ConsolidatedReport;
import ca.canada.inspection.pcr.ExpectedSizes;
import ca.canada.inspection.pcr.FastqDiagnosticsReport;
import ca.canada.inspection.pcr.PairSupport;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadExtensionCaller.Outcome;
import ca.canada.inspection.pcr.ReadExtensionCaller.Product;
import ca.canada.inspection.pcr.ReadExtensionCaller.Seed;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/**
 * FASTQ mode: for assays that neither the mini-assembly nor a read reported although
 * reads carry both primers (and, in {@code target} mode, for assays without a product
 * of their declared target size), extends the primer-carrying reads through the baited
 * reads and calls products on the extended reads ({@link ReadExtensionCaller}). This
 * is what finds a product longer than a read or merged pair. Shared by the
 * command-line and GUI pipelines; run after the read-level search, with the contig
 * hits of each sample attached and while the primer BLAST database still exists.
 */
public final class ReadExtensionStage {

    /**
     * @param search           blastn settings, logging and cancellation, as for the read-level search
     * @param bbtools          builds the Tadpole command
     * @param bbtoolsDirectory working directory of BBTools child processes
     * @param mode             which assays reads are extended for
     * @param sizes            product sizes declared in the primer file
     */
    record Settings(ReadLevelStage.Settings search, BbtoolsCommands bbtools, Path bbtoolsDirectory,
                    ReadExtensionCaller.Mode mode, ExpectedSizes sizes) {
    }

    /**
     * @param outcomes what the extension showed, per sample name then assay; a sample
     *                 without an assay to extend has no entry
     * @param rows     report rows of the products called that the sample did not show yet, per sample name
     * @param notes    samples whose extension could not be run, with the reason
     */
    public record Result(Map<String, Map<String, Outcome>> outcomes, Map<String, List<List<String>>> rows,
                         List<String> notes) {
        /** No extension was run: the mode is {@code off}, or the run has no FASTQ sample. */
        public static final Result NONE = new Result(Map.of(), Map.of(), List.of());

        /** The outcomes for one sample; empty when nothing was extended for it. */
        public Map<String, Outcome> of(String sampleName) {
            return outcomes.getOrDefault(sampleName, Map.of());
        }
    }

    private ReadExtensionStage() {
    }

    /**
     * @param evidence read-level evidence per FASTQ sample name, from {@link ReadLevelStage#run}
     */
    static Result run(Settings settings, Path detailedDir, Map<String, Sample> samples, Map<String, String> primers,
                      AmpliconOptions options, Map<String, ReadEvidence> evidence) {
        if (settings.mode() == ReadExtensionCaller.Mode.OFF) {
            return Result.NONE;
        }
        Map<String, Map<String, Outcome>> outcomes = new TreeMap<>();
        Map<String, List<List<String>>> rows = new HashMap<>();
        List<String> notes = new ArrayList<>();
        List<Sample> fastq = samples.values().stream()
                .filter(Sample::isFastq)
                .sorted(Comparator.comparing(Sample::getName))
                .toList();
        // One sample at a time: each extension loads the k-mers of all the baited reads.
        for (Sample sample : fastq) {
            ReadEvidence reads = evidence.get(sample.getName());
            if (reads == null) {
                continue;
            }
            stopIfCancelled(settings);
            try {
                extendSample(settings, detailedDir.resolve(sample.getName()), sample, primers, options, reads,
                        outcomes, rows);
            } catch (PipelineException e) {
                // The assays stay as the read-level search left them; the run goes on.
                String reason = BbtoolsCommands.ranOutOfMemory(e)
                        ? BbtoolsCommands.outOfMemoryMessage(((ProgramFailedException) e).program(), "",
                                settings.bbtools().childMemoryGiB())
                        : e.getMessage();
                notes.add("read extension could not be run for sample " + sample.getName() + " (" + reason + ")");
            }
        }
        return new Result(Map.copyOf(outcomes), Map.copyOf(rows), List.copyOf(notes));
    }

    private static void extendSample(Settings settings, Path sampleDir, Sample sample, Map<String, String> primers,
                                     AmpliconOptions options, ReadEvidence reads,
                                     Map<String, Map<String, Outcome>> outcomes,
                                     Map<String, List<List<String>>> rows) {
        String name = sample.getName();
        Path seedsFile = BbtoolsCommands.extensionSeeds(sampleDir, name);
        Path extendedFile = BbtoolsCommands.extendedReads(sampleDir, name);
        Path hitsFile = BbtoolsCommands.extendedReadsHits(sampleDir, name);
        // No stale files from an earlier run in this folder.
        deleteQuietly(seedsFile);
        deleteQuietly(extendedFile);
        deleteQuietly(hitsFile);
        deleteQuietly(BbtoolsCommands.extensionCandidates(sampleDir, name));
        deleteQuietly(BbtoolsCommands.candidateReads(sampleDir, name));

        List<String> assays = ReadExtensionCaller.candidates(sample, primers, options, reads, settings.mode(),
                settings.sizes());
        if (assays.isEmpty()) {
            return;
        }
        Path baitedReads = BbtoolsCommands.doubleTargetMatches(sampleDir, name);
        if (!Files.isRegularFile(baitedReads)) {
            throw new PipelineException("no baited reads file " + baitedReads.getFileName());
        }
        List<Seed> seeds = ReadExtensionCaller.seeds(assays, primers.keySet(), reads);
        Map<String, ReadLevelStage.ReadRecord> seedReads = writeSeeds(seeds,
                BbtoolsCommands.baitedReadsFasta(sampleDir, name), seedsFile);
        int reach = options.maxAmpliconLength() > 0 ? options.maxAmpliconLength() : ReadExtensionCaller.DEFAULT_REACH;

        ExternalProgram.run(settings.search().monitor(), settings.bbtoolsDirectory(),
                settings.bbtools().extend(seedsFile, baitedReads, extendedFile, ReadExtensionCaller.K, reach),
                "Tadpole");
        if (!Files.isRegularFile(extendedFile)) {
            throw new PipelineException("Tadpole wrote no extended reads");
        }
        Map<String, Integer> lengths = FastqDiagnosticsReport.contigLengths(extendedFile);
        ReadLevelStage.runBlast(settings.search(), settings.search().threads(), extendedFile, hitsFile);
        BlastTsv.prependHeader(hitsFile);

        Map<String, Outcome> sampleOutcomes = checkAgainstReadPairs(settings, ReadExtensionCaller.evaluate(
                extendedSample(name, hitsFile, extendedFile, lengths, primers, options), lengths, seeds, primers, options, reach,
                ReadExtensionCaller.knownSizes(sample, primers, options, reads)),
                extendedFile, sampleDir, sample);
        outcomes.put(name, sampleOutcomes);

        Map<String, String> readOfSeed = new HashMap<>();
        seeds.forEach(seed -> readOfSeed.put(seed.id(), seed.read()));
        List<List<String>> sampleRows = new ArrayList<>();
        for (Outcome outcome : sampleOutcomes.values()) {
            for (Product product : outcome.added()) {
                ReadLevelStage.ReadRecord seedRead =
                        seedReads.get(readOfSeed.get(product.row().get(ConsolidatedReport.CONTIG_COLUMN)));
                sampleRows.add(ReadExtensionCaller.reportRow(product, seedRead == null ? "" : seedRead.description()));
            }
        }
        if (!sampleRows.isEmpty()) {
            rows.put(name, sampleRows);
        }
    }

    /**
     * Checks every product that extended reads of both primers hold against the sample's
     * read pairs ({@link PairSupport}): a wrong turn through a repeat is closed from both
     * sides too, but no read pair of the sample spans it. The pairs are taken from the
     * sample's own reads, with BBDuk, not from the baited reads, which thin out away
     * from the primers.
     *
     * @param extendedFile the extended reads the products lie on
     */
    static Map<String, Outcome> checkAgainstReadPairs(Settings settings, Map<String, Outcome> outcomes,
                                                      Path extendedFile, Path sampleDir, Sample sample) {
        List<Product> closed = new ArrayList<>();
        outcomes.values().forEach(outcome -> closed.addAll(outcome.closed()));
        if (closed.isEmpty()) {
            return outcomes;
        }
        String name = sample.getName();
        List<String> sequences = candidateSequences(closed, extendedFile);
        Path candidates = BbtoolsCommands.extensionCandidates(sampleDir, name);
        try (BufferedWriter writer = Files.newBufferedWriter(candidates, StandardCharsets.UTF_8)) {
            for (int i = 0; i < closed.size(); i++) {
                Product product = closed.get(i);
                writer.write(">" + product.assay() + "_" + product.size() + "bp_on_"
                        + product.row().get(ConsolidatedReport.CONTIG_COLUMN)
                        + " best-supported path held by extended reads, not an assembled consensus");
                writer.newLine();
                writer.write(sequences.get(i));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the candidate products: " + candidates, e);
        }
        Path candidateReads = BbtoolsCommands.candidateReads(sampleDir, name);
        ExternalProgram.run(settings.search().monitor(), settings.bbtoolsDirectory(),
                settings.bbtools().recruit(candidates, sample.files(), candidateReads, ReadExtensionCaller.K), "BBDuk");
        PairSupport.Tally tally = tally(sequences, candidateReads, sample.files().size() == 2, settings);
        Map<Product, PairSupport.Verdict> verdicts = new java.util.IdentityHashMap<>();
        for (int i = 0; i < closed.size(); i++) {
            verdicts.put(closed.get(i), tally.verdict(i));
        }
        Map<String, Outcome> checked = new java.util.LinkedHashMap<>();
        outcomes.forEach((assay, outcome) -> checked.put(assay, outcome.withSupport(verdicts::get)));
        return checked;
    }

    /** The sequence of each product, cut from the extended read its row lies on. */
    static List<String> candidateSequences(List<Product> products, Path extendedFile) {
        Set<String> wanted = new HashSet<>();
        products.forEach(product -> wanted.add(product.row().get(ConsolidatedReport.CONTIG_COLUMN)));
        Map<String, String> extended = readFasta(extendedFile, wanted);
        List<String> sequences = new ArrayList<>();
        for (Product product : products) {
            String read = extended.get(product.row().get(ConsolidatedReport.CONTIG_COLUMN));
            String[] location = product.row().get(ConsolidatedReport.LOCATION_COLUMN).split("-");
            sequences.add(read.substring(Integer.parseInt(location[0]) - 1,
                    Math.min(read.length(), Integer.parseInt(location[1]))));
        }
        return sequences;
    }

    /**
     * Places the reads of a FASTQ file on the products.
     *
     * @param paired whether the file holds pairs, mates following each other
     */
    static PairSupport.Tally tally(List<String> sequences, Path reads, boolean paired, Settings settings) {
        PairSupport.Tally tally = new PairSupport.Tally(sequences);
        try (BufferedReader reader = SequenceFileUtils.openMaybeGzip(reads)) {
            String previous = null;
            long records = 0;
            String header;
            while ((header = reader.readLine()) != null) {
                String sequence = reader.readLine();
                String plus = reader.readLine();
                String quality = reader.readLine();
                if (!header.startsWith("@") || sequence == null || plus == null || quality == null) {
                    throw new PipelineException("Malformed FASTQ record in " + reads + " near: " + header);
                }
                if (!paired) {
                    tally.addRead(sequence);
                } else if (previous == null) {
                    previous = sequence;
                } else {
                    tally.addPair(previous, sequence);
                    previous = null;
                }
                if (++records % 500_000 == 0 && settings != null) {
                    stopIfCancelled(settings);
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read " + reads, e);
        }
        return tally;
    }

    /** The given records of a FASTA file whose sequences may span several lines, by ID. */
    static Map<String, String> readFasta(Path fasta, Set<String> ids) {
        Map<String, StringBuilder> found = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
            StringBuilder current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(">")) {
                    String id = line.substring(1).trim().split("\\s+", 2)[0];
                    current = ids.contains(id) ? found.computeIfAbsent(id, ignored -> new StringBuilder()) : null;
                } else if (current != null) {
                    current.append(line.strip());
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not read the extended reads: " + fasta, e);
        }
        Map<String, String> sequences = new HashMap<>();
        found.forEach((id, sequence) -> sequences.put(id, sequence.toString()));
        return sequences;
    }

    /**
     * Writes the seeds as FASTA, named by {@link Seed#id}, with the assay, primer role and
     * baited read as description. A read carrying primers of
     * two assays is written once per assay, so that each copy is judged for its own assay.
     *
     * @return the baited reads used, by read ID
     */
    static Map<String, ReadLevelStage.ReadRecord> writeSeeds(List<Seed> seeds, Path baitedReadsFasta, Path seedsFile) {
        Set<String> wanted = new HashSet<>();
        seeds.forEach(seed -> wanted.add(seed.read()));
        Map<String, ReadLevelStage.ReadRecord> records = ReadLevelStage.readRecords(baitedReadsFasta, wanted);
        try (BufferedWriter writer = Files.newBufferedWriter(seedsFile, StandardCharsets.UTF_8)) {
            for (Seed seed : seeds) {
                ReadLevelStage.ReadRecord record = records.get(seed.read());
                if (record == null) {
                    throw new PipelineException("read " + seed.read() + " is not in " + baitedReadsFasta.getFileName());
                }
                // The assay, the primer and the read it is seeded from, for whoever audits a call.
                writer.write(">" + seed.id() + " " + seed.assay() + "-" + seed.role() + " " + seed.read());
                writer.newLine();
                writer.write(record.sequence());
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the extension seeds: " + seedsFile, e);
        }
        return records;
    }

    /**
     * The extended reads as a pseudo-sample, with the accepted primer hits attached.
     *
     * @param extendedFile the FASTA of extended reads BLAST searched: trimmed hit ends are
     *                     compared with it (see TrimmedEnds), as for contigs and reads
     */
    static Sample extendedSample(String name, Path hitsFile, Path extendedFile, Map<String, Integer> lengths,
                                 Map<String, String> primers, AmpliconOptions options) {
        Sample extended = new Sample();
        extended.setName(name);
        extended.setFileType("reads");
        lengths.keySet().forEach(id -> extended.addContig(id, ""));
        try (BufferedReader reader = Files.newBufferedReader(hitsFile, StandardCharsets.UTF_8);
             QueryTemplates templates = new QueryTemplates(List.of(extendedFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("qseqid")) {
                    continue;
                }
                BlastHitParser.parse(line, name, primers, options, templates)
                        .ifPresent(hit -> extended.addBlastResult(hit.subjectID(), hit));
            }
        } catch (IOException e) {
            throw new PipelineException("Could not parse the BLAST output of the extended reads: " + hitsFile, e);
        }
        return extended;
    }

    private static void stopIfCancelled(Settings settings) {
        if (settings.search().monitor().isCancelled()) {
            throw new CancellationException("Read extension was cancelled.");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A leftover file is only clutter; it is never read without being rewritten.
        }
    }
}
