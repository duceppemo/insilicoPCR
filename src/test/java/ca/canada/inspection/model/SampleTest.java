package ca.canada.inspection.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleTest {

    private static BlastResult hit(String primerId) {
        return new BlastResult("contig", primerId, 0, 0, 1, 20, 1, 20, 20, 20, "ACGT", "ACGT", "ACGT");
    }

    @Test
    void filesStaySortedRegardlessOfInsertionOrder() {
        Sample sample = new Sample("s");
        sample.addFile(Path.of("b_2.fastq"));
        sample.addFile(Path.of("a_1.fastq"));
        assertEquals(List.of(Path.of("a_1.fastq"), Path.of("b_2.fastq")), sample.files());

        sample.addFile(Path.of("z.fq"));
        sample.addFile(Path.of("a.fq"));
        assertEquals(Path.of("a.fq"), sample.files().getFirst());
    }

    @Test
    void publicViewsAreImmutableCopies() {
        Sample sample = new Sample("s");
        sample.hits().add("assay-F", hit("assay-F"));
        sample.hits().describe("c1", "chromosome");

        assertThrows(UnsupportedOperationException.class, () -> sample.files().add(Path.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> sample.hits().byOligo().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> sample.hits().descriptions().put("x", "y"));
        assertEquals("chromosome", sample.hits().descriptions().get("c1"));
    }

    @Test
    void whatTheRunLearnsIsKept() {
        Sample sample = new Sample("s1");
        sample.setFileType(Sample.FileType.FASTQ);
        sample.hits().add("assay-F", hit("assay-F"));
        sample.hits().add("assay-F", hit("assay-F"));

        assertEquals("s1", sample.getName());
        assertTrue(sample.isFastq());
        assertEquals(2, sample.hits().byOligo().get("assay-F").size());

        sample.setFileType(Sample.FileType.FASTA);
        assertFalse(sample.isFastq());
    }

    /** A cache key of the tests, as Products has its own. */
    private record Key(String name) implements Hits.Key<String> {
    }

    @Test
    void derivedValuesAreKeptUntilTheHitsOrContigsChange() {
        Sample sample = new Sample("s");
        int[] computed = new int[1];
        java.util.function.Supplier<String> compute = () -> "products " + (++computed[0]);
        Key key = new Key("key");

        assertEquals("products 1", sample.hits().derived(key, compute));
        assertEquals("products 1", sample.hits().derived(new Key("key"), compute), "worked out once");
        sample.hits().describe("c1", "len=10");
        assertEquals("products 2", sample.hits().derived(key, compute), "a new contig changes the products");
        sample.hits().add("a-F", null);
        assertEquals("products 3", sample.hits().derived(key, compute), "so does a new hit");
        assertEquals("products 4", sample.hits().derived(new Key("other key"), compute));
    }

    @Test
    void theFirstFileOfAPairIsReadOneWhateverTheCase() {
        Sample sample = new Sample("s");
        sample.addFile(Path.of("s_R2.fastq.gz"));
        sample.addFile(Path.of("s_r1.fastq.gz"));
        assertEquals(List.of(Path.of("s_r1.fastq.gz"), Path.of("s_R2.fastq.gz")), sample.files());
    }
}
