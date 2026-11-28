package ca.canada.inspection.benchmark;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The benchmark's own rules: how a case is scored, how a baseline is compared, what the oracle says of a site. */
class ScorecardTest {

    @Test
    void aCaseIsScoredByItsSizes() {
        assertEquals(Scorecard.Result.FOUND, Scorecard.result(List.of(300), List.of(300), false));
        assertEquals(Scorecard.Result.MISSED, Scorecard.result(List.of(300), List.of(), false));
        assertEquals(Scorecard.Result.WRONG, Scorecard.result(List.of(300), List.of(301), false));
        assertEquals(Scorecard.Result.WRONG, Scorecard.result(List.of(300), List.of(300, 450), false));
        assertEquals(Scorecard.Result.ABSENT, Scorecard.result(List.of(), List.of(), false));
        assertEquals(Scorecard.Result.UNEXPECTED, Scorecard.result(List.of(), List.of(300), false));
        // Two copies are two products on an assembly, one call on reads.
        assertEquals(Scorecard.Result.WRONG, Scorecard.result(List.of(240, 240), List.of(240), false));
        assertEquals(Scorecard.Result.FOUND, Scorecard.result(List.of(240, 240), List.of(240), true));
    }

    @Test
    void aBaselineIsComparedCaseByCase() {
        Scorecard scorecard = scored(Map.of("kept", List.of(100), "lost", List.of(), "gained", List.of(100),
                "still", List.of(), "extra", List.of(100, 200)), "now said");
        Map<String, Scorecard.Kept> baseline = Map.of(
                "suite/kept", new Scorecard.Kept("FOUND", true, ""),
                "suite/lost", new Scorecard.Kept("FOUND", true, ""),
                "suite/gained", new Scorecard.Kept("MISSED", true, "then said"),
                "suite/still", new Scorecard.Kept("MISSED", true, "then said"),
                "suite/extra", new Scorecard.Kept("MISSED", true, "then said"),
                "suite/gone", new Scorecard.Kept("FOUND", true, ""),
                "suite/unlisted:scenario:sample:assay", new Scorecard.Kept("UNEXPECTED", true, ""),
                "other/case", new Scorecard.Kept("FOUND", true, ""));

        Scorecard.Difference difference = scorecard.compare(baseline);

        // Wrong in another way is worse too; what the run said, and a case no longer run, are changes.
        assertEquals(List.of("suite/extra", "suite/lost"), cases(difference.worse()));
        assertEquals(List.of("suite/gained", "suite/unlisted:scenario:sample:assay"), cases(difference.better()));
        assertEquals(List.of("suite/still", "suite/gone"), cases(difference.changed()));
        assertTrue(scorecard.compare(Scorecard.readBaselineText(scorecard.baseline())).none(),
                "a scorecard is the same as the baseline written from it");
    }

    @Test
    void theOracleJudgesAPlantedSiteByItsTmAndItsLastBases() {
        Random random = new Random(1);
        Assay assay = Assay.design(random, "a", 200);
        assertEquals(Rule.Verdict.ACCEPT, Planted.perfect(random, assay, Rule.DEFAULT).verdict());
        assertEquals(Rule.Verdict.REJECT, Planted.changed(random, assay, Planted.Sites.FORWARD,
                Planted.Kind.THREE_PRIME_END, 1, Rule.DEFAULT).verdict());
        assertEquals(Rule.Verdict.REJECT, Planted.changed(random, assay, Planted.Sites.BOTH,
                Planted.Kind.SUBSTITUTIONS, 8, Rule.DEFAULT).verdict());
        // The same site under a minimum no window reaches, one every window passes, and one between its windows.
        Planted changed = Planted.changed(random, assay, Planted.Sites.REVERSE, Planted.Kind.SUBSTITUTIONS, 1,
                new Rule(0, 0.5, 5));
        assertEquals(Rule.Verdict.ACCEPT, changed.verdict());
        String forward = assay.forward();
        Rule.Site site = Rule.DEFAULT.judge(forward, forward, "ACGT", "AC", true);
        assertTrue(site.lowest() <= site.tm() && site.tm() <= site.highest());
        assertEquals(Rule.Verdict.BORDERLINE, new Rule(site.tm(), 0.5, 5).judge(forward, forward, "ACGT", "AC", true)
                .verdict());
        assertEquals(Rule.Verdict.REJECT, new Rule(site.highest() + 1, 0.5, 5).judge(forward, forward, "ACGT", "AC",
                true).verdict());
        // A planted product is the assay's, between the bases written beside it.
        Planted perfect = Planted.perfect(random, assay, Rule.DEFAULT);
        assertEquals(assay.product(), perfect.template().substring(Planted.FLANK,
                perfect.template().length() - Planted.FLANK));
    }

    @Test
    void theSyntheticCasesAreTheSameEveryTime() {
        Scenario first = Suites.sites(Suites.Tier.CI);
        Scenario second = Suites.sites(Suites.Tier.CI);
        assertEquals(first.expectations, second.expectations);
        assertEquals(first.samples, second.samples);
        assertEquals(first.assays, second.assays);
    }

    /** A scorecard of one assembly scenario whose cases all expect a product of 100 bp, but {@code still} and {@code lost}. */
    private static Scorecard scored(Map<String, List<Integer>> observedSizes, String said) {
        Scenario scenario = new Scenario("suite", "scenario", 1);
        scenario.assembly("sample", List.of());
        StringBuilder report = new StringBuilder("Sample\tAssay\tAmpliconSize\n");
        StringBuilder diagnostics = new StringBuilder("Sample\tAssay\tStatus\n");
        for (String name : List.of("kept", "lost", "gained", "still", "extra")) {
            scenario.expect(name, "category", "sample", name, "", 100);
            observedSizes.get(name).forEach(size -> report.append("sample\t").append(name).append('\t').append(size)
                    .append('\n'));
            if (observedSizes.get(name).isEmpty()) {
                diagnostics.append("sample\t").append(name).append('\t').append(said).append('\n');
            }
        }
        Observed observed = Observed.ofText(report.toString(), "", "", diagnostics.toString());
        Scorecard scorecard = new Scorecard();
        scorecard.add(scenario, new App.Run(0, 1, Path.of("output"), Path.of("log")), observed);
        return scorecard;
    }

    /** The cases of difference lines, in their order: each line starts with its case and a colon or a space. */
    private static List<String> cases(List<String> lines) {
        return lines.stream().map(line -> line.startsWith("suite/unlisted:") ? line.substring(0, line.indexOf(' '))
                : line.split("[: ]")[0]).toList();
    }
}
