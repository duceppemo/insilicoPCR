package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReadPoolTest {

    @TempDir
    Path dir;

    private Path gzip(String name, String... reads) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            for (String read : reads) {
                out.write(("@" + read + "\nACGT\n+\nIIII\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return file;
    }

    private static List<String> names(Path gzipped) throws IOException {
        List<String> names = new ArrayList<>();
        try (var in = new java.io.BufferedReader(new java.io.InputStreamReader(new GZIPInputStream(
                Files.newInputStream(gzipped)), StandardCharsets.UTF_8))) {
            String line;
            int i = 0;
            while ((line = in.readLine()) != null) {
                if (i++ % 4 == 0) {
                    names.add(line.substring(1));
                }
            }
        }
        return names;
    }

    @Test
    void eachReadEntersThePoolOnceAndTheMatesOfAPairTogether() throws IOException {
        // Tadpole counts a k-mer once per read holding it: a read baited again by a later round, or already
        // among the baited reads, made a k-mer of one read count as two, past mincountextend=2.
        ReadPool pool = ReadPool.of(gzip("baited.fq.gz", "p1/1", "p1/2"));
        Path kept = dir.resolve("kept.fq.gz");

        assertEquals(4, pool.addNew(gzip("round1.fq.gz", "p1/1", "p1/2", "p2/1 x", "p2/2 x", "p3/1", "p3/2"), kept));
        assertEquals(2, pool.addNew(gzip("round2.fq.gz", "p2/1 x", "p2/2 x", "p4/1", "p4/2"), kept));
        assertEquals(0, pool.addNew(gzip("round3.fq.gz", "p3/1", "p3/2", "p4/1", "p4/2"), kept));

        assertEquals(List.of("p2/1 x", "p2/2 x", "p3/1", "p3/2", "p4/1", "p4/2"), names(kept));
    }

    @Test
    void anEmptyBaitedFileIsAnEmptyPool() throws IOException {
        ReadPool pool = ReadPool.of(Files.writeString(dir.resolve("none.fq.gz"), ""));
        assertEquals(2, pool.addNew(gzip("round.fq.gz", "r/1", "r/2"), dir.resolve("kept.fq.gz")));
    }
}
