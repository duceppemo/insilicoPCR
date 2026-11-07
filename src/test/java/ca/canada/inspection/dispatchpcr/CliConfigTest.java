package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pipeline.TadpoleOptions;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliConfigTest {

    @TempDir
    Path dir;

    private Path input;
    private Path primers;

    private CliConfig parse(String... extra) throws ParseException, IOException {
        if (input == null) {
            input = Files.writeString(dir.resolve("genome.fasta"), ">c1\nACGT\n");
            primers = Files.writeString(dir.resolve("primers.fasta"), ">a-F\nACGT\n>a-R\nACGT\n");
        }
        String[] base = {"-i", input.toString(), "-o", dir.resolve("out").toString(), "-p", primers.toString()};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        CommandLine cmd = new DefaultParser().parse(Dispatcher.options(), args);
        return CliConfig.from(cmd);
    }

    @Test
    void defaultsMatchDocumentedValues() throws Exception {
        CliConfig config = parse();
        AmpliconOptions options = config.ampliconOptions();
        assertEquals(0, options.maxTotalMismatches());
        assertEquals(0, options.maxProbeMismatches());
        assertEquals(AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW, options.threePrimeWindow());
        assertEquals(AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY, options.maxThreePrimePenalty());
        assertEquals(AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION, options.maxFivePrimeTruncation());
        assertEquals(AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH, options.maxAmpliconLength());
        assertEquals(0, options.minAmpliconLength());
        assertEquals(CliConfig.DEFAULT_EVALUE, config.evalue());
        assertTrue(config.threads() >= 1);
        assertFalse(config.gel());
    }

    @Test
    void tadpoleSettingsDefaultAndParse() throws Exception {
        assertEquals(TadpoleOptions.DEFAULTS, parse().tadpole());
        CliConfig config = parse("--tadpole-k", "25", "--tadpole-min-seed", "3",
                "--tadpole-min-cov", "2", "--tadpole-min-contig", "100");
        assertEquals(new TadpoleOptions(25, 3, 2, 100), config.tadpole());
        assertThrows(IllegalArgumentException.class, () -> parse("--tadpole-k", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--tadpole-min-contig", "-1"));
    }

    @Test
    void allFlagsAreParsed() throws Exception {
        CliConfig config = parse("-t", "3", "-m", "2", "--probe-mismatches", "3", "-e", "1e3",
                "-l", "0", "--min-length", "80", "--3p-window", "6", "--3p-penalty", "4",
                "--5p-truncation", "1", "-g");
        assertEquals(3, config.threads());
        assertEquals(1e3, config.evalue());
        assertTrue(config.gel());
        AmpliconOptions options = config.ampliconOptions();
        assertEquals(2, options.maxTotalMismatches());
        assertEquals(3, options.maxProbeMismatches());
        assertEquals(0, options.maxAmpliconLength());
        assertEquals(80, options.minAmpliconLength());
        assertEquals(6, options.threePrimeWindow());
        assertEquals(4, options.maxThreePrimePenalty());
        assertEquals(1, options.maxFivePrimeTruncation());
    }

    @Test
    void probeMismatchesFollowsMismatchesByDefault() throws Exception {
        assertEquals(2, parse("-m", "2").ampliconOptions().maxProbeMismatches());
    }

    @Test
    void invalidNumbersAreRejectedWithClearMessages() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> parse("-m", "abc"));
        assertThrows(IllegalArgumentException.class, () -> parse("-m", "-1"));
        assertThrows(IllegalArgumentException.class, () -> parse("-e", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("-t", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--min-length", "6000")); // > max 5000
    }

    @Test
    void missingInputsAreRejected() throws Exception {
        Path primersFile = Files.writeString(dir.resolve("p.fasta"), ">a-F\nACGT\n");
        CommandLine noInput = new DefaultParser().parse(Dispatcher.options(),
                new String[]{"-o", dir.toString(), "-p", primersFile.toString()});
        assertThrows(IllegalArgumentException.class, () -> CliConfig.from(noInput));

        CommandLine ghostInput = new DefaultParser().parse(Dispatcher.options(),
                new String[]{"-i", dir.resolve("ghost.fasta").toString(), "-o", dir.toString(), "-p", primersFile.toString()});
        assertThrows(IllegalArgumentException.class, () -> CliConfig.from(ghostInput));

        CommandLine primerIsDir = new DefaultParser().parse(Dispatcher.options(),
                new String[]{"-i", primersFile.toString(), "-o", dir.toString(), "-p", dir.toString()});
        assertThrows(IllegalArgumentException.class, () -> CliConfig.from(primerIsDir));
    }
}
