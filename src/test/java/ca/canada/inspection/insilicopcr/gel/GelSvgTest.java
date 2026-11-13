package ca.canada.inspection.insilicopcr.gel;

import ca.canada.inspection.insilicopcr.gel.LaneBand.BandRect;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelSvgTest {

    private static Document parse(String svg) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
    }

    private static List<Element> elements(Document document, String tag) {
        NodeList nodes = document.getElementsByTagName(tag);
        List<Element> result = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            result.add((Element) nodes.item(i));
        }
        return result;
    }

    private static List<String> titles(Document document) {
        return elements(document, "title").stream().map(Element::getTextContent).toList();
    }

    @Test
    void windowSvgHoldsLanesLadderLabelsAndBands() throws Exception {
        List<String> samples = List.of("A&B <x>", "plain");
        GelLayout layout = GelLayout.window(1000, 700, samples);
        BandRect rect = BandRect.inLane(layout.laneX(1), 300, layout.laneWidth(), 0.5);
        List<GelSvg.Band> bands = List.of(
                new GelSvg.Band(rect, Color.rgb(31, 119, 180), 0.2, "Sample: A&B <x>"),
                new GelSvg.Band(rect, Color.BLACK, 0.9, null));

        String svg = GelSvg.window(layout, samples, bands);
        Document document = parse(svg);
        Element root = document.getDocumentElement();
        assertEquals("svg", root.getTagName());
        assertEquals(GelSvg.format(layout.width()), root.getAttribute("width"));
        assertEquals(GelSvg.format(layout.height()), root.getAttribute("height"));

        // white page + gel + 2 per lane + 15 ladder bands + border + 2 sample bands
        assertEquals(1 + 1 + 2 * 3 + 15 + 1 + 2, elements(document, "rect").size());
        List<Element> texts = elements(document, "text");
        assertEquals(15 + 1 + 2, texts.size());
        assertEquals("20kb", texts.getFirst().getTextContent());
        assertEquals("10", texts.getFirst().getAttribute("font-size"));
        assertEquals("#000000", texts.getFirst().getAttribute("fill"));
        assertEquals("A&B <x>", texts.get(16).getTextContent());
        assertTrue(texts.get(16).getAttribute("transform").startsWith("rotate(45 "));

        List<String> titles = titles(document);
        assertEquals("Ladder 20kb", titles.getFirst());
        assertEquals("Sample: A&B <x>", titles.getLast());

        // Thin and faint bands are made visible in the SVG.
        assertTrue(svg.contains("height=\"1.200\" fill=\"#1f77b4\" opacity=\"0.350\""));
        assertTrue(svg.contains("fill=\"#000000\" opacity=\"0.900\"></rect>"));
        assertTrue(svg.endsWith("</svg>\n"));
    }

    @Test
    void windowSvgDrawsTheGelBackgroundAndLaneColours() throws Exception {
        GelLayout layout = GelLayout.window(800, 500, List.of("s1"));
        Document document = parse(GelSvg.window(layout, List.of("s1"), List.of()));
        List<Element> rects = elements(document, "rect");
        assertEquals("#eeeeee", rects.get(1).getAttribute("fill"));
        assertEquals("#f4f4f4", rects.get(2).getAttribute("fill"));
        assertEquals("white", rects.get(3).getAttribute("fill"));
        assertEquals("3", rects.get(3).getAttribute("width"));
        assertEquals("#e8e8e8", rects.get(4).getAttribute("fill"));
        assertEquals("none", rects.getLast().getAttribute("fill"));
    }

    @Test
    void pageSvgDrawsEachSampleWithItsBandsAndLabel() throws Exception {
        Map<String, List<GelBand>> lanes = new LinkedHashMap<>();
        lanes.put("s1", List.of(new GelBand("s1", "toxA", 312), new GelBand("s1", "stxQ", 118)));
        lanes.put("s2", List.of(new GelBand("s2", "stxQ", 118)));
        List<String> samples = List.of("s1", "s2", "empty");
        Map<String, Color> colors = GelColors.assignGeneColors(lanes);
        GelLayout layout = GelLayout.page(1000, 700, samples);

        Document plain = parse(GelSvg.page(layout, samples, lanes, colors, false));
        // white page + 2 per lane + 15 ladder bands + 3 sample bands + border
        assertEquals(1 + 2 * 4 + 15 + 3 + 1, elements(plain, "rect").size());
        List<Element> texts = elements(plain, "text");
        assertEquals(15 + 3 + 1, texts.size());
        assertEquals("10.000", texts.getFirst().getAttribute("font-size"));
        assertEquals("black", texts.getFirst().getAttribute("fill"));
        assertEquals("s1", texts.get(15).getTextContent());
        assertEquals("14.000", texts.get(15).getAttribute("font-size"));
        assertEquals("Ladder", texts.getLast().getTextContent());
        assertEquals(List.of("#000000"), elements(plain, "rect").stream()
                .filter(rect -> rect.getElementsByTagName("title").getLength() > 0)
                .map(rect -> rect.getAttribute("fill")).distinct().toList());

        String coloured = GelSvg.page(layout, samples, lanes, colors, true);
        assertTrue(coloured.contains("fill=\"" + GelSvg.toHex(colors.get("toxA")) + "\""));
        assertTrue(coloured.contains("fill=\"" + GelSvg.toHex(colors.get("stxQ")) + "\""));
    }

    @Test
    void pageSvgPlacesBandsOnTheScale() throws Exception {
        Map<String, List<GelBand>> lanes = Map.of("s1", List.of(new GelBand("s1", "toxA", 500)));
        GelLayout layout = GelLayout.page(800, 500, List.of("s1"));
        Document document = parse(GelSvg.page(layout, List.of("s1"), lanes, Map.of(), false));
        Element band = elements(document, "rect").stream()
                .filter(rect -> rect.getTextContent().startsWith("Sample: s1"))
                .findFirst().orElseThrow();
        assertEquals(GelSvg.format(layout.laneX(1) + layout.laneWidth() * 0.17), band.getAttribute("x"));
        assertEquals(GelSvg.format(layout.bandY(500) - 1.0), band.getAttribute("y"));
        assertEquals(GelSvg.format(2.0 + GelColors.bandIntensity(1)), band.getAttribute("height"));
        assertEquals(GelSvg.format(GelColors.bandIntensity(1)), band.getAttribute("opacity"));
    }

    @Test
    void numbersUseADotWhateverTheLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            assertEquals("1234.500", GelSvg.format(1234.5));
            assertEquals("-0.125", GelSvg.format(-0.125));
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    void coloursAreWrittenAsHex() {
        assertEquals("#1f77b4", GelSvg.toHex(Color.rgb(31, 119, 180)));
        assertEquals("#000000", GelSvg.toHex(Color.BLACK));
        assertEquals("#ffffff", GelSvg.toHex(Color.rgb(255, 255, 255, 0.2)));
    }

    @Test
    void xmlSpecialCharactersAreEscaped() {
        assertEquals("a &amp; b &lt;c&gt; &quot;d&quot; &apos;e&apos;", GelSvg.escapeXml("a & b <c> \"d\" 'e'"));
        assertEquals("&amp;lt;", GelSvg.escapeXml("&lt;"));
        assertEquals("", GelSvg.escapeXml(null));
        assertEquals("plain", GelSvg.escapeXml("plain"));
    }
}
