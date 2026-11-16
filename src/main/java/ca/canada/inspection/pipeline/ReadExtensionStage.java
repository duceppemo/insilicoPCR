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
import java.util.Comparator;
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
                extendSample(settings, directories.sample(sample.getName()), sample, primers, options, reads,
                        outcomes, rows);
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
                                     Map<String, List<ReportRow>> rows) {
        String name = sample.getName();
        Path seedsFile = files.extensionSeeds();
        Path extendedFile = files.extendedReads();
        Path hitsFile = files.extendedReadsHits();
        // No stale files from an earlier run in this folder.
        deleteQuietly(seedsFile);
        deleteQuietly(extendedFile);
        deleteQuietly(hitsFile);
        deleteQuietly(files.extensionCandidates());
        deleteQuietly(files.candidateReads());
        deleteQuietly(files.extensionEnds());
        deleteQuietly(files.extensionRoundReads());
        deleteQuietly(files.extensionReads());

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

        // In rounds: the baited reads run out about a fragment from each primer, and a product longer than
        // what they cover never closed, however deep the sample (before 0.15.3: 1.5 to 2 kb). Each round baits
        // the sample's reads again from the ends of the extended reads of the assays not yet closed, and
        // extends the seeds again through every read baited so far, until each assay closes, the extended
        // reads stop growing, or MAX_ROUNDS.
        List<Path> pool = new ArrayList<>(List.of(baitedReads));
        Map<String, Integer> grown = Map.of();
        Map<String, Integer> lengths;
        Map<String, Outcome> evaluated;
        for (int round = 1; ; round++) {
            stopIfCancelled(settings);
            settings.bbtools().extend(seedsFile, pool, extendedFile, ReadExtensionCaller.K, reach)
                    .run(settings.search().monitor(), "Tadpole");
            if (!Files.isRegularFile(extendedFile)) {
                throw new PipelineException("Tadpole wrote no extended reads");
            }
            lengths = SequenceFileUtils.sequenceLengths(extendedFile);
            ReadLevelStage.runBlast(settings.search(), settings.search().threads(), extendedFile, hitsFile);
            BlastTsv.keepAccepted(hitsFile, List.of(extendedFile), primers, options);
            evaluated = ReadExtensionCaller.evaluate(name, extendedHits(name, hitsFile, extendedFile, lengths, primers,
                    options, settings.search().monitor()::warning), lengths, seeds, primers, options, reach, known);
            Map<String, Integer> open = openSeedLengths(assays, seeds, evaluated, lengths);
            if (open.isEmpty() || round == MAX_ROUNDS || !grewSince(open, grown)) {
                break;
            }
            grown = open;
            writeEnds(extendedFile, open.keySet(), files.extensionEnds());
            settings.bbtools().recruit(files.extensionEnds(), sample.files(), files.extensionRoundReads(),
                    ReadExtensionCaller.K).run(settings.search().monitor(), "BBDuk");
            appendTo(files.extensionRoundReads(), files.extensionReads());
            deleteQuietly(files.extensionRoundReads());
            if (pool.size() == 1) {
                pool.add(files.extensionReads());
            }
        }

        Map<String, Outcome> sampleOutcomes = checkAgainstReadPairs(settings, evaluated, extendedFile, files, sample);
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
     * The extended reads' lengths, by seed, of the assays of {@code assays} with no closed product in
     * {@code outcomes}: those another round may close.
     */
    static Map<String, Integer> openSeedLengths(List<String> assays, List<Seed> seeds, Map<String, Outcome> outcomes,
                                                Map<String, Integer> lengths) {
        Set<String> open = new HashSet<>();
        for (String assay : assays) {
            Outcome outcome = outcomes.get(assay);
            if (outcome == null || outcome.closed().isEmpty()) {
                open.add(assay);
            }
        }
        Map<String, Integer> openLengths = new java.util.TreeMap<>();
        for (Seed seed : seeds) {
            Integer length = lengths.get(seed.id());
            if (open.contains(seed.assay()) && length != null) {
                openLengths.put(seed.id(), length);
            }
        }
        return openLengths;
    }

    /** Whether an extended read of {@code now} is longer than it was in {@code before} (or is new). */
    static boolean grewSince(Map<String, Integer> now, Map<String, Integer> before) {
        return now.entrySet().stream().anyMatch(entry -> entry.getValue() > before.getOrDefault(entry.getKey(), 0));
    }

    /** Writes the first and last {@link #END_LENGTH} bases of the extended reads {@code ids} as FASTA. */
    private static void writeEnds(Path extendedFile, Set<String> ids, Path ends) {
        Map<String, String> extended = readFasta(extendedFile, ids);
        try (BufferedWriter writer = Files.newBufferedWriter(ends, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> read : extended.entrySet()) {
                String bases = read.getValue();
                writer.write(">" + read.getKey() + "_start\n" + bases.substring(0, Math.min(END_LENGTH, bases.length()))
                        + "\n>" + read.getKey() + "_end\n" + bases.substring(Math.max(0, bases.length() - END_LENGTH))
                        + "\n");
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write the ends of the extended reads: " + ends, e);
        }
    }

    /** Appends {@code part}, a gzipped file, to {@code whole}: two gzip files one after another are one. */
    private static void appendTo(Path part, Path whole) {
        if (!Files.isRegularFile(part)) {
            return;
        }
        try (java.io.OutputStream out = Files.newOutputStream(whole, java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND)) {
            Files.copy(part, out);
        } catch (IOException e) {
            throw new PipelineException("Could not keep the reads baited from the extended reads: " + whole, e);
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
                                                      Path extendedFile, SampleFiles files, Sample sample) {
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
        settings.bbtools().recruit(candidates, sample.files(), candidateReads, ReadExtensionCaller.K)
                .run(settings.search().monitor(), "BBDuk");
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
                BlastHits.read(hitsFile, List.of(extendedFile), extended, primers, options), List.of(extendedFile));
        if (!missing.isEmpty()) {
            warning.accept(missing);
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
