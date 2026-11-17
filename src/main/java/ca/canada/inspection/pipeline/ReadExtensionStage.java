package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.BlastHits;
import ca.canada.inspection.pcr.PairSupport;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller.Outcome;
import ca.canada.inspection.pcr.ReadExtensionCaller.Product;
import ca.canada.inspection.pcr.ReadExtensionCaller.Seed;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TrimmedEnds;
import ca.canada.inspection.util.FastaScanner;
import ca.canada.inspection.util.FastqRecords;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.concurrent.CancellationException;

/**
 * FASTQ mode: for assays that neither the mini-assembly nor a read reported although
 * reads carry both primers (and, in {@code target} mode, for assays without a product
 * of their declared target size), extends the primer-carrying reads through the baited
 * reads and calls products on the extended reads ({@link ReadExtensionCaller}). This
 * is what finds a product longer than a read or merged pair. A step of
 * {@link InSilicoPcrPipeline}, run after the read-level search, with the contig
 * hits of each sample attached and while the primer BLAST database still exists.
 */
public final class ReadExtensionStage {

    /**
     * @param search           blastn settings, logging and cancellation, as for the read-level search
     * @param bbtools          builds the Tadpole and BBDuk commands
     * @param mode             which assays reads are extended for
     */
    record Settings(ReadLevelStage.Settings search, BbtoolsCommands bbtools, ReadExtensionCaller.Mode mode) {

        /** The settings of a run: one sample at a time, so each BBTools child gets everything. */
        static Settings of(PipelineContext context, ReadLevelStage.Settings search) {
            return new Settings(search, BbtoolsCommands.of(context.dependencies(),
                    ChildJavaMemory.forOneChild(context.childJavaMemoryBudgetGiB()), context.config().threads()),
                    context.config().readExtension());
        }
    }

