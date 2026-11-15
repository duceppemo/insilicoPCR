package ca.canada.inspection.pipeline;

import ca.canada.inspection.pcr.AmpliconOptions;
import ca.canada.inspection.pcr.ReadExtensionCaller;
import ca.canada.inspection.report.RunDetails;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one description of a run's settings, for the HTML report, QAlog.txt and the run's log. */
class RunSettingsTest {

    private static PipelineConfig.Builder config() {
        return PipelineConfig.builder(Path.of("in"), Path.of("out"), Path.of("primers.fasta"));
    }

    @Test
    void everySettingThatDecidesTheResultsIsListed() {
        PipelineConfig config = config().threads(3).evalue(1e-5)
                .ampliconOptions(AmpliconOptions.builder().mismatches(1).maxLength(0).build()).build();

        List<String> lines = RunSettings.lines(config, false);

        assertEquals(List.of("Settings:",
                "  Primer mismatches (-m): 1",
                "  Probe mismatches (--probe-mismatches): 1",
                "  3' window / max penalty (--3p-window / --3p-penalty): 5 / 0",
                "  5' truncation allowed (--5p-truncation): 5",
                "  Product length (--min-length to --max-length): 0 to no limit",
                "  BLAST e-value (-e): 0.00001",
                "  Threads (-t): 3",
                "  Input check (--input-check): full"), lines);
    }

    @Test
    void aRunWithReadsListsTheFastqSettingsToo() {
        List<RunDetails.Setting> settings = RunSettings.of(config().build(), true);

        assertEquals("k=31 mincountseed=2 mincov=1 mincontig=60", value(settings, "Tadpole assembly"));
        assertEquals("at least 2 reads or read pairs and 30% of informative reads", value(settings, "Read-level calls"));
        String extension = value(settings, "Read extension");
        assertTrue(extension.startsWith("unreported: for assays in no contig and in no read, when reads carry both"
                + " primers, up to 150 reads per primer"), extension);
        // As the command runs it, not a copy that could drift from it.
        assertTrue(extension.contains("Tadpole mode=extend k=31 " + String.join(" ", BbtoolsCommands.EXTEND_OPTIONS)),
                extension);
        assertTrue(extension.contains("at least 2 extended reads seeded from each primer"), extension);
        assertTrue(extension.contains("read pairs holding each of its windows (the largest window of 100, 150, ..."
                + " bases whose median is held by 10 pairs; none held by fewer than 2 or 10% of the median), and no"
                + " repeat that long with another copy in the sample"), extension);
        assertTrue(RunSettings.readExtension(ReadExtensionCaller.Mode.TARGET).startsWith("target: for assays in no"
                + " contig and in no read, and assays without a product of their declared target size"));
        assertEquals("off", RunSettings.readExtension(ReadExtensionCaller.Mode.OFF));
        assertFalse(RunSettings.of(config().build(), false).stream()
                .anyMatch(setting -> setting.label().contains("FASTQ")));
    }

    @Test
    void theHtmlReportGetsThePathsThenTheSettings() {
        RunDetails details = RunSettings.details("9.9.9", config().build(), false);

        assertEquals("9.9.9", details.version());
        assertEquals(List.of("Input", "Primer file", "Output folder", "Primer mismatches (-m)"),
                details.settings().subList(0, 4).stream().map(RunDetails.Setting::label).toList());
    }

    @Test
    void numbersReadAsWritten() {
        assertEquals("100000", RunSettings.number(1e5));
        assertEquals("0.05", RunSettings.number(0.05));
        assertEquals("1.0E-30", RunSettings.number(1e-30));
        assertEquals("0", RunSettings.number(0));
    }

    private static String value(List<RunDetails.Setting> settings, String labelStart) {
        return settings.stream().filter(setting -> setting.label().startsWith(labelStart)).findFirst()
                .orElseThrow().value();
    }

    @Test
    void theBbtoolsMemoryIsListedWithTheFastqSettings() {
        assertEquals("24 GB for the programs running at once", value(RunSettings.of(config().bbtoolsMemory(24).build(),
                true), "BBTools memory"));
        assertTrue(value(RunSettings.of(config().build(), true), "BBTools memory").startsWith("automatic"));
    }
}
