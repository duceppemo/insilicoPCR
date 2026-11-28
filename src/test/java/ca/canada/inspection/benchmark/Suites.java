package ca.canada.inspection.benchmark;

import ca.canada.inspection.benchmark.Scenario.Contig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The synthetic cases: samples the benchmark writes from seeds, whose products are known because it put them there.
 *
 * <ul>
 *   <li>{@code sites}: is a primer site the rule accepts found, and one it rejects left out? One changed site for
 *   each assay, in one chromosome.</li>
 *   <li>{@code geometry}: perfect sites, arranged every way the documentation describes (sizes, strands, copies,
 *   contig breaks, circles, probes, one primer alone, two assays of a reaction, degenerate bases, {@code ta=}).</li>
 *   <li>{@code reads}: the same products looked for in simulated read pairs, by product length, by how the sites
 *   differ from the primers, with repeats inside the product; and in the same chromosome as an assembly.</li>
 * </ul>
 *
 * <p>Changing a seed, a count or the order of the random draws changes every case: the baseline must then be made
 * again, and no longer compares with earlier ones.</p>
 */
final class Suites {

    enum Tier {
        /** A few cases of each kind, one sequencing run: a minute or two, run by the tests on every build. */
        CI(10, 2, 300_000, new int[]{100, 250, 450, 1500}, new int[]{300}, List.of(ReadSimulator.PE150)),
        /** The same kinds on three sequencing runs, with longer products: minutes, run before a change is merged. */
        STANDARD(10, 3, 300_000, new int[]{100, 250, 450, 800, 1500, 3000, 4500}, new int[]{150, 300, 600},
                List.of(ReadSimulator.PE150, ReadSimulator.PE150_LOW, ReadSimulator.PE100_SHORT)),
        /** Enough cases of each kind for rates, in a chromosome of 3 Mb: run by hand. */
        FULL(60, 12, 3_000_000, new int[]{100, 250, 450, 800, 1500, 3000, 4500}, new int[]{150, 300, 600},
                List.of(ReadSimulator.PE150, ReadSimulator.PE150_LOW, ReadSimulator.PE100_SHORT));

        /** Assays for each kind of site change. */
        final int perSiteKind;
        /** Assays for each kind of product in reads. */
        final int perReadKind;
        /** Bases of the chromosome the read cases lie in. */
        final int chromosome;
        /** Product lengths looked for in reads, bp. */
        final int[] readLengths;
        /** Lengths of the repeats inside a product looked for in reads, bp. */
        final int[] repeats;
        /** The sequencing runs simulated. */
        final List<ReadSimulator.Profile> profiles;

        Tier(int perSiteKind, int perReadKind, int chromosome, int[] readLengths, int[] repeats,
             List<ReadSimulator.Profile> profiles) {
            this.perSiteKind = perSiteKind;
            this.perReadKind = perReadKind;
            this.chromosome = chromosome;
            this.readLengths = readLengths;
            this.repeats = repeats;
            this.profiles = profiles;
        }
    }

    private static final Rule RULE = Rule.DEFAULT;

    private Suites() {
    }

