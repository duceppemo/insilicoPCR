package ca.canada.inspection.dispatchpcr;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.pcr.ReadLevelCaller;
import ca.canada.inspection.pipeline.PipelineConfig;
import ca.canada.inspection.pipeline.TadpoleOptions;
import ca.canada.inspection.util.InputCheck;
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

    private PipelineConfig parse(String... extra) throws ParseException, IOException {
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
    void aWrongValueIsNamedAsTheCommandLineNamesIt() {
        // It said "minAmpliconLength (6000) is larger than maxAmpliconLength (5000)": names no option has.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> parse("--min-length", "6000"));
        assertEquals("--min-length (6000) is larger than -l (--max-length, 5000): no product could be reported",
                error.getMessage());
        assertEquals("-t (threads) must be at least 1, not 0",
                assertThrows(IllegalArgumentException.class, () -> parse("-t", "0")).getMessage());
    }

    @Test
    void theWindowsDefaultsAreTheCommandLines() throws Exception {
        // A run from the window with its settings untouched is the run of the command line given
        // the same mismatches: a default changed on one side only would no longer pass.
        PipelineConfig cli = parse("-m", "2");
        var amplicon = ca.canada.inspection.insilicopcr.ui.AmpliconSettingsDialog.AmpliconSettings.DEFAULTS;
        var fastq = ca.canada.inspection.insilicopcr.ui.FastqSettingsDialog.FastqSettings.DEFAULTS;

        assertEquals(cli.ampliconOptions(), amplicon.toOptions(2));
        assertEquals(cli.tadpole(), fastq.tadpole());
        assertEquals(cli.readMinFraction(), fastq.readMinFraction());
        assertEquals(cli.readExtension(), fastq.readExtension());
        assertEquals(cli.bbtoolsMemoryGiB(), fastq.bbtoolsMemoryGiB());
    }

    @Test
    void defaultsMatchDocumentedValues() throws Exception {
        PipelineConfig config = parse();
        AmpliconOptions options = config.ampliconOptions();
        assertEquals(0, options.maxTotalMismatches());
        assertEquals(0, options.maxProbeMismatches());
        assertEquals(AmpliconOptions.DEFAULT_THREE_PRIME_WINDOW, options.threePrimeWindow());
        assertEquals(AmpliconOptions.DEFAULT_THREE_PRIME_PENALTY, options.maxThreePrimePenalty());
        assertEquals(AmpliconOptions.DEFAULT_FIVE_PRIME_TRUNCATION, options.maxFivePrimeTruncation());
        assertEquals(AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH, options.maxAmpliconLength());
        assertEquals(0, options.minAmpliconLength());
        assertEquals(PipelineConfig.DEFAULT_EVALUE, config.evalue());
        assertTrue(config.threads() >= 1);
        assertFalse(config.gel());
        // The command line sets only what it is given: its defaults are the application's.
        PipelineConfig builderDefaults = PipelineConfig.builder(input, dir.resolve("out"), primers).build();
        assertEquals(builderDefaults.ampliconOptions(), options);
        assertEquals(builderDefaults.inputCheck(), config.inputCheck());
        assertEquals(builderDefaults.tadpole(), config.tadpole());
    }

    @Test
    void probeMismatchesFollowTheMismatchesUnlessGiven() throws Exception {
        assertEquals(2, parse("-m", "2").ampliconOptions().maxProbeMismatches());
        assertEquals(3, parse("-m", "2", "--probe-mismatches", "3").ampliconOptions().maxProbeMismatches());
        assertEquals(InputCheck.Depth.QUICK, parse("--input-check", "quick").inputCheck());
    }

    @Test
    void theHelpShowsTheDefaultsInForce() {
        String help = Dispatcher.options().getOption("max-length").getDescription()
                + Dispatcher.options().getOption("input-check").getDescription();
        assertTrue(help.contains("default: " + AmpliconOptions.DEFAULT_MAX_AMPLICON_LENGTH), help);
        assertTrue(help.endsWith("default: " + PipelineConfig.DEFAULT_INPUT_CHECK.option()), help);
    }

    @Test
    void readMinFractionDefaultsAndParses() throws Exception {
        assertEquals(ReadLevelCaller.DEFAULT_MIN_SUPPORT_FRACTION, parse().readMinFraction());
        assertEquals(0.5, parse("--read-min-fraction", "0.5").readMinFraction());
        assertEquals(0.0, parse("--read-min-fraction", "0").readMinFraction());
        assertEquals(1.0, parse("--read-min-fraction", "1").readMinFraction());
        assertThrows(IllegalArgumentException.class, () -> parse("--read-min-fraction", "1.5"));
        assertThrows(IllegalArgumentException.class, () -> parse("--read-min-fraction", "-0.1"));
        assertThrows(IllegalArgumentException.class, () -> parse("--read-min-fraction", "30%"));
    }

    @Test
    void readExtensionDefaultsToUnreportedAssaysAndParses() throws Exception {
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, parse().readExtension());
        assertEquals(ReadExtensionCaller.Mode.OFF, parse("--read-extension", "off").readExtension());
        assertEquals(ReadExtensionCaller.Mode.UNREPORTED, parse("--read-extension", "unreported").readExtension());
        assertEquals(ReadExtensionCaller.Mode.TARGET, parse("--read-extension", "TARGET").readExtension());
        IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
                () -> parse("--read-extension", "on"));
        assertEquals("--read-extension must be off, unreported or target, got: on", bad.getMessage());
        assertTrue(Dispatcher.options().getOption("read-extension").getDescription().endsWith("default: unreported"));
        assertFalse(Dispatcher.options().hasOption("no-read-extension"));
    }

    @Test
    void tadpoleSettingsDefaultAndParse() throws Exception {
        assertEquals(TadpoleOptions.DEFAULTS, parse().tadpole());
        PipelineConfig config = parse("--tadpole-k", "25", "--tadpole-min-seed", "3",
                "--tadpole-min-cov", "2", "--tadpole-min-contig", "100");
        assertEquals(new TadpoleOptions(25, 3, 2, 100), config.tadpole());
        assertThrows(IllegalArgumentException.class, () -> parse("--tadpole-k", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--tadpole-min-contig", "-1"));
        // -1 was told ">= 0" and 0 ">= 1": both now the bound itself.
        for (String value : new String[]{"0", "-1"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> parse("--tadpole-min-contig", value));
            assertEquals("Invalid Tadpole setting: --tadpole-min-contig must be >= 1, got: " + value, error.getMessage());
        }
    }

    @Test
    void allFlagsAreParsed() throws Exception {
        PipelineConfig config = parse("-t", "3", "-m", "2", "--probe-mismatches", "3", "-e", "1e3",
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

    @Test
    void theInputCheckIsFullUnlessAskedOtherwise() throws Exception {
        assertEquals(InputCheck.Depth.FULL, parse().inputCheck());
        assertEquals(InputCheck.Depth.QUICK, parse("--input-check", "quick").inputCheck());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parse("--input-check", "off"));
        assertEquals("--input-check must be full or quick, not: off", error.getMessage());
    }

    @Test
    void theBbtoolsMemoryIsWorkedOutUnlessGiven() throws Exception {
        assertEquals(PipelineConfig.AUTOMATIC_BBTOOLS_MEMORY, parse().bbtoolsMemoryGiB());
        assertEquals(24, parse("--bbtools-memory", "24").bbtoolsMemoryGiB());
        // --memory since 1.0 (it sizes BLAST too); the old name still works, but not both.
        assertEquals(12, parse("--memory", "12").bbtoolsMemoryGiB());
        assertEquals("--memory and --bbtools-memory are one option: give it once", assertThrows(
                IllegalArgumentException.class, () -> parse("--memory", "12", "--bbtools-memory", "12")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("--bbtools-memory", "0"));
        assertThrows(IllegalArgumentException.class, () -> parse("--bbtools-memory", "lots"));
    }

    @Test
    void aPathThatIsNoPathSaysWhyOnWindows() {
        // "C:\my data\" -p ...: the backslash keeps the quote; -i took the rest of the line, and the error
        // was "Unexpected argument(s)" or "Illegal char <">".
        String quoted = "C:\\my data\" -p C:\\my";
        String message = CliConfig.notAPath("input", quoted);
        assertTrue(message.startsWith("--input " + quoted + ": a quote is in the path."), message);
        assertTrue(message.contains("leave out the last backslash"), message);
        // A letter outside the code page reaches Java as ?: "Illegal char <?> at index 55".
        assertTrue(CliConfig.notAPath("output", "C:\\data\\?? ?\\out").contains("reaches the Java runtime as ?"));
        assertEquals("--primers <x>: not a valid path", CliConfig.notAPath("primers", "<x>"));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aQuoteInAPathIsAWrongCommandLineOnWindows() throws ParseException {
        CommandLine cmd = new DefaultParser().parse(Dispatcher.options(),
                new String[]{"-i", "C:\\my data\" -p C:\\my", "-o", "out"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CliConfig.checkQuotes(cmd));
        assertTrue(error.getMessage().contains("leave out the last backslash"), error.getMessage());
    }

    @Test
    void anOptionGivenTwiceIsRefused() throws ParseException, IOException {
        // -t 2 -t 3 ran with 2, the first, without a word.
        parse();
        CommandLine cmd = new DefaultParser().parse(Dispatcher.options(), new String[]{"-i", input.toString(), "-o",
                dir.resolve("out").toString(), "-p", primers.toString(), "-t", "2", "--threads", "3"});
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CliConfig.checkGivenOnce(cmd));
        assertEquals("--threads is given 2 times: give it once", error.getMessage());
    }

    @Test
    void numbersTheProgramCannotHoldAreNamedAsGiven() {
        // 1e-400 reads as 0: the message said "got: 0.0"; --bbtools-memory -1 said ">= 0", though 0 is refused too;
        // Tadpole's settings were named by Tadpole's names (minContig), not the options'.
        assertEquals("--evalue 1e-400 is too small for a number of the program (it reads as 0): give 1e-300 or more",
                assertThrows(IllegalArgumentException.class, () -> parse("-e", "1e-400")).getMessage());
        assertEquals("--bbtools-memory must be at least 1 GB; leave it out for automatic",
                assertThrows(IllegalArgumentException.class, () -> parse("--bbtools-memory", "-1")).getMessage());
        assertEquals("Invalid Tadpole setting: --tadpole-min-contig must be >= 1, got: 0",
                assertThrows(IllegalArgumentException.class, () -> parse("--tadpole-min-contig", "0")).getMessage());
        // Below 0 or 0, as given and named as the option ("evalue must be ..., got: -1.0").
        assertEquals("--evalue must be a number greater than 0, e.g. 1e5, got: -1",
                assertThrows(IllegalArgumentException.class, () -> parse("-e", "-1")).getMessage());
        // An integer too large for one is said to be so, not "must be an integer".
        assertEquals("--mismatches is too large, got: 99999999999 (at most 2147483647)",
                assertThrows(IllegalArgumentException.class, () -> parse("-m", "99999999999")).getMessage());
        assertEquals("--mismatches is too small, got: -99999999999 (at least -2147483648)",
                assertThrows(IllegalArgumentException.class, () -> parse("-m", "-99999999999")).getMessage());
        assertEquals("--threads must be an integer, got: 2.5",
                assertThrows(IllegalArgumentException.class, () -> parse("-t", "2.5")).getMessage());
    }

    @Test
    void theTmGateIsOffUnlessAnAnnealingTemperatureIsGiven() throws Exception {
        assertEquals(null, parse().ampliconOptions().tmGate());
        assertEquals(ca.canada.inspection.pcr.TmGate.at(60), parse("--anneal-temp", "60").ampliconOptions().tmGate());
        assertEquals(new ca.canada.inspection.pcr.TmGate(58, 3, 8, 40, 1.5, 0.6, 50),
                parse("--anneal-temp", "58", "--tm-margin", "3", "--start-margin", "8", "--na", "40", "--mg", "1.5",
                        "--dntp", "0.6", "--oligo-conc", "50").ampliconOptions().tmGate());
        IllegalArgumentException alone = assertThrows(IllegalArgumentException.class, () -> parse("--mg", "2"));
        assertTrue(alone.getMessage().contains("--anneal-temp"), alone.getMessage());
        assertThrows(IllegalArgumentException.class, () -> parse("--anneal-temp", "hot"));
    }
}
