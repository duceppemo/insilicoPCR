package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.ReadExtensionCaller.Outcome;
import ca.canada.inspection.pcr.ReadExtensionCaller.Product;
import ca.canada.inspection.pcr.ReadExtensionCaller.Seed;
import ca.canada.inspection.pcr.ReadLevelCaller.ReadEvidence;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static ca.canada.inspection.pcr.TestHits.PRIMER_20;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadExtensionCallerTest {

    private static final AmpliconOptions DEFAULTS = AmpliconOptions.withMismatches(0);
    private static final Map<String, String> PRIMERS = Map.of("long-F", PRIMER_20, "long-R", PRIMER_20);
    private static final int REACH = 5000;
    private static final PairSupport.Verdict SUPPORTED = new PairSupport.Verdict(PairSupport.Kind.SUPPORTED, true,
            300, 9, 191, 14, 0, 0, 0, 0, 40);

    private static Set<String> readIds(int from, int count) {
        return IntStream.range(from, from + count).mapToObj(i -> "read_" + i).collect(Collectors.toSet());
    }

    /** Reads carrying each primer (never both), no read holding a complete amplicon. */
    private static ReadEvidence primersInReads(int forward, int reverse) {
        Map<String, Set<String>> ids = new HashMap<>();
        ids.put("long-F", readIds(0, forward));
        ids.put("long-R", readIds(1000, reverse));
        return new ReadEvidence(ids, Map.of(), Map.of("long", 280));
    }

    private static Sample fastqSample() {
        Sample sample = new Sample();
        sample.setName("S");
        sample.setFileType("fastq");
        return sample;
    }

    // --- which assays are extended ---

    @Test
    void assayInNoContigAndNoReadWithBothPrimersInReadsIsACandidate() {
        assertEquals(List.of("long"),
                ReadExtensionCaller.candidates(fastqSample(), PRIMERS, DEFAULTS, primersInReads(5, 4)));
    }

    @Test
    void assayTheAssemblyReportsIsLeftAlone() {
        Sample sample = fastqSample();
        sample.addBlastResult("long-F", TestHits.plusHit("long-F", "contig_0", 101));
        sample.addBlastResult("long-R", TestHits.minusHit("long-R", "contig_0", 281));

        assertTrue(ReadExtensionCaller.candidates(sample, PRIMERS, DEFAULTS, primersInReads(5, 4)).isEmpty());
    }

    @Test
    void assayWithACompleteAmpliconInSomeReadIsLeftToTheReadLevelRules() {
        // One read holds the product: too few to report it, and not for the extension to overrule.
        Sample reads = FastqDiagnosticsTest.readsSample();
        reads.addBlastResult("long-F", TestHits.plusHit("long-F", "read_1", 11));
        reads.addBlastResult("long-R", TestHits.minusHit("long-R", "read_1", 61));
        reads.addBlastResult("long-F", TestHits.plusHit("long-F", "read_2", 11));
        reads.addBlastResult("long-R", TestHits.minusHit("long-R", "read_3", 61));
        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101, "read_2", 101, "read_3", 101),
                PRIMERS, DEFAULTS);
        assertFalse(evidence.products("long").getFirst().isReportable());

        assertTrue(ReadExtensionCaller.candidates(fastqSample(), PRIMERS, DEFAULTS, evidence).isEmpty());
    }

    @Test
    void assayWithOnlyOnePrimerInReadsIsNotACandidate() {
        assertTrue(ReadExtensionCaller.candidates(fastqSample(), PRIMERS, DEFAULTS, primersInReads(5, 0)).isEmpty());
        assertTrue(ReadExtensionCaller.candidates(fastqSample(), PRIMERS, DEFAULTS, primersInReads(0, 5)).isEmpty());
    }

    // --- which assays, by mode ---

    private static final ExpectedSizes TARGET_1074 = ExpectedSizes.fromHeaders(Map.of("long-F", "size=1074"));

    private static List<String> candidates(Sample sample, ReadEvidence reads, ReadExtensionCaller.Mode mode,
                                           ExpectedSizes sizes) {
        return ReadExtensionCaller.candidates(sample, PRIMERS, DEFAULTS, reads, mode, sizes);
    }

    /** A sample whose mini-assembly reports the assay at 200 bp. */
    private static Sample assembledAt200() {
        Sample sample = fastqSample();
        sample.addBlastResult("long-F", TestHits.plusHit("long-F", "contig_0", 101));
        sample.addBlastResult("long-R", TestHits.minusHit("long-R", "contig_0", 281));
        return sample;
    }

    @Test
    void offExtendsNothing() {
        assertTrue(candidates(fastqSample(), primersInReads(5, 4), ReadExtensionCaller.Mode.OFF, TARGET_1074)
                .isEmpty());
    }

    @Test
    void targetModeAlsoExtendsAnAssayWithoutAProductOfItsDeclaredSize() {
        // The assembly reports 200 bp; the assay declares 1074 bp, which the sample does not show.
        ReadEvidence reads = primersInReads(5, 4);

        assertTrue(candidates(assembledAt200(), reads, ReadExtensionCaller.Mode.UNREPORTED, TARGET_1074).isEmpty());
        assertEquals(List.of("long"),
                candidates(assembledAt200(), reads, ReadExtensionCaller.Mode.TARGET, TARGET_1074));
        // Without a declared target there is nothing to look for; and both primers must still be in reads.
        assertTrue(candidates(assembledAt200(), reads, ReadExtensionCaller.Mode.TARGET, ExpectedSizes.NONE)
                .isEmpty());
        assertTrue(candidates(assembledAt200(), primersInReads(5, 0), ReadExtensionCaller.Mode.TARGET, TARGET_1074)
                .isEmpty());
        // An unreported assay is extended in target mode as in unreported mode, declared size or not.
        assertEquals(List.of("long"),
                candidates(fastqSample(), reads, ReadExtensionCaller.Mode.TARGET, ExpectedSizes.NONE));
    }

    @Test
    void targetModeLeavesAnAssayThatAlreadyShowsItsTarget() {
        ExpectedSizes target200 = ExpectedSizes.fromHeaders(Map.of("long-F", "size=200"));

        assertTrue(candidates(assembledAt200(), primersInReads(5, 4), ReadExtensionCaller.Mode.TARGET, target200)
                .isEmpty());
    }

    @Test
    void targetModeLeavesATargetSeenInTooFewReadsToTheReadLevelRules() {
        // One read holds a 70 bp product, the declared target: not reported, and not for the
        // extension to overrule. Declared at another size, the same assay is extended.
        Sample reads = FastqDiagnosticsTest.readsSample();
        reads.addBlastResult("long-F", TestHits.plusHit("long-F", "read_1", 11));
        reads.addBlastResult("long-R", TestHits.minusHit("long-R", "read_1", 61));
        reads.addBlastResult("long-F", TestHits.plusHit("long-F", "read_2", 11));
        reads.addBlastResult("long-R", TestHits.minusHit("long-R", "read_3", 61));
        ReadEvidence evidence = ReadLevelCaller.evaluate(reads, Map.of("read_1", 101, "read_2", 101, "read_3", 101),
                PRIMERS, DEFAULTS);
        ExpectedSizes target70 = ExpectedSizes.fromHeaders(Map.of("long-F", "size=70"));

        assertTrue(candidates(fastqSample(), evidence, ReadExtensionCaller.Mode.TARGET, target70).isEmpty());
        assertEquals(List.of("long"), candidates(fastqSample(), evidence, ReadExtensionCaller.Mode.TARGET, TARGET_1074));
        assertEquals(Map.of("long", Set.of(70)),
                ReadExtensionCaller.knownSizes(fastqSample(), PRIMERS, DEFAULTS, evidence));
    }

    @Test
    void modeIsParsedFromItsCommandLineValue() {
        assertEquals(ReadExtensionCaller.Mode.OFF, ReadExtensionCaller.Mode.parse("off"));
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, ReadExtensionCaller.Mode.parse(" Unreported "));
        assertEquals(ReadExtensionCaller.Mode.TARGET, ReadExtensionCaller.Mode.parse("target"));
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, ReadExtensionCaller.Mode.DEFAULT);
        assertEquals("target", ReadExtensionCaller.Mode.TARGET.toString());
        assertEquals("read extension must be off, unreported or target, got: on", org.junit.jupiter.api.Assertions
                .assertThrows(IllegalArgumentException.class, () -> ReadExtensionCaller.Mode.parse("on")).getMessage());
    }

    // --- seeds ---

    @Test
    void everyReadIsASeedWhenFewCarryThePrimer() {
        List<Seed> seeds = ReadExtensionCaller.seeds(List.of("long"), PRIMERS.keySet(), primersInReads(3, 2));

        assertEquals(List.of(new Seed("extended_read_0", "read_0", "long", 'F'),
                new Seed("extended_read_1", "read_1", "long", 'F'),
                new Seed("extended_read_2", "read_2", "long", 'F'),
                new Seed("extended_read_3", "read_1000", "long", 'R'),
                new Seed("extended_read_4", "read_1001", "long", 'R')), seeds);
    }

    @Test
    void seedsAreCappedPerPrimerAndSpreadOverTheReads() {
        List<Seed> seeds = ReadExtensionCaller.seeds(List.of("long"), PRIMERS.keySet(), primersInReads(600, 40));

        List<Seed> forward = seeds.stream().filter(seed -> seed.role() == 'F').toList();
        assertEquals(ReadExtensionCaller.MAX_SEEDS_PER_PRIMER, forward.size());
        assertEquals(40, seeds.size() - forward.size());
        // Every fourth of the 600 reads, in read order: not the first 150.
        assertEquals("read_0", forward.getFirst().read());
        assertEquals("read_4", forward.get(1).read());
        assertEquals("read_596", forward.getLast().read());
        assertEquals(seeds.size(), seeds.stream().map(Seed::id).distinct().count());
    }

    @Test
    void variantsOfADegeneratePrimerCountAsOnePrimer() {
        Map<String, String> primers = Map.of("long-F_1", PRIMER_20, "long-F_2", PRIMER_20, "long-R", PRIMER_20);
        Map<String, Set<String>> ids = new HashMap<>();
        ids.put("long-F_1", readIds(0, 120));
        Set<String> second = new HashSet<>(readIds(100, 120)); // 20 reads match both variants
        ids.put("long-F_2", second);
        ids.put("long-R", readIds(1000, 3));
        ReadEvidence evidence = new ReadEvidence(ids, Map.of(), Map.of());

        List<Seed> seeds = ReadExtensionCaller.seeds(List.of("long"), primers.keySet(), evidence);

        List<Seed> forward = seeds.stream().filter(seed -> seed.role() == 'F').toList();
        assertEquals(ReadExtensionCaller.MAX_SEEDS_PER_PRIMER, forward.size(), "220 distinct reads, capped once");
        assertEquals(forward.size(), forward.stream().map(Seed::read).distinct().count());
    }

    @Test
    void spreadSortsReadsByTheirNumber() {
        assertEquals(List.of("read_2", "read_10", "read_100"),
                ReadExtensionCaller.spread(Set.of("read_100", "read_2", "read_10"), 5));
        assertEquals(List.of("read_1", "read_3"),
                ReadExtensionCaller.spread(Set.of("read_1", "read_2", "read_3", "read_4"), 2));
    }

    // --- the call rule ---

    /** Seeds: {@code forward} forward-seeded reads then {@code reverse} reverse-seeded ones. */
    private static List<Seed> seeds(int forward, int reverse) {
        return ReadExtensionCaller.seeds(List.of("long"), PRIMERS.keySet(), primersInReads(forward, reverse));
    }

    private static Sample extended() {
        Sample sample = new Sample();
        sample.setName("S");
        sample.setFileType("reads");
        return sample;
    }

    /** A product of {@code size} bp on the extended read, starting at base 101. */
    private static void product(Sample extended, String read, int size) {
        extended.addBlastResult("long-F", TestHits.plusHit("long-F", read, 101));
        extended.addBlastResult("long-R", TestHits.minusHit("long-R", read, 101 + size - 20));
    }

    private static Map<String, Integer> lengths(List<Seed> seeds) {
        Map<String, Integer> lengths = new HashMap<>();
        seeds.forEach(seed -> lengths.put(seed.id(), 3000));
        return lengths;
    }

    private static Outcome evaluate(Sample extended, List<Seed> seeds) {
        return ReadExtensionCaller.evaluate(extended, lengths(seeds), seeds, PRIMERS, DEFAULTS, REACH).get("long");
    }

    @Test
    void productClosedFromBothPrimersIsCalled() {
        List<Seed> seeds = seeds(3, 3); // extended_read_0..2 forward-seeded, 3..5 reverse-seeded
        Sample extended = extended();
        for (int i : new int[]{0, 1, 3, 4, 5}) {
            product(extended, "extended_read_" + i, 1074);
        }

        Outcome outcome = evaluate(extended, seeds);

        assertEquals(3, outcome.forwardSeeds());
        assertEquals(3, outcome.reverseSeeds());
        assertEquals(REACH, outcome.reach());
        Product product = outcome.closed().getFirst();
        assertEquals(1074, product.size());
        assertEquals(2, product.forwardClosing());
        assertEquals(3, product.reverseClosing());
        assertEquals("2 of 3 forward-seeded and 3 of 3 reverse-seeded reads", product.supportSummary());
        assertEquals("extended_read_0", product.row().get(ConsolidatedReport.CONTIG_COLUMN));
        assertEquals("101-1174", product.row().get(ConsolidatedReport.LOCATION_COLUMN));
    }

    @Test
    void productClosedFromOneSideOnlyIsNotCalled() {
        // Three forward-seeded reads reach the reverse primer, but only one read extended
        // from the reverse primer comes back to the forward one: a wrong turn looks like this.
        List<Seed> seeds = seeds(3, 3);
        Sample extended = extended();
        for (int i : new int[]{0, 1, 2, 3}) {
            product(extended, "extended_read_" + i, 1303);
        }

        Outcome outcome = evaluate(extended, seeds);

        assertTrue(outcome.closed().isEmpty());
        Product product = outcome.products().getFirst();
        assertEquals(3, product.forwardClosing());
        assertEquals(1, product.reverseClosing());
        assertFalse(product.isReportable());
    }

    @Test
    void lengthVariantsAreOneProductReportedAtItsMostFrequentSize() {
        // 1074 bp from the forward side and 1072 bp from the reverse side, as two length
        // variants of one locus give: neither size alone is closed from both sides.
        List<Seed> seeds = seeds(3, 2);
        Sample extended = extended();
        product(extended, "extended_read_0", 1074);
        product(extended, "extended_read_1", 1074);
        product(extended, "extended_read_2", 1074);
        product(extended, "extended_read_3", 1072);
        product(extended, "extended_read_4", 1072);

        Product product = evaluate(extended, seeds).closed().getFirst();

        assertEquals(1074, product.size());
        assertEquals(Set.of(1072, 1074), product.sizes());
        assertEquals("1072,1074", product.sizeSummary());
        assertEquals(3, product.forwardClosing());
        assertEquals(2, product.reverseClosing());
        assertEquals("1074", product.row().get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));
    }

    @Test
    void sizesFurtherApartAreSeparateProductsEachNeedingBothSides() {
        List<Seed> seeds = seeds(4, 4);
        Sample extended = extended();
        for (int i : new int[]{0, 1, 4, 5}) {
            product(extended, "extended_read_" + i, 1074);
        }
        for (int i : new int[]{2, 3, 6, 7}) {
            product(extended, "extended_read_" + i, 1900);
        }
        product(extended, "extended_read_0", 400); // one read, one side

        Outcome outcome = evaluate(extended, seeds);

        assertEquals(List.of(400, 1074, 1900), outcome.products().stream().map(Product::size).toList());
        assertEquals(List.of(1074, 1900), outcome.closed().stream().map(Product::size).toList());
    }

    @Test
    void productTheSampleAlreadyShowsIsCalledButNotAddedAgain() {
        // Target mode: the sample reports 1900 bp on a contig; the extended reads hold that
        // product (1903 bp, a length variant) and a 1074 bp one. Only the second is new.
        List<Seed> seeds = seeds(4, 4);
        Sample extended = extended();
        for (int i : new int[]{0, 1, 4, 5}) {
            product(extended, "extended_read_" + i, 1074);
        }
        for (int i : new int[]{2, 3, 6, 7}) {
            product(extended, "extended_read_" + i, 1903);
        }

        Outcome outcome = ReadExtensionCaller.evaluate(extended, lengths(seeds), seeds, PRIMERS, DEFAULTS, REACH,
                Map.of("long", Set.of(1900))).get("long");

        assertEquals(List.of(1074, 1903), outcome.closed().stream().map(Product::size).toList());
        assertTrue(outcome.added().isEmpty(), "nothing is added before the read pairs have confirmed it");
        Outcome confirmed = outcome.withSupport(product -> SUPPORTED);
        assertEquals(List.of(1074, 1903), confirmed.called().stream().map(Product::size).toList());
        assertEquals(List.of(1074), confirmed.added().stream().map(Product::size).toList());
        assertEquals(Set.of(1900), confirmed.reported());
    }

    @Test
    void closedProductIsCalledOnlyWhenTheReadPairsSupportIt() {
        List<Seed> seeds = seeds(3, 3);
        Sample extended = extended();
        seeds.forEach(seed -> product(extended, seed.id(), 1074));
        Outcome unchecked = evaluate(extended, seeds);
        PairSupport.Verdict unsupported = new PairSupport.Verdict(PairSupport.Kind.UNSUPPORTED_WINDOW, true, 300, 0,
                204, 10, 0, 0, 0, 0, 25);

        assertEquals(1, unchecked.closed().size());
        assertTrue(unchecked.called().isEmpty(), "a product not checked against the read pairs is not called");
        assertEquals(1, unchecked.withSupport(product -> SUPPORTED).called().size());
        Outcome rejected = unchecked.withSupport(product -> unsupported);
        assertTrue(rejected.called().isEmpty());
        assertEquals(1, rejected.withheld().size());
        assertEquals(unsupported, rejected.withheld().getFirst().support());
        // A product held from one side only is not checked at all.
        Sample oneSided = extended();
        product(oneSided, "extended_read_0", 1074);
        product(oneSided, "extended_read_1", 1074);
        Outcome open = evaluate(oneSided, seeds).withSupport(product -> SUPPORTED);
        assertTrue(open.closed().isEmpty() && open.called().isEmpty() && open.withheld().isEmpty());
    }

    @Test
    void assayWithSeedsButNoProductHasAnEmptyOutcome() {
        List<Seed> seeds = seeds(5, 4);

        Outcome outcome = evaluate(extended(), seeds);

        assertTrue(outcome.products().isEmpty());
        assertEquals(5, outcome.forwardSeeds());
        assertEquals(4, outcome.reverseSeeds());
    }

    @Test
    void productHangingOffTheExtendedReadIsNotCounted() {
        // The reverse primer's last bases lie beyond the end of the extended read.
        List<Seed> seeds = seeds(2, 2);
        Sample extended = extended();
        Map<String, Integer> lengths = new HashMap<>();
        for (Seed seed : seeds) {
            product(extended, seed.id(), 1074);
            lengths.put(seed.id(), 1170); // product ends at 1174
        }

        Outcome outcome = ReadExtensionCaller.evaluate(extended, lengths, seeds, PRIMERS, DEFAULTS, REACH).get("long");

        assertTrue(outcome.products().isEmpty());
    }

    @Test
    void productOfAnotherAssayOnASeedIsIgnored() {
        Map<String, String> primers = Map.of("long-F", PRIMER_20, "long-R", PRIMER_20,
                "other-F", PRIMER_20, "other-R", PRIMER_20);
        List<Seed> seeds = seeds(2, 2);
        Sample extended = extended();
        for (Seed seed : seeds) {
            extended.addBlastResult("other-F", TestHits.plusHit("other-F", seed.id(), 101));
            extended.addBlastResult("other-R", TestHits.minusHit("other-R", seed.id(), 601));
        }

        Map<String, Outcome> outcomes = ReadExtensionCaller.evaluate(extended, lengths(seeds), seeds, primers,
                DEFAULTS, REACH);

        assertEquals(Set.of("long"), outcomes.keySet());
        assertTrue(outcomes.get("long").products().isEmpty());
    }

    @Test
    void probeAssayNeedsItsProbeInsideTheProduct() {
        Map<String, String> primers = Map.of("long-F", PRIMER_20, "long-R", PRIMER_20, "long-P", PRIMER_20);
        List<Seed> seeds = seeds(2, 2);
        Sample withoutProbe = extended();
        Sample withProbe = extended();
        for (Seed seed : seeds) {
            product(withoutProbe, seed.id(), 1074);
            product(withProbe, seed.id(), 1074);
            withProbe.addBlastResult("long-P", TestHits.plusHit("long-P", seed.id(), 500));
        }

        assertTrue(ReadExtensionCaller.evaluate(withoutProbe, lengths(seeds), seeds, primers, DEFAULTS, REACH)
                .get("long").products().isEmpty());
        Product product = ReadExtensionCaller.evaluate(withProbe, lengths(seeds), seeds, primers, DEFAULTS, REACH)
                .get("long").closed().getFirst();
        assertEquals("long-P", product.row().get(13));
    }

    // --- the report row ---

    @Test
    void reportRowSaysWhereTheCallComesFrom() {
        List<Seed> seeds = seeds(3, 2);
        Sample extended = extended();
        seeds.forEach(seed -> product(extended, seed.id(), seed.role() == 'F' ? 1074 : 1072));
        Product product = evaluate(extended, seeds).closed().getFirst();

        List<String> row = ReadExtensionCaller.reportRow(product, "ERR1.5 5/1");

        assertEquals(product.row().size(), row.size(), "same columns as any other row");
        assertEquals("S", row.getFirst());
        assertEquals("long", row.get(ConsolidatedReport.GENE_COLUMN));
        assertEquals("1074", row.get(ConsolidatedReport.AMPLICON_SIZE_COLUMN));
        assertEquals("FASTQ read-extension call: no read holds the product; 3 of 3 forward-seeded and 2 of 2"
                        + " reverse-seeded reads, extended through the baited reads (k=31), hold it at 1072,1074 bp;"
                        + " the extended read is the best-supported path, not an assembled consensus;"
                        + " representative extended from read ERR1.5 5/1",
                row.get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN));
        assertTrue(ReadExtensionCaller.reportRow(product, "").get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN)
                .endsWith("not an assembled consensus"));
        // Once checked, the row states the window the read pairs verified.
        assertTrue(ReadExtensionCaller.reportRow(product.withSupport(SUPPORTED), "")
                .get(ConsolidatedReport.CONTIG_DESCRIPTION_COLUMN).contains("hold it at 1072,1074 bp; every 300-base"
                        + " window held by at least 9 read pairs; the extended read is the best-supported path"));
    }
}
