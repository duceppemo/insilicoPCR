package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FastqRecordsTest {

    @TempDir
    Path dir;

    @Test
    void recordsAreReadInOrderWithBlankLinesBetweenThemSkipped() throws IOException {
        Path fastq = Files.writeString(dir.resolve("r.fastq"), "@r1 a/1\nACGT \n+\nIIII\n\n@r2\nTT\n+\nII\n");
        List<FastqRecords.Record> records = new ArrayList<>();

        FastqRecords.forEach(fastq, records::add);

        assertEquals(List.of(new FastqRecords.Record("r1 a/1", "ACGT"), new FastqRecords.Record("r2", "TT")), records);
    }

    @Test
    void aRecordCutShortNamesTheFileAndWhere() throws IOException {
        Path fastq = Files.writeString(dir.resolve("cut.fastq"), "@r1\nACGT\n+\nIIII\n@r2\nTT\n");
        UncheckedIOException error = assertThrows(UncheckedIOException.class, () -> FastqRecords.forEach(fastq, r -> {
        }));
        assertEquals("Malformed FASTQ record in " + fastq + " near: @r2", error.getMessage());
    }
}
