package ca.canada.inspection.benchmark;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;

/**
 * Real samples given both ways, as an assembly and as the reads it was made from: what the assembly shows for an
 * assay, the reads must show too. No answer is written down for these samples; the assembly is the reference, so
 * the cases measure what is lost between an assembly and its reads on real sequencing (uneven depth, adapters,
 * errors, real repeats), which simulated reads do not carry.
 *
 * <p>The assays are a primer file's, or designed here: primer pairs cut from random places of each sample's own
 * contigs, for products of chosen sizes. A designed assay is scored only when the assembly gives its one product,
 * of the size it was designed for.</p>
 *
 * <p>The samples are listed in a tab-separated file with a header line, {@code sample}, {@code assembly},
 * {@code reads1} and {@code reads2} (empty for single-end reads); paths are taken from the file's own folder.</p>
 */
final class Concordance {

    /** Product sizes of designed assays, bp. */
    static final int[] DESIGNED_SIZES = {300, 1000, 2000, 3000, 4500};

    /** A sample given both ways. */
    record Sample(String name, Path assembly, Path reads1, Path reads2) {
    }

    private Concordance() {
    }

    static List<Sample> manifest(Path file) throws IOException {
        List<Sample> samples = new ArrayList<>();
        Path folder = file.toAbsolutePath().getParent();
        for (Map<String, String> row : Observed.rows(Files.readString(file, StandardCharsets.UTF_8))) {
            String second = row.getOrDefault("reads2", "");
            samples.add(new Sample(row.get("sample"), folder.resolve(row.get("assembly")),
                    folder.resolve(row.get("reads1")), second.isEmpty() ? null : folder.resolve(second)));
        }
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("No sample in " + file + ": a header line (sample, assembly, reads1,"
                    + " reads2, tab-separated) and a line for each sample");
        }
        return samples;
    }

    /**
     * Runs the assemblies, then the reads, and adds a case for each sample and assay to {@code scorecard}.
     *
     * @param primers a primer file, or {@code null}
     * @param design  assays designed for each size of {@link #DESIGNED_SIZES} in each sample; 0 for none
     */
    static void run(App app, List<Sample> samples, Path primers, int design, Path work, int threads,
                    PrintStream progress, Scorecard scorecard) throws IOException {
        Path folder = Files.createDirectories(work.resolve("concordance"));
        StringBuilder panel = new StringBuilder(primers == null ? "" : Files.readString(primers, StandardCharsets.UTF_8));
        if (!panel.isEmpty() && panel.charAt(panel.length() - 1) != '\n') {
            panel.append('\n');
        }
        // A designed assay, by its name: the sample it was cut from and its size.
        Map<String, Assay> designed = new LinkedHashMap<>();
        Map<String, String> designedFor = new LinkedHashMap<>();
        Random random = new Random(20261201);
        for (int s = 0; s < samples.size() && design > 0; s++) {
            List<String> contigs = contigs(samples.get(s).assembly());
            for (int size : DESIGNED_SIZES) {
                for (int i = 1; i <= design; i++) {
                    Assay assay = cut(random, String.format(Locale.ROOT, "d%d_s%02d_%02d", size, s + 1, i), contigs, size);
                    if (assay != null) {
                        designed.put(assay.id(), assay);
                        designedFor.put(assay.id(), samples.get(s).name());
                        panel.append(assay.fasta());
                    }
                }
            }
        }
        Path panelFile = folder.resolve("primers.fasta");
        Files.writeString(panelFile, panel, StandardCharsets.UTF_8);
        List<String> assays = panel.toString().lines().filter(line -> line.startsWith(">"))
                .map(line -> line.substring(1).split("\\s")[0]).filter(id -> id.endsWith("-F") || id.contains("-F_"))
                .map(id -> id.substring(0, id.lastIndexOf("-F"))).distinct().toList();
        if (assays.isEmpty()) {
            throw new IllegalArgumentException("No assay: give --primers, --design, or both");
        }

        Path assemblies = Files.createDirectories(folder.resolve("assemblies"));
        Path reads = Files.createDirectories(folder.resolve("reads"));
        for (Sample sample : samples) {
            link(sample.assembly(), assemblies.resolve(sample.name() + (gzipped(sample.assembly()) ? ".fasta.gz" : ".fasta")));
            if (sample.reads2() == null) {
                link(sample.reads1(), reads.resolve(sample.name() + (gzipped(sample.reads1()) ? ".fastq.gz" : ".fastq")));
            } else {
                link(sample.reads1(), reads.resolve(sample.name() + (gzipped(sample.reads1()) ? "_R1.fastq.gz" : "_R1.fastq")));
                link(sample.reads2(), reads.resolve(sample.name() + (gzipped(sample.reads2()) ? "_R2.fastq.gz" : "_R2.fastq")));
            }
        }
        progress.printf("concordance: %d sample(s), %d assay(s); the assemblies...%n", samples.size(), assays.size());
        App.Run onAssemblies = app.run(assemblies, panelFile, folder.resolve("assemblies-output"), List.of(), threads,
                work.resolve("concordance-assemblies.log"));
        progress.printf("concordance: assemblies, exit %d in %.0f s; the reads...%n", onAssemblies.exitCode(),
                onAssemblies.seconds());
        App.Run onReads = app.run(reads, panelFile, folder.resolve("reads-output"), List.of(), threads,
                work.resolve("concordance-reads.log"));
        progress.printf("concordance: reads, exit %d in %.0f s%n", onReads.exitCode(), onReads.seconds());
        scorecard.timings().add(new Scorecard.Timing("concordance", "assemblies", samples.size(), assays.size(),
                onAssemblies.seconds(), onAssemblies.exitCode()));
        scorecard.timings().add(new Scorecard.Timing("concordance", "reads", samples.size(), assays.size(),
                onReads.seconds(), onReads.exitCode()));
        boolean failed = onAssemblies.exitCode() != 0 || onReads.exitCode() != 0;
        Observed reference = onAssemblies.exitCode() == 0 ? Observed.of(onAssemblies.output()) : null;
        Observed observed = failed ? null : Observed.of(onReads.output());

        // Every assay of the panel in every sample, and any product reported under another name (two assays).
        TreeSet<Observed.Key> keys = new TreeSet<>();
        for (Sample sample : samples) {
            assays.forEach(assay -> keys.add(new Observed.Key(sample.name(), assay)));
        }
        if (!failed) {
            keys.addAll(reference.withProducts());
            keys.addAll(observed.withProducts());
        }
        for (Observed.Key key : keys) {
            List<Integer> expected = failed ? List.of() : sizes(reference, key);
            List<Integer> got = failed ? List.of() : sizes(observed, key);
            Assay cut = designed.get(key.assay());
            String category = "the panel's assays";
            String note = "";
            boolean scored = true;
            if (cut != null) {
                boolean own = designedFor.get(key.assay()).equals(key.sample());
                category = own ? "designed, " + cut.size() + " bp" : "designed for another sample";
                // Scored where it was cut only when the assembly gives its product and no other.
                scored = !own || expected.equals(List.of(cut.size()));
                note = own && !scored ? "not the one product of " + cut.size() + " bp in its own assembly" : "";
            }
            scorecard.add(new Scorecard.Line("concordance/" + key.sample() + ":" + key.assay(), "concordance", "reads",
                    category, key.sample(), key.assay(), Scorecard.sizes(expected), Scorecard.sizes(got),
                    failed ? Scorecard.Result.FAILED : Scorecard.result(expected, got, true), scored,
                    failed ? "run failed" : observed.said(key), note));
        }
    }

    private static List<Integer> sizes(Observed observed, Observed.Key key) {
        return observed.products(key).stream().map(Observed.Product::size).distinct().sorted().toList();
    }

    /**
     * An assay cut from a random place of {@code contigs}: a product of {@code size} bp whose ends make primers of 20
     * to 24 bases melting at {@link Assay#TM_LOW} to {@link Assay#TM_HIGH} °C; {@code null} when no place gives one.
     */
    static Assay cut(Random random, String id, List<String> contigs, int size) {
        List<String> longEnough = contigs.stream().filter(contig -> contig.length() >= size + 200).toList();
        long total = longEnough.stream().mapToLong(String::length).sum();
        for (int attempt = 0; attempt < 5000 && total > 0; attempt++) {
            // A contig drawn by its length, so that every place of the assembly is as likely.
            long drawn = (long) (random.nextDouble() * total);
            String contig = longEnough.getFirst();
            for (String candidate : longEnough) {
                contig = candidate;
                drawn -= candidate.length();
                if (drawn < 0) {
                    break;
                }
            }
            int start = 100 + random.nextInt(contig.length() - size - 199);
            String product = contig.substring(start, start + size);
            if (!product.chars().allMatch(base -> "ACGT".indexOf(base) >= 0)) {
                continue;
            }
            String forward = primer(product);
            String reverse = primer(Seq.reverseComplement(product));
            if (forward != null && reverse != null) {
                return new Assay(id, forward, reverse, null, "", product);
            }
        }
        return null;
    }

    /** The first 20 to 24 bases of {@code sequence} that make a primer as {@link Assay#oligo} draws them, or {@code null}. */
    private static String primer(String sequence) {
        for (int length = 20; length <= 24; length++) {
            String oligo = sequence.substring(0, length);
            double gc = Seq.gc(oligo);
            if (gc < 0.35 || gc > 0.65 || Seq.hasRun(oligo, 4)) {
                continue;
            }
            double tm = Rule.perfectTm(oligo);
            if (tm >= Assay.TM_LOW && tm <= Assay.TM_HIGH) {
                return oligo;
            }
        }
        return null;
    }

    /** The sequences of a FASTA file, gzipped or not, in upper case. */
    static List<String> contigs(Path fasta) throws IOException {
        List<String> contigs = new ArrayList<>();
        StringBuilder current = null;
        try (InputStream in = Files.newInputStream(fasta);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gzipped(fasta) ? new GZIPInputStream(in)
                     : in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.startsWith(">")) {
                    if (current != null) {
                        contigs.add(current.toString());
                    }
                    current = new StringBuilder();
                } else if (current != null) {
                    current.append(line.strip().toUpperCase(Locale.ROOT));
                }
            }
        }
        if (current != null) {
            contigs.add(current.toString());
        }
        return contigs;
    }

    private static boolean gzipped(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    /** A link to {@code file} under the benchmark's own name for it; a copy where links cannot be made. */
    private static void link(Path file, Path name) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("No such file: " + file);
        }
        Files.deleteIfExists(name);
        try {
            Files.createSymbolicLink(name, file.toAbsolutePath());
        } catch (IOException | UnsupportedOperationException e) {
            Files.copy(file, name);
        }
    }
}
