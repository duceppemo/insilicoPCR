package ca.canada.inspection.benchmark;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/**
 * Read pairs of a sample's contigs, as a short-read sequencer gives them: fragments drawn evenly along each contig,
 * from either strand, a read from each end, with substitution errors. Simple on purpose: the benchmark's real read
 * sets carry what real sequencing adds (uneven depth, adapters, quality that falls along a read).
 */
final class ReadSimulator {

    /**
     * A sequencing run.
     *
     * @param name         the profile's name in case names and categories
     * @param readLength   bases of each read
     * @param fragmentMean mean fragment length, bp
     * @param fragmentSd   its standard deviation
     * @param depth        mean depth of coverage
     * @param errorRate    the share of bases read as another
     */
    record Profile(String name, int readLength, int fragmentMean, int fragmentSd, double depth, double errorRate) {
    }

    /** 2 x 150 bp, 350-bp fragments, 40x: an ordinary run. */
    static final Profile PE150 = new Profile("2x150-40x", 150, 350, 35, 40, 0.002);
    /** The same run at 15x. */
    static final Profile PE150_LOW = new Profile("2x150-15x", 150, 350, 35, 15, 0.002);
    /** 2 x 100 bp on 165-bp fragments, 60x: reads that overlap, as SRR642185. */
    static final Profile PE100_SHORT = new Profile("2x100-short-60x", 100, 165, 15, 60, 0.002);

    private ReadSimulator() {
    }

    /** Writes {@code <sample>_R1.fastq.gz} and {@code <sample>_R2.fastq.gz} into {@code folder}. */
    static void write(Random random, Scenario.Sample sample, Path folder) throws IOException {
        Profile profile = sample.reads();
        String quality = "I".repeat(profile.readLength());
        try (BufferedWriter first = gzipped(folder.resolve(sample.name() + "_R1.fastq.gz"));
             BufferedWriter second = gzipped(folder.resolve(sample.name() + "_R2.fastq.gz"))) {
            long number = 0;
            for (Scenario.Contig contig : sample.contigs()) {
                String sequence = contig.sequence();
                if (sequence.length() < profile.readLength()) {
                    continue;
                }
                long pairs = Math.round(profile.depth() * sequence.length() / (2.0 * profile.readLength()));
                for (long pair = 0; pair < pairs; pair++) {
                    int length = (int) Math.round(profile.fragmentMean() + random.nextGaussian() * profile.fragmentSd());
                    length = Math.max(profile.readLength(), Math.min(length, sequence.length()));
                    int start = random.nextInt(sequence.length() - length + 1);
                    String fragment = sequence.substring(start, start + length);
                    if (random.nextBoolean()) {
                        fragment = Seq.reverseComplement(fragment);
                    }
                    number++;
                    String id = "@" + sample.name() + "." + number;
                    record(first, id + " 1:N:0:1", read(random, fragment.substring(0, profile.readLength()), profile),
                            quality);
                    record(second, id + " 2:N:0:1", read(random, Seq.reverseComplement(fragment)
                            .substring(0, profile.readLength()), profile), quality);
                }
            }
        }
    }

    private static String read(Random random, String bases, Profile profile) {
        StringBuilder read = new StringBuilder(bases);
        for (int i = 0; i < read.length(); i++) {
            if (random.nextDouble() < profile.errorRate()) {
                read.setCharAt(i, Seq.other(random, read.charAt(i)));
            }
        }
        return read.toString();
    }

    private static void record(BufferedWriter writer, String id, String bases, String quality) throws IOException {
        writer.write(id);
        writer.write('\n');
        writer.write(bases);
        writer.write("\n+\n");
        writer.write(quality);
        writer.write('\n');
    }

    private static BufferedWriter gzipped(Path file) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)),
                StandardCharsets.UTF_8));
    }
}
