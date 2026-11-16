package ca.canada.inspection.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CirclesTest {

    @TempDir
    Path directory;

    private static String bases(int length, long seed) {
        StringBuilder bases = new StringBuilder();
        Random random = new Random(seed);
        while (bases.length() < length) {
            bases.append("ACGT".charAt(random.nextInt(4)));
        }
        return bases.toString();
    }

    @Test
    void aSequenceIsACircleWhenItsHeaderSaysSoOrTheAssemblyFoundItClosed() throws IOException {
        String plasmid = bases(5000, 1);
        String chromosome = bases(3000, 2);
        String loop = bases(1800, 3);
        String repeatLoop = bases(150, 4);
        String unmarked = bases(2000, 5);
        String unflagged = bases(1500, 7);
        Path fasta = Files.writeString(directory.resolve("assembly.fasta"),
                ">plasmid1 length=5000 depth=12.5x circular=true\n" + plasmid + "\n"
                        + ">chr [organism=Brucella] [topology=circular]\n" + chromosome + "\n"
                        + ">contig_3,len=1802,cov=38.2,class=long/medium/self-loop,min=23\n" + loop
                        + loop.substring(0, 2) + "\n"
                        + ">contig_9,len=180,class=short/self-loop\n" + repeatLoop + repeatLoop.substring(0, 30) + "\n"
                        + ">linear1 circular=false\n" + unmarked + "\n"
                        + ">circular=true\n" + unmarked + "\n"
                        + ">contig_4,len=2000,class=long/medium/unanchored\n" + unmarked + "\n"
                        + ">contig_5,len=1526,cov=30.2,class=long/medium/unanchored\n" + unflagged
                        + unflagged.substring(0, 26) + "\n"
                        + ">contig_6,len=1505,cov=30.2,class=long/medium/unanchored\n" + unflagged
                        + unflagged.substring(0, 5) + "\n"
                        + ">tiny circular=true\nACGTACGTAC\n");

        Map<String, Integer> circles = Circles.of(fasta, 31);

        // A Tadpole circle without the start it repeats at its end (2 bases, or k - 1 for a repeat's loop); one not
        // called a self-loop, only when it repeats 12 or more (5 alike is as likely chance).
        assertEquals(Map.of("plasmid1", 5000, "chr", 3000, "contig_3,len=1802,cov=38.2,class=long/medium/self-loop,min=23",
                1800, "contig_9,len=180,class=short/self-loop", 150,
                "contig_5,len=1526,cov=30.2,class=long/medium/unanchored", 1500), circles);
    }

    @Test
    void aMarkedCircleIsTakenAsItIsWhateverItsEndsShare() throws IOException {
        // A complete genome is written once round: its first bases again at its end are its own.
        String plasmid = bases(4000, 6);
        Path fasta = Files.writeString(directory.resolve("plasmid.fasta.gz").resolveSibling("plasmid.fasta"),
                ">p1 circular=true\n" + plasmid + plasmid.substring(0, 20) + "\n");

        assertEquals(Map.of("p1", 4020), Circles.of(fasta, 31));
    }
}