    /**
     * @param outcomes what the extension showed, per sample name then assay; a sample
     *                 without an assay to extend has no entry
     * @param rows     report rows of the products called that the sample did not show yet, per sample name
     * @param notes    samples whose extension could not be run, with the reason
     */
    public record Result(Map<String, Map<String, Outcome>> outcomes, Map<String, List<ReportRow>> rows,
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
    static Result run(Settings settings, PipelineDirectories directories, Map<String, Sample> samples,
                      PrimerPanel primers,
                      AmpliconOptions options, Map<String, ReadEvidence> evidence) {
        if (settings.mode() == ReadExtensionCaller.Mode.OFF) {
            return Result.NONE;
        }
        Map<String, Map<String, Outcome>> outcomes = new TreeMap<>();
        Map<String, List<ReportRow>> rows = new HashMap<>();
        List<String> notes = new ArrayList<>();
        List<Sample> fastq = PipelineContext.fastqSamples(samples);
        // One sample at a time: each extension loads the k-mers of all the baited reads.
        for (Sample sample : fastq) {
            ReadEvidence reads = evidence.get(sample.getName());
            if (reads == null) {
                continue;
            }
            stopIfCancelled(settings);
            try {
                extendSample(settings, directories.sample(sample.getName()), sample, primers, options, reads,
                        outcomes, rows, notes);
            } catch (PipelineException | UncheckedIOException e) {
                // A failed program or a file that cannot be read or written: the assays stay as
                // the read-level search left them, and the run goes on. Unless the run was stopped:
                // a stopped program is a CancellationException, not caught here, but the stop can
                // also break a file being read or written, which lands here.
                stopIfCancelled(settings);
                String reason = e instanceof ProgramFailedException failed && BbtoolsCommands.ranOutOfMemory(failed)
                        ? BbtoolsCommands.outOfMemoryMessage(failed.program(), "", settings.bbtools().childMemoryGiB())
                        : e.getMessage();
                notes.add("read extension could not be run for sample " + sample.getName() + " (" + reason + ")");
            }
        }
        return new Result(Map.copyOf(outcomes), Map.copyOf(rows), List.copyOf(notes));
    }

    private static void extendSample(Settings settings, SampleFiles files, Sample sample, PrimerPanel primers,
                                     AmpliconOptions options, ReadEvidence reads,
                                     Map<String, Map<String, Outcome>> outcomes,
                                     Map<String, List<ReportRow>> rows, List<String> notes) {
        String name = sample.getName();
        Path seedsFile = files.extensionSeeds();
        Path extendedFile = files.extendedReads();
        Path hitsFile = files.extendedReadsHits();
        // No stale files from an earlier run in this folder.
        ca.canada.inspection.util.OutputFiles.deleteQuietly(seedsFile);
        ca.canada.inspection.util.OutputFiles.deleteQuietly(extendedFile);
        ca.canada.inspection.util.OutputFiles.deleteQuietly(hitsFile);
        ca.canada.inspection.util.OutputFiles.deleteQuietly(files.extensionCandidates());
        ca.canada.inspection.util.OutputFiles.deleteQuietly(files.candidateReads());
        ca.canada.inspection.util.OutputFiles.deleteQuietly(files.extensionEnds());
        ca.canada.inspection.util.OutputFiles.deleteQuietly(files.extensionRoundReads());
        ca.canada.inspection.util.OutputFiles.deleteQuietly(files.extensionReads());

        // What the sample shows already, on contigs and in reads: worked out once for the sample.
        Map<String, Set<Integer>> known = ReadExtensionCaller.knownSizes(sample, primers, options, reads);
        List<String> assays = ReadExtensionCaller.candidates(known, primers, reads, settings.mode());
        if (assays.isEmpty()) {
            return;
        }
        Path baitedReads = files.doubleTargetMatches();
        if (!Files.isRegularFile(baitedReads)) {
            throw new PipelineException("no baited reads file " + baitedReads.getFileName());
        }
        List<Seed> seeds = ReadExtensionCaller.seeds(assays, primers, reads);
        Map<String, BaitedReads.ReadRecord> seedReads = writeSeeds(seeds,
                files.baitedReadsFasta(), seedsFile);
        int reach = options.maxAmpliconLength() > 0 ? options.maxAmpliconLength() : ReadExtensionCaller.DEFAULT_REACH;

        // In rounds (ExtensionRounds): products longer than the baited reads reach close as the reads are baited
        // again from the ends of the extended reads.
        Map<String, String> seedBases = new HashMap<>();
        seeds.forEach(seed -> seedBases.put(seed.id(), seedReads.get(seed.read()).sequence()));
        Map<String, Outcome> sampleOutcomes = new ExtensionRounds(settings, files, sample, primers, options, seeds,
                seedBases, known, reach, notes).run();
        outcomes.put(name, sampleOutcomes);

        Map<String, String> readOfSeed = new HashMap<>();
        seeds.forEach(seed -> readOfSeed.put(seed.id(), seed.read()));
        List<ReportRow> sampleRows = new ArrayList<>();
        for (Outcome outcome : sampleOutcomes.values()) {
            for (Product product : outcome.added()) {
                BaitedReads.ReadRecord seedRead =
                        seedReads.get(readOfSeed.get(product.row().contig()));
                sampleRows.add(ReadExtensionCaller.reportRow(product, seedRead == null ? "" : seedRead.description()));
            }
        }
        if (!sampleRows.isEmpty()) {
            rows.put(name, sampleRows);
        }
    }

    /** The most rounds of the extension: about 300 bases more each, past the 5,000 of the default reach. */
    static final int MAX_ROUNDS = 20;
    /** Bases at each end of an extended read from which a round baits: more than a fragment, so mates reach past it. */
    static final int END_LENGTH = 500;

    /**
     * The ends, facing the product, of the extended reads of {@code assay}'s seeds among {@code seeds}, by seed:
     * the {@link ReadExtensionCaller#TANDEM_STRETCH} bases past the seed's own primer, in the direction it extends
     * (from its hit on the extended read). A seed is extended both ways, and the end facing away from the product
     * looped in a microsatellite beside the primer: the assay was taken for one through a repeat. A seed whose
     * primer has no hit on its extended read is left out.
     */
    static Map<String, String> innerEnds(String assay, List<Seed> seeds, Map<String, String> extended, Hits hits,
                                         PrimerPanel primers) {
        Map<String, String> ends = new HashMap<>();
        ca.canada.inspection.pcr.PrimerId.Assay group = primers.assays().get(assay);
        if (group == null) {
            return ends;
        }
        for (Seed seed : seeds) {
            String read = extended.get(seed.id());
            if (!seed.assay().equals(assay) || read == null) {
                continue;
            }
            List<String> oligos = seed.role() == ca.canada.inspection.pcr.PrimerId.Role.FORWARD ? group.forward()
                    : group.reverse();
            Boolean towardsEnd = null;
            for (String oligo : oligos) {
                for (ca.canada.inspection.model.BlastResult hit : hits.byOligo().getOrDefault(oligo, List.of())) {
                    if (hit.queryID().equals(seed.id())) {
                        towardsEnd = hit.isPlusStrand();
                    }
                }
            }
            if (towardsEnd != null) {
                int stretch = ReadExtensionCaller.TANDEM_STRETCH;
                ends.put(seed.id(), towardsEnd ? read.substring(Math.max(0, read.length() - stretch))
                        : read.substring(0, Math.min(read.length(), stretch)));
            }
        }
        return ends;
    }

    /** Where each open seed lies in its extended read: bases added before it and after it ({-1, -1}: not found). */
    static Map<String, int[]> sides(List<Seed> open, Map<String, String> seedBases, Map<String, String> extended) {
        Map<String, int[]> sides = new java.util.TreeMap<>();
        for (Seed seed : open) {
            String read = extended.get(seed.id());
            String bases = seedBases.get(seed.id());
            if (read == null || bases == null) {
                continue;
            }
            int left = read.indexOf(bases);
            sides.put(seed.id(), left < 0 ? new int[]{-1, -1} : new int[]{left, read.length() - left - bases.length()});
        }
        return sides;
    }

    /**
     * Writes the first and last {@link #END_LENGTH} bases of the extended reads whose ends grew since
     * {@code before} (both of a read whose seed was not found in it).
     *
     * @return whether any end grew
     */
    static boolean writeGrownEnds(Map<String, int[]> now, Map<String, int[]> before, Map<String, String> extended,
                                  Path ends) {
        boolean any = false;
        try (BufferedWriter writer = Files.newBufferedWriter(ends, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, int[]> entry : now.entrySet()) {
                int[] side = entry.getValue();
                int[] was = before.getOrDefault(entry.getKey(), new int[]{-1, -1});
                boolean unknown = side[0] < 0;
                String bases = extended.get(entry.getKey());
                if (unknown || side[0] > was[0]) {
                    writer.write(">" + entry.getKey() + "_start\n" + bases.substring(0, Math.min(END_LENGTH, bases.length())) + "\n");
                    any = true;
                }
                if (unknown || side[1] > was[1]) {
                    writer.write(">" + entry.getKey() + "_end\n" + bases.substring(Math.max(0, bases.length() - END_LENGTH)) + "\n");
                    any = true;
                }
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the ends of the extended reads: " + ends, e);
        }
        return any;
    }

    /** Writes the seeds {@code seeds} as FASTA, for a round of the extension. */
    static void writeSeedBases(List<Seed> seeds, Map<String, String> seedBases, Path file) {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Seed seed : seeds) {
                writer.write(">" + seed.id() + "\n" + seedBases.get(seed.id()) + "\n");
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the extension seeds: " + file, e);
        }
    }

    /** Writes the sample's extended reads and their accepted hits, kept in its folder. */
    static void writeExtended(Map<String, String> reads, Path extendedFile, List<String> hits, Path hitsFile) {
        try (BufferedWriter writer = Files.newBufferedWriter(extendedFile, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> read : reads.entrySet()) {
                writer.write(">" + read.getKey() + "\n" + read.getValue() + "\n");
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the extended reads: " + extendedFile, e);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(hitsFile, StandardCharsets.UTF_8)) {
            writer.write(BlastTsv.HEADER + "\n");
            for (String line : hits) {
                writer.write(line + "\n");
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the hits of the extended reads: " + hitsFile, e);
        }
    }

    static List<String> linesOf(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Checks every product that extended reads hold against the sample's
     * read pairs ({@link PairSupport}): a wrong turn through a repeat is closed from both
     * sides too, but no read pair of the sample spans it. The pairs are taken from the
     * sample's own reads, with BBDuk, not from the baited reads, which thin out away
     * from the primers.
     *
     * @param extendedFile the extended reads the products lie on
     * @param ends   the ends of extended reads a round baits from next, or {@code null}: baited in the same pass
     *               over the sample's reads as the candidates ({@link SampleFiles#extensionUnionReads}), which the
     *               round then takes its reads from
     * @param baited reads already baited with a reference holding every candidate here, or {@code null}: the
     *               candidates' reads are taken from them, as a pass over the sample's reads would give them
     */
    static Map<String, Outcome> checkAgainstReadPairs(Settings settings, Map<String, Outcome> outcomes,
                                                      Path extendedFile, SampleFiles files, Sample sample, Path ends,
                                                      Path baited) {
        List<Product> closed = new ArrayList<>();
        outcomes.values().forEach(outcome -> closed.addAll(outcome.closed()));
        if (closed.isEmpty()) {
            return outcomes;
        }
        String name = sample.getName();
        List<String> sequences = candidateSequences(closed, extendedFile);
        Path candidates = files.extensionCandidates();
        try (BufferedWriter writer = Files.newBufferedWriter(candidates, StandardCharsets.UTF_8)) {
            for (int i = 0; i < closed.size(); i++) {
                Product product = closed.get(i);
                writer.write(">" + product.assay() + "_" + product.size() + "bp_on_"
                        + product.row().contig()
                        + " best-supported path held by extended reads, not an assembled consensus");
                writer.newLine();
                writer.write(sequences.get(i));
                writer.newLine();
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the candidate products: " + candidates, e);
        }
        Path candidateReads = files.candidateReads();
        int k = ReadExtensionCaller.K;
        if (baited != null) {
            settings.bbtools().recruitFrom(candidates, baited, sample.isPaired(), candidateReads, k)
                    .run(settings.search().monitor());
        } else if (ends != null) {
            Path union = files.extensionUnionRef();
            try {
                Files.writeString(union, Files.readString(candidates) + Files.readString(ends));
            } catch (IOException e) {
                throw new PipelineException("Could not write the candidate products and ends: " + union, e);
            }
            settings.bbtools().recruit(union, sample.files(), files.extensionUnionReads(), k)
                    .run(settings.search().monitor());
            settings.bbtools().recruitFrom(candidates, files.extensionUnionReads(), sample.isPaired(), candidateReads,
                    k).run(settings.search().monitor());
        } else {
            settings.bbtools().recruit(candidates, sample.files(), candidateReads, k)
                    .run(settings.search().monitor());
        }
        PairSupport.Tally tally = tally(sequences, candidateReads, sample.isPaired(), settings);
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
        products.forEach(product -> wanted.add(product.row().contig()));
        Map<String, String> extended = readFasta(extendedFile, wanted);
        List<String> sequences = new ArrayList<>();
        for (Product product : products) {
            String read = extended.get(product.row().contig());
            sequences.add(read.substring(product.row().start() - 1, Math.min(read.length(), product.row().end())));
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
        String[] previous = {null};
        long[] records = {0};
        FastqRecords.forEach(reads, record -> {
            if (!paired) {
                tally.addRead(record.sequence());
            } else if (previous[0] == null) {
                previous[0] = record.sequence();
            } else {
                tally.addPair(previous[0], record.sequence());
                previous[0] = null;
            }
            if (++records[0] % 500_000 == 0 && settings != null) {
                stopIfCancelled(settings);
            }
        });
        return tally;
    }

    /** The given records of a FASTA file whose sequences may span several lines, by ID. */
    static Map<String, String> readFasta(Path fasta, Set<String> ids) {
        Map<String, StringBuilder> found = new HashMap<>();
        StringBuilder[] current = {null};
        try (FastaScanner scanner = new FastaScanner(List.of(fasta))) {
            String header;
            while ((header = scanner.nextHeader(base -> {
                if (current[0] != null) {
                    current[0].append((char) base);
                }
            })) != null) {
                String id = SequenceFileUtils.fastaId(header);
                current[0] = ids.contains(id) ? found.computeIfAbsent(id, ignored -> new StringBuilder()) : null;
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
    static Map<String, BaitedReads.ReadRecord> writeSeeds(List<Seed> seeds, Path baitedReadsFasta, Path seedsFile) {
        Set<String> wanted = new HashSet<>();
        seeds.forEach(seed -> wanted.add(seed.read()));
        Map<String, BaitedReads.ReadRecord> records = BaitedReads.records(baitedReadsFasta, wanted);
        try (BufferedWriter writer = Files.newBufferedWriter(seedsFile, StandardCharsets.UTF_8)) {
            for (Seed seed : seeds) {
                BaitedReads.ReadRecord record = records.get(seed.read());
                if (record == null) {
                    throw new PipelineException("read " + seed.read() + " is not in " + baitedReadsFasta.getFileName());
                }
                // The assay, the primer and the read it is seeded from, for whoever audits a call.
                writer.write(">" + seed.id() + " " + seed.assay() + "-" + seed.role().letter() + " " + seed.read());
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
     * The accepted primer hits on the extended reads of sample {@code name}.
     *
     * @param extendedFile the FASTA of extended reads BLAST searched: trimmed hit ends are
     *                     compared with it (see TrimmedEnds), as for contigs and reads
     */
    static Hits extendedHits(String name, Path hitsFile, Path extendedFile, Map<String, Integer> lengths,
                             PrimerPanel primers, AmpliconOptions options, Consumer<String> warning) {
        Hits extended = new Hits();
        lengths.keySet().forEach(id -> extended.describe(id, ""));
        String missing = BlastHits.missingWarning(
                BlastTsv.keepAccepted(hitsFile, List.of(extendedFile), primers, options, extended), List.of(extendedFile));
        if (!missing.isEmpty()) {
            warning.accept(missing);
        }
        return extended;
    }

    static void stopIfCancelled(Settings settings) {
        if (settings.search().monitor().isCancelled()) {
            throw new CancellationException("Read extension was cancelled.");
        }
    }

}
