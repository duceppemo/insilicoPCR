package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SequenceFileUtilsTest {

    @Test
    void enaStylePairedFastqFilesShareOneSampleName() {
        assertEquals("SRR10729095", SequenceFileUtils.sampleName(Path.of("SRR10729095_1.fastq.gz")));
        assertEquals("SRR10729095", SequenceFileUtils.sampleName(Path.of("SRR10729095_2.fastq.gz")));
    }

    @Test
    void illuminaStylePairedFastqFilesShareOneSampleName() {
        assertEquals("sampleA", SequenceFileUtils.sampleName(Path.of("sampleA_R1.fastq.gz")));
        assertEquals("sampleA", SequenceFileUtils.sampleName(Path.of("sampleA_R2_001.fastq")));
    }

    @Test
    void bareDigitSuffixIsKeptForFastaFiles() {
        // Two assemblies named strain_1/strain_2 are different samples, not a read pair.
        assertEquals("strain_1", SequenceFileUtils.sampleName(Path.of("strain_1.fasta")));
        assertEquals("strain_2", SequenceFileUtils.sampleName(Path.of("strain_2.fna.gz")));
        // The Illumina _R1/_R2 convention is still stripped for FASTA.
        assertEquals("strain", SequenceFileUtils.sampleName(Path.of("strain_R1.fasta")));
    }

    @Test
    void plainFilesKeepTheirBaseName() {
        assertEquals("lmono_EGDe", SequenceFileUtils.sampleName(Path.of("lmono_EGDe.fasta")));
        assertEquals("reads", SequenceFileUtils.sampleName(Path.of("reads.fq.gz")));
    }
}
