package ca.canada.inspection.pipeline;

import ca.canada.inspection.model.Hits;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadExtensionCaller.Outcome;
import ca.canada.inspection.pcr.ReadExtensionCaller.Seed;
import ca.canada.inspection.util.OutputFiles;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rounds of one sample's read extension ({@link ReadExtensionStage}). The baited reads run out about
 * a fragment from each primer, and a product longer than what they cover never closed, however deep the
 * sample (before 0.15.3: 1.5 to 2 kb). Each round extends the seeds of the assays not closed yet through
 * every read baited so far, each read once ({@link ReadPool}), checks the products they close against the
 * sample's read pairs, then baits the sample's reads again from the ends that grew; until each assay
 * closes, no end grows, no read is new, or {@link ReadExtensionStage#MAX_ROUNDS}. An assay done in a round
 * keeps that round's extended reads and outcome; a round that fails keeps what the rounds before it found.
 */
final class ExtensionRounds {
    private final ReadExtensionStage.Settings settings;
    private final SampleFiles files;
    private final Sample sample;
    private final String name;
    private final PrimerPanel primers;
    private final AmpliconOptions options;
    private final List<Seed> seeds;
    private final Map<String, String> seedBases;
    private final Map<String, Set<Integer>> known;
    private final int reach;
    private final List<String> notes;
    private final Path baitedReads;

    /** The reads extended through: the baited reads, then those the rounds baited. */
    private final List<Path> pool;
    /** Built when a round first baits new reads: 27 s on 9.7 million baited reads, for a sample whose assays
     *  all closed in round 1, which never used it. */
    private ReadPool pooled;
    /** The assays done, with their outcome, extended reads and hits as of the round they were done in. */
    private final Map<String, Outcome> settled = new TreeMap<>();
    private final Map<String, String> settledReads = new LinkedHashMap<>();
    private final List<String> settledHits = new ArrayList<>();
    /** The last round done: its outcomes, extended reads and hits. */
    private Map<String, Outcome> latest = Map.of();
    private Map<String, String> latestReads = Map.of();
    private List<String> latestHits = List.of();
    /** The hits of the last round extended (also of one that failed after its BLAST search). */
    private Hits roundHits = new Hits();
    /**
     * Whether {@link SampleFiles#candidateReads} holds the reads checked in the round of {@link #latest} (a check
     * that failed part way may have cut it short): the check after the rounds then takes its reads from them.
     */
    private boolean latestChecked;
    /** How far each seed's extended read reached either side of it, as of the last round's bait. */
    private Map<String, int[]> sides = Map.of();
    /** The seeds of the assays not done yet. */
    private List<Seed> open;

    /** One round's extension: the extended reads' lengths, and each open assay's outcome. */
    private record Round(Map<String, Integer> lengths, Map<String, Outcome> outcomes) {
    }

    ExtensionRounds(ReadExtensionStage.Settings settings, SampleFiles files, Sample sample, PrimerPanel primers,
                    AmpliconOptions options, List<Seed> seeds, Map<String, String> seedBases,
                    Map<String, Set<Integer>> known, int reach, List<String> notes) {
        this.settings = settings;
        this.files = files;
        this.sample = sample;
        this.name = sample.getName();
        this.primers = primers;
        this.options = options;
        this.seeds = seeds;
        this.seedBases = seedBases;
        this.known = known;
        this.reach = reach;
        this.notes = notes;
        this.baitedReads = files.doubleTargetMatches();
        this.pool = new ArrayList<>(List.of(baitedReads));
        this.open = seeds;
    }

    /** Runs the rounds, then checks what is not done yet: each assay's outcome, by assay. */
    Map<String, Outcome> run() {
        for (int round = 1; ; round++) {
            ReadExtensionStage.stopIfCancelled(settings);
            Round extended;
            try {
                extended = extend();
            } catch (PipelineException | UncheckedIOException e) {
                stoppedAt(round, e);
                break;
            }
            Map<String, Outcome> closedNow = new LinkedHashMap<>();
            extended.outcomes().forEach((assay, outcome) -> {
                if (!outcome.closed().isEmpty()) {
                    closedNow.put(assay, outcome);
                }
            });
            Map<String, String> roundReads = ReadExtensionStage.readFasta(files.extendedReads(),
                    extended.lengths().keySet());
            Map<String, Outcome> checkedNow;
            try {
                checkedNow = check(round, closedNow, roundReads);
            } catch (PipelineException | UncheckedIOException e) {
                // As a round that fails before it (it lost the sample's whole extension, the rounds before it too).
                stoppedAt(round, e);
                break;
            }
            latest = extended.outcomes();
            latestReads = roundReads;
            latestHits = Files.isRegularFile(files.extendedReadsHits())
                    ? ReadExtensionStage.linesOf(files.extendedReadsHits()) : List.of();
            if (!closedNow.isEmpty()) {
                latestChecked = true;
            }
            settle(extended.outcomes(), checkedNow);
            if (open.isEmpty() || round == ReadExtensionStage.MAX_ROUNDS) {
                break;
            }
            // A failure here keeps what the rounds so far found.
            try {
                if (!baitNextRound()) {
                    break;
                }
            } catch (PipelineException | UncheckedIOException e) {
                ReadExtensionStage.stopIfCancelled(settings);
                notes.add("read extension of sample " + name + " stopped after round " + round + " ("
                        + e.getMessage() + "); the products of the rounds so far are kept");
                break;
            }
        }
        OutputFiles.deleteQuietly(files.extensionRoundSeeds());
        return finish();
    }

    /** Round {@code round} failed: in round 1 the sample's extension fails; later, with a note, it keeps the rest. */
    private void stoppedAt(int round, RuntimeException e) {
        if (round == 1) {
            throw e;
        }
        ReadExtensionStage.stopIfCancelled(settings);
        notes.add("read extension of sample " + name + " stopped at round " + round + " (" + e.getMessage()
                + "); the products of the rounds before are kept");
    }

    /** Extends the open seeds through the pool, and searches the extended reads for the primers. */
    private Round extend() {
        Path roundSeeds = files.extensionRoundSeeds();
        Path extendedFile = files.extendedReads();
        ReadExtensionStage.writeSeedBases(open, seedBases, roundSeeds);
        settings.bbtools().extend(roundSeeds, pool, extendedFile, ReadExtensionCaller.K, reach)
                .run(settings.search().monitor());
        if (!Files.isRegularFile(extendedFile)) {
            throw new PipelineException("Tadpole wrote no extended reads");
        }
        Map<String, Integer> lengths = SequenceFileUtils.sequenceLengths(extendedFile);
        ReadLevelStage.runBlast(settings.search(), settings.search().threads(), extendedFile, files.extendedReadsHits());
        roundHits = ReadExtensionStage.extendedHits(name, files.extendedReadsHits(), extendedFile, lengths, primers,
                options, settings.search().monitor()::warning);
        return new Round(lengths, ReadExtensionCaller.evaluate(name, roundHits, lengths, open, primers, options, reach,
                known));
    }

    /**
     * Checks the products closed this round against the sample's read pairs. The ends every open seed grew are
     * written first: those of the seeds still open after the check are among them, and are baited in the same
     * pass over the sample's reads as the candidate products (each pass reads files of gigabytes; two a round
     * took 70 of S3's 77 s).
     */
    private Map<String, Outcome> check(int round, Map<String, Outcome> closedNow, Map<String, String> roundReads) {
        OutputFiles.deleteQuietly(files.extensionUnionReads());
        if (closedNow.isEmpty()) {
            return Map.of();
        }
        Path ends = null;
        if (round < ReadExtensionStage.MAX_ROUNDS && ReadExtensionStage.writeGrownEnds(
                ReadExtensionStage.sides(open, seedBases, roundReads), sides, roundReads, files.extensionEnds())) {
            ends = files.extensionEnds();
        }
        latestChecked = false; // the check rewrites the file; it holds the round's reads once it is done
        return ReadExtensionStage.checkAgainstReadPairs(settings, closedNow, files.extendedReads(), files, sample,
                ends, null);
    }

    /**
     * Settles the assays done this round, with this round's extended reads and outcome: those with a closed
     * product the read pairs support, and those whose extended reads loop, towards the product, in a tandem
     * repeat they cannot cross (they take no more rounds). A closed product the pairs do not support (a wrong
     * turn, closed from one side) leaves its assay open: settled on it, the real product was not looked for.
     */
    private void settle(Map<String, Outcome> outcomes, Map<String, Outcome> checkedNow) {
        Set<String> doneNow = new HashSet<>();
        for (Map.Entry<String, Outcome> entry : outcomes.entrySet()) {
            String assay = entry.getKey();
            Outcome checked = checkedNow.get(assay);
            if (checked != null && !checked.called().isEmpty()) {
                settled.put(assay, checked);
                doneNow.add(assay);
                continue;
            }
            String repeat = entry.getValue().closed().isEmpty()
                    ? ReadExtensionCaller.tandemRepeat(ReadExtensionStage.innerEnds(assay, open, latestReads, roundHits,
                    primers)) : null;
            if (repeat != null) {
                settled.put(assay, entry.getValue().withTandemRepeat(repeat));
                doneNow.add(assay);
            }
        }
        Set<String> doneSeeds = new HashSet<>();
        open.stream().filter(seed -> doneNow.contains(seed.assay())).forEach(seed -> doneSeeds.add(seed.id()));
        for (String id : doneSeeds) {
            String read = latestReads.get(id);
            if (read != null) {
                settledReads.put(id, read);
            }
        }
        latestHits.stream().filter(line -> doneSeeds.contains(line.substring(0, Math.max(0, line.indexOf('\t')))))
                .forEach(settledHits::add);
        open = open.stream().filter(seed -> !doneNow.contains(seed.assay())).toList();
    }

    /**
     * Baits the sample's reads again from the ends of the open seeds' extended reads that grew this round (an
     * end that did not, baited again, brings back the reads it brought before), and adds the new ones to the
     * pool.
     *
     * @return whether the next round has anything new to extend through
     */
    private boolean baitNextRound() {
        Map<String, int[]> now = ReadExtensionStage.sides(open, seedBases, latestReads);
        if (!ReadExtensionStage.writeGrownEnds(now, sides, latestReads, files.extensionEnds())) {
            return false;
        }
        sides = now;
        // From this round's pass, when it baited from these ends already; else a pass of their own.
        if (Files.isRegularFile(files.extensionUnionReads())) {
            settings.bbtools().recruitFrom(files.extensionEnds(), files.extensionUnionReads(), sample.isPaired(),
                    files.extensionRoundReads(), ReadExtensionCaller.K).run(settings.search().monitor());
        } else {
            settings.bbtools().recruit(files.extensionEnds(), sample.files(), files.extensionRoundReads(),
                    ReadExtensionCaller.K).run(settings.search().monitor());
        }
        if (pooled == null) {
            pooled = ReadPool.of(baitedReads);
        }
        int added = pooled.addNew(files.extensionRoundReads(), files.extensionReads());
        OutputFiles.deleteQuietly(files.extensionRoundReads());
        if (added == 0) {
            return false; // no read the extension has not had
        }
        if (pool.size() == 1) {
            pool.add(files.extensionReads());
        }
        return true;
    }

    /**
     * Writes the sample's extended reads and their hits (those of each assay as of the round it was done in,
     * the others' as of the last round), and checks the assays not done yet against the read pairs.
     */
    private Map<String, Outcome> finish() {
        Map<String, Outcome> unsettled = new LinkedHashMap<>(latest);
        unsettled.keySet().removeAll(settled.keySet());
        Map<String, String> finalReads = new TreeMap<>(latestReads);
        finalReads.putAll(settledReads);
        // What the extended reads of an assay still open ran into, towards the product, for its diagnostics.
        for (Map.Entry<String, Outcome> entry : unsettled.entrySet()) {
            if (entry.getValue().closed().isEmpty()) {
                String repeat = ReadExtensionCaller.tandemRepeat(ReadExtensionStage.innerEnds(entry.getKey(), seeds,
                        latestReads, roundHits, primers));
                if (repeat != null) {
                    entry.setValue(entry.getValue().withTandemRepeat(repeat));
                }
            }
        }
        List<String> finalHits = new ArrayList<>(settledHits);
        latestHits.stream().filter(line -> !line.equals(BlastTsv.HEADER) && !settledReads.containsKey(
                line.substring(0, Math.max(0, line.indexOf('\t'))))).forEach(finalHits::add);
        ReadExtensionStage.writeExtended(finalReads, files.extendedReads(), finalHits, files.extendedReadsHits());

        // The assays settled on a supported product were checked in their round; the others are checked now, from
        // the reads their round's check baited (with more candidates: the same reads as a pass of their own).
        Path baited = null;
        boolean anyClosed = unsettled.values().stream().anyMatch(outcome -> !outcome.closed().isEmpty());
        if (anyClosed && latestChecked && Files.isRegularFile(files.candidateReads())) {
            try {
                baited = Files.move(files.candidateReads(), files.extensionUnionReads(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                baited = null; // a pass over the sample's reads, as before
            }
        }
        Map<String, Outcome> sampleOutcomes = new TreeMap<>(ReadExtensionStage.checkAgainstReadPairs(settings,
                unsettled, files.extendedReads(), files, sample, null, baited));
        OutputFiles.deleteQuietly(files.extensionUnionReads());
        OutputFiles.deleteQuietly(files.extensionUnionRef());
        sampleOutcomes.putAll(settled);
        return sampleOutcomes;
    }
}
