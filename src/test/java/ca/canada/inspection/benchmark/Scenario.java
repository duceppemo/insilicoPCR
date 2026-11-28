package ca.canada.inspection.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * One run of the program under test and what it must report: the samples and the primer file the benchmark writes,
 * the options of the run, and an {@link Expectation} for each sample and assay whose answer is known.
 *
 * <p>A sample and assay without an expectation must give no product.</p>
 */
final class Scenario {

    /** One sequence of a sample. */
    record Contig(String name, String description, String sequence) {
    }

    /**
     * How a sample is given to the program.
     *
     * @param reads     the read pairs to simulate from the contigs, or {@code null} for the contigs as an assembly
     * @param gzip      an assembly written gzipped (reads always are)
     * @param lowerCase an assembly written in lower case, as soft-masked sequences are
     */
    record Sample(String name, List<Contig> contigs, ReadSimulator.Profile reads, boolean gzip, boolean lowerCase) {
    }

    /**
     * What a sample must show for an assay.
     *
     * @param caseId   the case's name, the same in every version: what a baseline is compared by
     * @param category what the case measures, which the summary counts by
     * @param sizes    the products' sizes, bp, one for each product; empty when there must be none
     * @param scored   whether the answer is sure enough to count ({@link Rule.Verdict#BORDERLINE} is not)
     * @param note     how the case was made, for the scorecard
     */
    record Expectation(String caseId, String category, String sample, String assay, List<Integer> sizes,
                       boolean scored, String note) {
    }

    final String suite;
    final String name;
    final List<String> options;
    final List<Assay> assays = new ArrayList<>();
    final List<Sample> samples = new ArrayList<>();
    final List<Expectation> expectations = new ArrayList<>();
    /** The seed of the simulated reads. */
    final long seed;

    Scenario(String suite, String name, long seed, String... options) {
        this.suite = suite;
        this.name = name;
        this.seed = seed;
        this.options = List.of(options);
    }

    boolean hasReads() {
        return samples.stream().anyMatch(sample -> sample.reads() != null);
    }

    Scenario assay(Assay assay) {
        assays.add(assay);
        return this;
    }

    Scenario assembly(String sample, List<Contig> contigs) {
        samples.add(new Sample(sample, contigs, null, false, false));
        return this;
    }

    Scenario reads(String sample, List<Contig> contigs, ReadSimulator.Profile profile) {
        samples.add(new Sample(sample, contigs, profile, false, false));
        return this;
    }

    Scenario sample(Sample sample) {
        samples.add(sample);
        return this;
    }

    /** A product of each of {@code sizes} is expected. */
    Scenario expect(String caseId, String category, String sample, String assay, String note, Integer... sizes) {
        expectations.add(new Expectation(caseId, category, sample, assay, List.of(sizes), true, note));
        return this;
    }

    /** What the rule says of the changed sites of {@code planted}: the product, none, or not scored. */
    Scenario expect(String caseId, String category, String sample, Assay assay, Planted planted) {
        // A borderline site is run with the product as its expectation, and never counted.
        List<Integer> sizes = planted.verdict() == Rule.Verdict.REJECT ? List.of() : List.of(assay.size());
        expectations.add(new Expectation(caseId, category, sample, assay.id(), sizes,
                planted.verdict() != Rule.Verdict.BORDERLINE, planted.note()));
        return this;
    }

    /**
     * Writes the scenario's input into {@code folder}: {@code primers.fasta} and {@code input/}, one FASTA file for
     * each assembly and a gzipped FASTQ pair for each sample of reads.
     */
    void write(Path folder) throws IOException {
        Path input = Files.createDirectories(folder.resolve("input"));
        StringBuilder primers = new StringBuilder();
        assays.forEach(assay -> primers.append(assay.fasta()));
        Files.writeString(folder.resolve("primers.fasta"), primers);
        Random random = new Random(seed);
        for (Sample sample : samples) {
            if (sample.reads() != null) {
                ReadSimulator.write(random, sample, input);
                continue;
            }
            Path file = input.resolve(sample.name() + (sample.gzip() ? ".fasta.gz" : ".fasta"));
            try (Writer writer = sample.gzip()
                    ? new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)
                    : Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                BufferedWriter fasta = new BufferedWriter(writer);
                for (Contig contig : sample.contigs()) {
                    fasta.write(">" + contig.name() + (contig.description().isEmpty() ? "" : " " + contig.description()));
                    fasta.write('\n');
                    String sequence = sample.lowerCase() ? contig.sequence().toLowerCase(java.util.Locale.ROOT)
                            : contig.sequence();
                    for (int start = 0; start < sequence.length(); start += 80) {
                        fasta.write(sequence, start, Math.min(80, sequence.length() - start));
                        fasta.write('\n');
                    }
                }
                fasta.flush();
            }
        }
    }
}
