package ca.canada.inspection.insilicopcr;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SampleTest {

    private static BlastResult hit(String primerId) {
        return new BlastResult("s", "contig", primerId, 0, 0, 1, 20, 1, 20, 20, 20, "ACGT", "ACGT", "ACGT");
    }

    @Test
    void filesStaySortedRegardlessOfInsertionOrder() {
        Sample sample = new Sample();
        sample.addFile(Path.of("b_2.fastq"));
        sample.addFile(Path.of("a_1.fastq"));
        assertEquals(List.of(Path.of("a_1.fastq"), Path.of("b_2.fastq")), sample.files());

        sample.setFile(Path.of("only.fasta"));
        assertEquals(1, sample.files().size());

        sample.setFiles(List.of(Path.of("z.fq"), Path.of("a.fq")));
        assertEquals(Path.of("a.fq"), sample.files().getFirst());
    }

    @Test
    void publicViewsAreImmutableCopies() {
        Sample sample = new Sample();
        sample.addBlastResult("assay-F", hit("assay-F"));
        sample.addContig("c1", "chromosome");

        assertThrows(UnsupportedOperationException.class, () -> sample.files().add(Path.of("x")));
        assertThrows(UnsupportedOperationException.class, () -> sample.blastResults().put("x", List.of()));
        assertThrows(UnsupportedOperationException.class, () -> sample.contigs().put("x", "y"));
        assertEquals("chromosome", sample.contigs().get("c1"));
    }

    @Test
    void legacyAliasesDelegate() {
        Sample sample = new Sample();
        sample.setName("s1");
        sample.setFileType("fastq");
        sample.addNewBlastResult("assay-F", hit("assay-F"));

        assertEquals("s1", sample.getName());
        assertTrue(sample.isFastq());
        assertEquals(1, sample.getBlastResults().get("assay-F").size());

        sample.setFileType("fasta");
        assertFalse(sample.isFastq());

        sample.setAssemblyFile("assembly.fasta");
        assertEquals(Path.of("assembly.fasta"), sample.getAssemblyFile());
    }
}