    /** The scenarios of {@code suite} ({@code sites}, {@code geometry}, {@code reads}), or of every suite for {@code all}. */
    static List<Scenario> of(Tier tier, String suite) {
        List<Scenario> scenarios = new ArrayList<>();
        if (suite.equals("all") || suite.equals("sites")) {
            scenarios.add(sites(tier));
        }
        if (suite.equals("all") || suite.equals("geometry")) {
            scenarios.add(geometry(tier));
        }
        if (suite.equals("all") || suite.equals("reads")) {
            scenarios.addAll(reads(tier));
        }
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("No suite named " + suite + ": sites, geometry, reads or all");
        }
        return scenarios;
    }

    // ---- sites ----

    /**
     * @param hot primers of 24 to 28 bases melting at 66 to 72 °C, whose sites still melt above the minimum with
     *            several mismatches; otherwise 20 to 24 bases at 60 to 66 °C
     */
    private record SiteKind(String id, String category, Planted.Kind kind, int count, boolean hot, boolean both) {
        SiteKind(String id, String category, Planted.Kind kind, int count) {
            this(id, category, kind, count, false, false);
        }
    }

    private static final List<SiteKind> SITE_KINDS = List.of(
            new SiteKind("perfect", "perfect sites", null, 0),
            new SiteKind("sub1", "1 substitution", Planted.Kind.SUBSTITUTIONS, 1),
            new SiteKind("sub2", "2 substitutions", Planted.Kind.SUBSTITUTIONS, 2),
            new SiteKind("sub3", "3 substitutions", Planted.Kind.SUBSTITUTIONS, 3),
            new SiteKind("sub4", "4 substitutions", Planted.Kind.SUBSTITUTIONS, 4),
            new SiteKind("sub5", "5 substitutions", Planted.Kind.SUBSTITUTIONS, 5),
            new SiteKind("hot2", "hot primer, 2 substitutions", Planted.Kind.SUBSTITUTIONS, 2, true, false),
            new SiteKind("hot3", "hot primer, 3 substitutions", Planted.Kind.SUBSTITUTIONS, 3, true, false),
            new SiteKind("hot4", "hot primer, 4 substitutions", Planted.Kind.SUBSTITUTIONS, 4, true, false),
            new SiteKind("hot5", "hot primer, 5 substitutions", Planted.Kind.SUBSTITUTIONS, 5, true, false),
            new SiteKind("both1", "both sites, 1 substitution each", Planted.Kind.SUBSTITUTIONS, 1, false, true),
            new SiteKind("hotboth2", "hot primers, both sites, 2 substitutions each", Planted.Kind.SUBSTITUTIONS, 2,
                    true, true),
            new SiteKind("eighth", "hot primers, both sites, 8th base from each end", Planted.Kind.EIGHTH_FROM_ENDS,
                    2, true, true),
            new SiteKind("hotdel", "hot primer, 1-base deletion", Planted.Kind.DELETION, 1, true, false),
            new SiteKind("hotins", "hot primer, 1-base insertion", Planted.Kind.INSERTION, 1, true, false),
            new SiteKind("five2", "2 substitutions at the 5' end", Planted.Kind.FIVE_PRIME, 2),
            new SiteKind("five3", "3 substitutions at the 5' end", Planted.Kind.FIVE_PRIME, 3),
            new SiteKind("five4", "4 substitutions at the 5' end", Planted.Kind.FIVE_PRIME, 4),
            new SiteKind("end", "mismatch on the 3' base", Planted.Kind.THREE_PRIME_END, 1),
            new SiteKind("window", "mismatch in the 3' window", Planted.Kind.THREE_PRIME_WINDOW, 1),
            new SiteKind("del", "1-base deletion", Planted.Kind.DELETION, 1),
            new SiteKind("ins", "1-base insertion", Planted.Kind.INSERTION, 1));

    /**
     * One assembly of one chromosome holding, for every assay, a product with one primer's site changed: the product
     * is expected when {@link Rule} accepts the changed site, and no product when it rejects it.
     */
    static Scenario sites(Tier tier) {
        Random random = new Random(20261128);
        Scenario scenario = new Scenario("sites", "sites", 1);
        StringBuilder chromosome = new StringBuilder(Seq.random(random, 2000));
        for (SiteKind kind : SITE_KINDS) {
            for (int i = 1; i <= tier.perSiteKind; i++) {
                String id = String.format(Locale.ROOT, "%s_%02d", kind.id(), i);
                int length = 150 + random.nextInt(251);
                Assay assay = kind.hot() ? Assay.design(random, id, length, false, 66, 72, 24, 28)
                        : Assay.design(random, id, length);
                Planted planted = kind.kind() == null ? Planted.perfect(random, assay, RULE)
                        : Planted.changed(random, assay, kind.both() ? Planted.Sites.BOTH : i % 2 == 0
                        ? Planted.Sites.FORWARD : Planted.Sites.REVERSE, kind.kind(), kind.count(), RULE);
                // Half of the products on the other strand.
                boolean minus = random.nextBoolean();
                chromosome.append(minus ? Seq.reverseComplement(planted.template()) : planted.template());
                chromosome.append(Seq.random(random, 1000 + random.nextInt(2000)));
                scenario.assay(assay).expect(assay.id(), kind.category(), "sites", assay, planted);
            }
        }
        return scenario.assembly("sites", List.of(new Contig("chromosome", "", chromosome.toString())));
    }

    // ---- geometry ----

    /** Random bases on both sides of {@code sequence}. */
    private static String flanked(Random random, String sequence) {
        return Seq.random(random, 300 + random.nextInt(200)) + sequence + Seq.random(random, 300 + random.nextInt(200));
    }

    /** Perfect sites, arranged as the documentation describes: what is reported must not depend on how they are found. */
    static Scenario geometry(Tier tier) {
        Random random = new Random(20261129);
        Scenario scenario = new Scenario("geometry", "geometry", 2);

        // Product sizes up to --max-length (5000), and beyond it.
        List<Contig> sizes = new ArrayList<>();
        for (int size : new int[]{60, 150, 500, 1500, 4000, 5000, 5001, 8000}) {
            Assay assay = Assay.design(random, "size" + size, size);
            sizes.add(new Contig("contig_" + assay.id(), "", flanked(random, assay.product())));
            scenario.assay(assay);
            if (size <= 5000) {
                scenario.expect(assay.id(), "product size to 5000 bp", "sizes", assay.id(), size + " bp", size);
            } else {
                scenario.expect(assay.id(), "product over --max-length", "sizes", assay.id(), size + " bp");
            }
        }
        scenario.assembly("sizes", sizes);

        List<Contig> misc = new ArrayList<>();
        Assay minus = Assay.design(random, "minus", 300);
        misc.add(new Contig("contig_minus", "", flanked(random, Seq.reverseComplement(minus.product()))));
        scenario.assay(minus).expect("minus", "product on the minus strand", "misc", "minus", "", 300);

        Assay twice = Assay.design(random, "twice", 240);
        misc.add(new Contig("contig_twice_a", "", flanked(random, twice.product())));
        misc.add(new Contig("contig_twice_b", "", flanked(random, twice.product())));
        scenario.assay(twice).expect("twice", "two copies", "misc", "twice", "one product on each of two contigs", 240, 240);

        Assay lonely = Assay.design(random, "lonely", 300);
        misc.add(new Contig("contig_lonely", "", flanked(random,
                lonely.product().substring(0, 260) + Seq.random(random, 40))));
        scenario.assay(lonely).expect("lonely", "one primer has no site", "misc", "lonely", "the reverse site replaced");

        Assay split = Assay.design(random, "split", 400);
        misc.add(new Contig("contig_split_a", "", Seq.random(random, 400) + split.product().substring(0, 200)));
        misc.add(new Contig("contig_split_b", "", split.product().substring(200) + Seq.random(random, 400)));
        scenario.assay(split).expect("split", "product across two contigs", "misc", "split",
                "the assembly breaks inside the product: could not tell");

        // The forward primer at both ends, facing each other: a product of that primer alone.
        Assay single = Assay.design(random, "single", 300);
        String alone = single.forward() + Seq.random(random, 280) + Seq.reverseComplement(single.forward());
        misc.add(new Contig("contig_single", "", flanked(random, alone)));
        scenario.assay(single).expect("single", "product of one primer alone", "misc", "single", "", alone.length());

        // Two assays of one reaction: the forward primer of one and the reverse primer of the other.
        Assay mixA = Assay.design(random, "mixa", 300).withWords("mix=tube1");
        Assay mixB = Assay.design(random, "mixb", 300).withWords("mix=tube1");
        String cross = mixA.forward() + Seq.random(random, 200) + Seq.reverseComplement(mixB.reverse());
        misc.add(new Contig("contig_mix", "", flanked(random, cross)));
        scenario.assay(mixA).assay(mixB).expect("mix", "product of two assays of a reaction", "misc", "mixa+mixb", "",
                cross.length());

        // Primers that melt at 49 to 52.5 °C: accepted at ta=50 (45 °C needed), not at the run's 60 (55 needed).
        Assay cool = Assay.design(random, "cool", 250, false, 49, 52.5, 16, 20).withWords("ta=50");
        Assay cold = Assay.design(random, "cold", 250, false, 49, 52.5, 16, 20);
        misc.add(new Contig("contig_cool", "", flanked(random, cool.product())));
        misc.add(new Contig("contig_cold", "", flanked(random, cold.product())));
        scenario.assay(cool).expect("cool", "assay at its own ta=", "misc", "cool", "primers at 49 to 52.5 °C, ta=50", 250);
        scenario.assay(cold).expect("cold", "primers too cold for the run", "misc", "cold", "primers at 49 to 52.5 °C");
        scenario.assembly("misc", misc);

        // A product across the origin of a circle: reported when the header says it is one.
        Assay origin = Assay.design(random, "origin", 500);
        String circle = origin.product().substring(250) + Seq.random(random, 1500) + origin.product().substring(0, 250);
        scenario.assay(origin);
        scenario.assembly("circular", List.of(new Contig("plasmid", "circular=true", circle)));
        scenario.assembly("linear", List.of(new Contig("plasmid", "", circle)));
        scenario.expect("origin-circular", "product across a circle's origin", "circular", "origin", "", 500);
        scenario.expect("origin-linear", "product across the ends of an unmarked circle", "linear", "origin",
                "the same sequence without circular=true: could not tell");

        // A probe assay with its probe, and with the probe's site replaced.
        Assay probe = Assay.design(random, "probe", 200, true, Assay.TM_LOW, Assay.TM_HIGH);
        int probeStart = probe.product().indexOf(probe.probe());
        String noProbe = probe.product().substring(0, probeStart) + Seq.random(random, probe.probe().length())
                + probe.product().substring(probeStart + probe.probe().length());
        scenario.assay(probe);
        scenario.assembly("probe_in", List.of(new Contig("contig_probe", "", flanked(random, probe.product()))));
        scenario.assembly("probe_out", List.of(new Contig("contig_probe", "", flanked(random, noProbe))));
        scenario.expect("probe-in", "probe between the primers", "probe_in", "probe", "", 200);
        scenario.expect("probe-out", "probe without a site", "probe_out", "probe", "the probe's site replaced");

        // A degenerate base in the forward primer, and a sample with each of the bases it stands for.
        Assay plain = Assay.design(random, "degen", 220);
        char base = plain.forward().charAt(9);
        char code = base == 'A' || base == 'G' ? 'R' : 'Y';
        char alternative = switch (base) {
            case 'A' -> 'G';
            case 'G' -> 'A';
            case 'C' -> 'T';
            default -> 'C';
        };
        Assay degenerate = plain.withForward(plain.forward().substring(0, 9) + code + plain.forward().substring(10));
        String other = plain.product().substring(0, 9) + alternative + plain.product().substring(10);
        scenario.assay(degenerate);
        scenario.assembly("degen_a", List.of(new Contig("contig_degen", "", flanked(random, plain.product()))));
        scenario.assembly("degen_b", List.of(new Contig("contig_degen", "", flanked(random, other))));
        scenario.expect("degenerate-a", "degenerate primer", "degen_a", "degen", code + " over " + base, 220);
        scenario.expect("degenerate-b", "degenerate primer", "degen_b", "degen", code + " over " + alternative, 220);

        // How the file is written must not matter.
        Assay zipped = Assay.design(random, "zipped", 180);
        scenario.assay(zipped).sample(new Scenario.Sample("gz", List.of(new Contig("contig_zipped", "",
                flanked(random, zipped.product()))), null, true, false));
        scenario.expect("gzip", "input as written", "gz", "zipped", "a gzipped FASTA", 180);
        Assay masked = Assay.design(random, "masked", 180);
        scenario.assay(masked).sample(new Scenario.Sample("lower", List.of(new Contig("contig_masked", "",
                flanked(random, masked.product()))), null, false, true));
        scenario.expect("lower-case", "input as written", "lower", "masked", "a FASTA in lower case", 180);

        if (tier != Tier.CI) {
            // Far along a chromosome-long sequence, and across its millionth base.
            Assay far = Assay.design(random, "far", 350);
            Assay seam = Assay.design(random, "seam", 350);
            String chromosome = Seq.random(random, 999_850) + seam.product() + Seq.random(random, 200_000)
                    + far.product() + Seq.random(random, 300_000);
            scenario.assay(far).assay(seam).assembly("long", List.of(new Contig("chromosome", "", chromosome)));
            scenario.expect("far", "long sequence", "long", "far", "1.2 Mb along a 1.5 Mb sequence", 350);
            scenario.expect("seam", "long sequence", "long", "seam", "across the millionth base", 350);
        }
        return scenario;
    }

    // ---- reads ----

    /** A product of the read cases: its template in the chromosome, and what is expected of it. */
    private record ReadCase(Assay assay, String category, String note, boolean present) {
    }

    /**
     * One chromosome and its assays, looked for in read pairs of three sequencing runs, and in the chromosome itself
     * as an assembly: every product is there, so what an assembly gives and the reads do not is the reads' loss.
     */
    static List<Scenario> reads(Tier tier) {
        Random random = new Random(20261130);
        List<ReadCase> cases = new ArrayList<>();
        List<String> loci = new ArrayList<>();
        // The other copies of the repeats, placed away from their products.
        List<String> elsewhere = new ArrayList<>();
        int n = tier.perReadKind;

        for (int length : tier.readLengths) {
            for (int i = 1; i <= n; i++) {
                Assay assay = Assay.design(random, String.format(Locale.ROOT, "len%d_%02d", length, i), length);
                loci.add(assay.product());
                cases.add(new ReadCase(assay, "product of " + length + " bp", "", true));
            }
        }
        // Sites that differ from hot primers and still melt above the minimum: the rule accepts them.
        for (int count : new int[]{2, 3, 4}) {
            for (int i = 1; i <= n; i++) {
                cases.add(divergent(random, loci, String.format(Locale.ROOT, "div%d_%02d", count, i),
                        "site with " + count + " substitutions", 66, 25, i % 2 == 0 ? Planted.Sites.FORWARD
                                : Planted.Sites.REVERSE, Planted.Kind.SUBSTITUTIONS, count));
            }
        }
        for (int i = 1; i <= n; i++) {
            cases.add(divergent(random, loci, String.format(Locale.ROOT, "five3_%02d", i),
                    "site with 3 substitutions at the 5' end", 66, 25, i % 2 == 0 ? Planted.Sites.FORWARD
                            : Planted.Sites.REVERSE, Planted.Kind.FIVE_PRIME, 3));
        }
        // Both sites accepted by the rule, and neither with 17 bases in a row within one difference of its primer:
        // the sites a search of the reads by near-exact words cannot bait (Limitations, "Known gaps").
        for (int i = 1; i <= n; i++) {
            cases.add(divergent(random, loci, String.format(Locale.ROOT, "eighth_%02d", i),
                    "both sites with substitutions on the 8th base from each end", 64, 22, Planted.Sites.BOTH,
                    Planted.Kind.EIGHTH_FROM_ENDS, 2));
        }
        // A repeat inside a product of 1500 bp, with another copy 3% different elsewhere in the chromosome.
        for (int repeat : tier.repeats) {
            for (int i = 1; i <= Math.max(2, n * 2 / 3); i++) {
                Assay assay = Assay.design(random, String.format(Locale.ROOT, "rep%d_%02d", repeat, i), 1500);
                String product = assay.product();
                int start = (product.length() - repeat) / 2;
                elsewhere.add(Seq.diverged(random, product.substring(start, start + repeat), 0.03));
                loci.add(product);
                cases.add(new ReadCase(assay, "repeat of " + repeat + " bp in a 1500-bp product",
                        "its other copy differs by 3%", true));
            }
        }
        // A tandem repeat inside a product a read pair spans.
        for (int i = 1; i <= Math.max(2, n * 2 / 3); i++) {
            Assay design = Assay.design(random, String.format(Locale.ROOT, "tandem_%02d", i), 400);
            String product = design.product();
            String repeat = "CAG".repeat(25);
            int start = (product.length() - repeat.length()) / 2;
            String withRepeat = product.substring(0, start) + repeat + product.substring(start + repeat.length());
            Assay assay = new Assay(design.id(), design.forward(), design.reverse(), null, "", withRepeat);
            loci.add(withRepeat);
            cases.add(new ReadCase(assay, "tandem repeat of 75 bp in a 400-bp product", "(CAG)25", true));
        }
        // Assays of which the sample holds nothing.
        for (int i = 1; i <= Math.max(4, n); i++) {
            Assay assay = Assay.design(random, String.format(Locale.ROOT, "absent_%02d", i), 300);
            cases.add(new ReadCase(assay, "assay absent from the sample", "", false));
        }

        // The chromosome: the products and the repeats' other copies, evenly apart, on either strand.
        int filler = Math.max(1500, (tier.chromosome - loci.stream().mapToInt(String::length).sum())
                / (loci.size() + elsewhere.size() + 1));
        StringBuilder chromosome = new StringBuilder(Seq.random(random, filler));
        List<String> pieces = new ArrayList<>(loci);
        pieces.addAll(elsewhere);
        for (String piece : pieces) {
            chromosome.append(random.nextBoolean() ? Seq.reverseComplement(piece) : piece);
            chromosome.append(Seq.random(random, filler));
        }
        List<Contig> contigs = List.of(new Contig("chromosome", "", chromosome.toString()));

        Scenario assembly = new Scenario("reads", "reads-as-assembly", 3);
        Scenario reads = new Scenario("reads", "reads", 4);
        assembly.assembly("assembly", contigs);
        for (ReadSimulator.Profile profile : tier.profiles) {
            reads.reads(sample(profile), contigs, profile);
        }
        for (ReadCase one : cases) {
            assembly.assay(one.assay());
            reads.assay(one.assay());
            Integer[] sizes = one.present() ? new Integer[]{one.assay().size()} : new Integer[0];
            assembly.expect(one.assay().id() + "@assembly", one.category() + " | assembly", "assembly",
                    one.assay().id(), one.note(), sizes);
            for (ReadSimulator.Profile profile : tier.profiles) {
                reads.expect(one.assay().id() + "@" + sample(profile), one.category() + " | " + profile.name(),
                        sample(profile), one.assay().id(), one.note(), sizes);
            }
        }
        return List.of(assembly, reads);
    }

    /** A sample's name: a profile's, as a file name. */
    private static String sample(ReadSimulator.Profile profile) {
        return "reads_" + profile.name().replace('-', '_');
    }

    /**
     * A 200-bp product whose changed sites {@link Rule} accepts, added to {@code loci}: primers of {@code minLength}
     * bases or up to 2 more, melting at {@code tmLow} °C or up to 6 above, are drawn until a change of theirs is
     * accepted.
     */
    private static ReadCase divergent(Random random, List<String> loci, String id, String category, double tmLow,
                                      int minLength, Planted.Sites sites, Planted.Kind kind, int count) {
        for (int design = 0; design < 200; design++) {
            Assay assay = Assay.design(random, id, 200, false, tmLow, tmLow + 6, minLength, minLength + 2);
            for (int attempt = 0; attempt < 20; attempt++) {
                Planted planted = Planted.changed(random, assay, sites, kind, count, RULE);
                if (planted.verdict() == Rule.Verdict.ACCEPT) {
                    loci.add(planted.template());
                    return new ReadCase(assay, category, planted.note(), true);
                }
            }
        }
        throw new IllegalStateException("No accepted site with " + count + " changes (" + kind + ") for " + id);
    }
}
