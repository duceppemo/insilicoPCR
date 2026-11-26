package ca.canada.inspection.pcr;

import ca.canada.inspection.model.Hits;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a BLAST TSV of the primers ({@code BlastTsv.OUTFMT}) into a sample: the one loop for the
 * contigs of a sample, the baited reads and the extended reads. A hit whose ends BLAST trimmed is
 * compared with the sequence it lies on ({@link TrimmedEnds}), read from the FASTA files BLAST
 * searched, alongside the report.
 */
public final class BlastHits {

    private BlastHits() {
    }

    /**
     * Adds the accepted hits of {@code tsv} to {@code into}.
     *
     * @param searched the FASTA files BLAST searched, in which trimmed hit ends are looked up
     * @return the sequences of trimmed hits not found in {@code searched}; their trimmed bases
     *         are not compared (see {@link #missingWarning})
     * @throws UncheckedIOException when the report or a searched file cannot be read
     */
    public static Set<String> read(Path tsv, List<Path> searched, Hits into, PrimerPanel primers,
                                   AmpliconOptions options) {
        // Decoded leniently, as the searched FASTA is (SequenceFileUtils.openMaybeGzip): a sequence ID with a
        // byte that is not UTF-8 reads the same way in both, instead of stopping the run here.
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(tsv),
                StandardCharsets.UTF_8))) {
            return read(reader, searched, into, primers, options, java.util.function.UnaryOperator.identity(), line -> {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read BLAST report: " + tsv, e);
        }
    }

    /**
     * Adds the accepted hits of {@code lines}, blastn's table, to {@code into}, and gives each one's line,
     * put back on the sequence searched by {@code toContig} first, to {@code accepted}: the one loop that
     * judges hits, as a table is filtered and its hits taken in one pass.
     *
     * @param toContig a line as on the sequence searched, or {@code null} for a line to pass over (a query
     *                 searched in pieces: the hit of a piece that does not own it)
     * @return the sequences of trimmed hits not found in {@code searched} ({@link #missingWarning})
     */
    public static Set<String> read(BufferedReader lines, List<Path> searched, Hits into, PrimerPanel primers,
                                   AmpliconOptions options, java.util.function.UnaryOperator<String> toContig,
                                   java.util.function.Consumer<String> accepted) throws IOException {
        return read(lines, searched, into, primers, options, toContig, accepted, false);
    }

    /**
     * @param keepCutByEnd whether to keep, apart, the primer hits rejected only because their 3' end runs off a
     *                     contig ({@link BlastHitParser#cutByQueryEnd}): an assembly's, for the split warning; not
     *                     reads', every one of which ends somewhere
     */
    public static Set<String> read(BufferedReader lines, List<Path> searched, Hits into, PrimerPanel primers,
                                   AmpliconOptions options, java.util.function.UnaryOperator<String> toContig,
                                   java.util.function.Consumer<String> accepted, boolean keepCutByEnd)
            throws IOException {
        try (QueryTemplates templates = new QueryTemplates(searched, into.circles())) {
            return readSites(lines, templates, into, primers, options, toContig, accepted, keepCutByEnd);
        }
    }

    /** Hits whose Tm is being computed, held back to be added in the order of the report. */
    private static final int TM_QUEUE = 8192;

    /** The pools that compute site Tms, by size: one per {@code -t}, shared by the samples read at once. */
    private static final Map<Integer, java.util.concurrent.ForkJoinPool> TM_POOLS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Threads for site Tms, the run's {@code -t}; 0 for every core. */
    private static volatile int tmThreads;

    /**
     * Computes site Tms on {@code threads} threads (the run's {@code -t}) from now on, every sample read at once
     * sharing them; 0 for every core.
     */
    public static void tmThreads(int threads) {
        if (threads < 0) {
            throw new IllegalArgumentException("threads must be >= 0: " + threads);
        }
        tmThreads = threads;
    }

    private static java.util.concurrent.ExecutorService tmPool() {
        int threads = tmThreads;
        return threads == 0 ? java.util.concurrent.ForkJoinPool.commonPool()
                : TM_POOLS.computeIfAbsent(threads, java.util.concurrent.ForkJoinPool::new);
    }

    private record Waiting(BlastHitParser.Pending site, String line, java.util.concurrent.Future<Double> tm) {
    }

    /**
     * {@link #read}: the hits that pass the 3' rule are scored in parallel (a thermodynamic alignment each, and an
     * assembly can have millions of BLAST hits), and added in the order of the report.
     */
    private static Set<String> readSites(BufferedReader lines, QueryTemplates templates, Hits into,
                                         PrimerPanel primers, AmpliconOptions options,
                                         java.util.function.UnaryOperator<String> toContig,
                                         java.util.function.Consumer<String> accepted, boolean keepCutByEnd)
            throws IOException {
        java.util.ArrayDeque<Waiting> waiting = new java.util.ArrayDeque<>();
        java.util.concurrent.ExecutorService pool = tmPool();
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isBlank() || line.equals(BlastField.HEADER) || (line = toContig.apply(line)) == null) {
                continue;
            }
            // Read once, for its site and for the contig-end check below.
            java.util.Optional<ca.canada.inspection.model.BlastResult> found = BlastHitParser.hit(line, primers);
            if (found.isPresent()) {
                BlastHitParser.Pending site = BlastHitParser.site(found.get(), options, templates);
                if (!site.primes()) {
                    // No Tm: it would not decide, and millions of short hits fail the 3' rule.
                    into.nearMiss(site.hit().subjectID(), ca.canada.inspection.model.NearMiss.failingThreePrimeRule(site.hit(), site.threePrimePenalty(), options.tmGate().minimumTm(site.hit().subjectID())));
                } else {
                    waiting.add(new Waiting(site, line, pool.submit(() -> site.tm(options.tmGate()))));
                    if (waiting.size() >= TM_QUEUE) {
                        drain(waiting, TM_QUEUE / 2, into, accepted, options.tmGate());
                    }
                }
            }
            // Present only for a hit the gate rejects (see cutByQueryEnd), and cheap for the others: decided here,
            // while the template of its query is at hand.
            if (keepCutByEnd && found.isPresent()) {
                BlastHitParser.cutByQueryEnd(found.get(), options, templates)
                        .ifPresent(cut -> into.addCutByEnd(cut.subjectID(), cut));
            }
        }
        drain(waiting, waiting.size(), into, accepted, options.tmGate());
        return templates.missing();
    }

    private static void drain(java.util.ArrayDeque<Waiting> waiting, int count, Hits into,
                              java.util.function.Consumer<String> accepted, TmGate gate) {
        for (int i = 0; i < count; i++) {
            Waiting next = waiting.remove(); // count is at most its size
            double tm;
            try {
                tm = next.tm().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while computing primer-site Tm", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException("Could not compute a primer-site Tm: " + e.getCause(), e.getCause());
            }
            BlastHitParser.Pending site = next.site();
            java.util.Optional<ca.canada.inspection.model.BlastResult> hit = site.acceptedHit(gate, tm);
            if (hit.isPresent()) {
                into.add(site.hit().subjectID(), hit.get());
                accepted.accept(next.line());
            } else {
                into.nearMiss(site.hit().subjectID(), ca.canada.inspection.model.NearMiss.of(site.hit(), tm,
                        site.threePrimePenalty(), gate.minimumTm(site.hit().subjectID())));
            }
        }
    }

    /** The warning for {@link #read}'s missing sequences; empty when none is missing. */
    public static String missingWarning(Set<String> missing, List<Path> searched) {
        if (missing.isEmpty()) {
            return "";
        }
        return "Warning: " + missing.size() + " sequences with a trimmed primer or probe hit were not found in "
                + searched + " (e.g. " + java.util.Collections.min(missing)
                + "); the trimmed bases of their hits are not compared.";
    }
}
