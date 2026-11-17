package ca.canada.inspection.report;

import ca.canada.inspection.pcr.PrimerPanel;
import ca.canada.inspection.pcr.ReportRow;
import ca.canada.inspection.pcr.TestRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The HTML report's script (report.js) in a real browser: headless Chrome, where one is installed
 * (on the CI's Linux machines; skipped otherwise). The page is the one a run writes, with a script
 * of the test's appended that types, clicks and records what is shown.
 */
class HtmlReportScriptTest {

    @TempDir
    Path dir;

    private static String chrome() {
        for (String name : List.of("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")) {
            for (String folder : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(folder, name);
                if (Files.isExecutable(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    /** The report of samples S1 to S13 with an assay 16S each, S11 with another assay inv. */
    private Path report() throws IOException {
        List<ReportRow> rows = new ArrayList<>();
        List<String> samples = new ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            samples.add("S" + i);
            rows.add(TestRows.row("S" + i, "16S", 1, 1500, "c1", ""));
        }
        rows.add(TestRows.row("S11", "inv", 1, 300, "c2", ""));
        PrimerPanel primers = PrimerPanel.of(Map.of("16S-F", "ACGTACGTACGTACGTACGT", "16S-R", "ACGTACGTACGTACGTACGT",
                "inv-F", "ACGTACGTACGTACGTACGT", "inv-R", "ACGTACGTACGTACGTACGT"));
        return HtmlReport.write(new HtmlReport.Input(Files.writeString(dir.resolve("report.tsv"), ""), rows, samples, Set.of(), primers,
                List.of(), new RunDetails("2026-11-15 12:00", "9.9.9", List.of()), List.of(), List.of()));
    }

    /** Runs {@code actions} on the page in Chrome, then gives what they recorded in {@code out}. */
    private String run(Path page, String actions) throws IOException, InterruptedException {
        String chrome = chrome();
        assumeTrue(chrome != null, "no Chrome or Chromium to run the page's script in");
        String html = Files.readString(page, StandardCharsets.UTF_8).replace("</body>", """
                <script>
                (async function () {
                  const wait = ms => new Promise(done => setTimeout(done, ms));
                  const shown = () => Array.from(document.querySelectorAll('#rows tbody tr'))
                      .filter(tr => !tr.hidden).map(tr => tr.dataset.s + '/' + tr.dataset.a).join(',');
                  const type = (id, text) => { const box = document.getElementById(id); box.value = text;
                      box.dispatchEvent(new Event('input')); };
                  const out = [];
                """ + actions + """
                  document.body.setAttribute('data-out', out.join('|'));
                })();
                </script>
                </body>""");
        Path tested = Files.writeString(dir.resolve("tested.html"), html, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(chrome, "--headless=new", "--disable-gpu", "--no-sandbox",
                "--user-data-dir=" + dir.resolve("profile"), "--virtual-time-budget=5000", "--dump-dom",
                tested.toUri().toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String dom = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Chrome did not end");
        Matcher out = Pattern.compile("data-out=\"([^\"]*)\"").matcher(dom);
        assertTrue(out.find(), "the page's script did not run: " + dom.substring(0, Math.min(300, dom.length())));
        return out.group(1).replace("&amp;", "&");
    }

    @Test
    void theAmpliconFilterMatchesWithinACellNotAcrossTwo() throws Exception {
        // S1 then 16S read as one string held "s116s": a search for S11 also showed S1's 16S row.
        String out = run(report(), """
                  type('rowFilter', 'S11'); await wait(400);
                  out.push(shown());
                  type('rowFilter', ''); await wait(400);
                  out.push(document.getElementById('rowCount').textContent);
                """);
        assertEquals("S11/16S,S11/inv|14 amplicons", out);
    }

    @Test
    void aClickOnACellIsNotUndoneByAFilterStillPending() throws Exception {
        // Typed, then a cell clicked before the filter ran: the filter then showed every row again.
        String out = run(report(), """
                  type('rowFilter', 'S1');
                  document.querySelector('#grid button.chip[data-s="S11"][data-a="inv"]').click();
                  await wait(400);
                  out.push(shown());
                  out.push(document.getElementById('rowCount').textContent);
                  out.push(document.getElementById('showAll').hidden);
                """);
        assertEquals("S11/inv|S11 · inv: 1 of 14 shown|false", out);
    }

    @Test
    void aCellOfASampleBeyondTheRowsListedSaysWhereItsRowsAre() throws Exception {
        // The page lists the first 50,000 amplicons (here 100, the same code): a click on a later sample's cell
        // found none, and said "0 of 50000 shown" as if it had none.
        int listed = 100;
        List<ReportRow> rows = new ArrayList<>();
        for (int i = 0; i < listed; i++) {
            rows.add(TestRows.row("S" + (i % 50), "16S", 1, 1500, "c" + i, ""));
        }
        rows.add(TestRows.row("Zlast", "16S", 1, 1500, "z", ""));
        List<String> samples = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            samples.add("S" + i);
        }
        samples.add("Zlast");
        PrimerPanel primers = PrimerPanel.of(Map.of("16S-F", "ACGTACGTACGTACGTACGT", "16S-R", "ACGTACGTACGTACGTACGT"));
        Path page = HtmlReport.write(new HtmlReport.Input(Files.writeString(dir.resolve("report.tsv"), ""), rows, samples, Set.of(), primers,
                List.of(), new RunDetails("2026-11-16 12:00", "9.9.9", List.of()), List.of(), List.of(), listed));

        String out = run(page, """
                  document.querySelector('#grid button.chip[data-s="Zlast"][data-a="16S"]').click();
                  out.push(document.getElementById('rowCount').textContent);
                  document.querySelector('#grid button.chip[data-s="S7"][data-a="16S"]').click();
                  out.push(document.getElementById('rowCount').textContent);
                """);

        assertEquals("Zlast · 16S: not among the amplicons listed here; report.tsv has them|S7 · 16S: 2 of 100 shown", out);
    }

    @Test
    void theSampleFilterIgnoresBlanksAroundWhatIsTyped() throws Exception {
        String out = run(report(), """
                  type('sampleFilter', ' S11 '); await wait(400);
                  out.push(Array.from(document.querySelectorAll('#grid tbody tr')).filter(tr => !tr.hidden)
                      .map(tr => tr.dataset.sample).join(','));
                """);
        assertEquals("S11", out);
    }

    @Test
    void aPrintoutShowsEveryRowWhateverTheFilters() throws Exception {
        // Not printed: the count saying rows are hidden. So every row a filter hid must be printed,
        // by a rule of the page's own print style sheet that matches it.
        String out = run(report(), """
                  type('rowFilter', 'S11'); type('sampleFilter', 'S11'); await wait(400);
                  const print = Array.from(document.styleSheets).flatMap(sheet => Array.from(sheet.cssRules))
                      .filter(rule => rule.media && rule.media.mediaText.includes('print'))
                      .flatMap(rule => Array.from(rule.cssRules));
                  const printed = element => print.some(rule => rule.selectorText && element.matches(rule.selectorText)
                      && rule.style.display === 'table-row' && rule.style.getPropertyPriority('display') === 'important');
                  const hidden = Array.from(document.querySelectorAll('#rows tbody tr, #grid tbody tr')).filter(tr => tr.hidden);
                  out.push(hidden.length > 0);
                  out.push(hidden.filter(tr => !printed(tr)).length);
                  out.push(print.some(rule => rule.selectorText === '.tools' && rule.style.display === 'none'));
                """);
        assertEquals("true|0|true", out);
    }
}
