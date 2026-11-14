package ca.canada.inspection.gel;

import javafx.scene.paint.Color;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
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

    /** A gel of "A&B <x>" (two bands) and "plain" (none), coloured by gene. */
    private static GelScene scene() {
        List<String> samples = List.of("A&B <x>", "plain");
        GelLayout layout = GelLayout.window(1000, 700, samples);
        List<LaneBand> bands = LaneBand.window(layout, 1, "A&B <x>",
                List.of(new GelBand("A&B <x>", "toxA", 312), new GelBand("A&B <x>", "stxQ", 118)), 2.0);
        Map<String, Color> colors = Map.of("toxA", Color.rgb(31, 119, 180), "stxQ", Color.rgb(214, 39, 40));
        return GelScene.of(layout, samples, bands, band -> colors.get(band.gene()), 10.0);
    }

    @Test
    void theSvgHoldsEveryPartOfTheScene() throws Exception {
        GelScene scene = scene();
        String svg = GelSvg.of(scene);
        Document document = parse(svg);
        Element root = document.getDocumentElement();
        assertEquals("svg", root.getTagName());
        assertEquals(GelSvg.format(scene.width()), root.getAttribute("width"));
        assertEquals(GelSvg.format(scene.height()), root.getAttribute("height"));

        // Four rectangles per band (halo, band, highlight, shadow).
        assertEquals(scene.background().size() + 4 * (15 + 2) + 1, elements(document, "rect").size());
        List<Element> texts = elements(document, "text");
        assertEquals(15 + 3, texts.size());
        assertEquals("20kb", texts.getFirst().getTextContent());
        assertEquals("end", texts.getFirst().getAttribute("text-anchor"));
        assertEquals("10.000", texts.getFirst().getAttribute("font-size"));
        assertEquals("A&B <x>", texts.get(16).getTextContent());
        assertTrue(texts.get(16).getAttribute("transform").startsWith("rotate(45 "));

        List<String> titles = titles(document);
        assertEquals("Ladder 20kb", titles.getFirst());
        assertTrue(titles.getLast().startsWith("Sample: A&B <x>"), titles.getLast());
        assertTrue(svg.endsWith("</svg>\n"));
    }

    @Test
    void coloursAndTheirOpacityAreWrittenApart() throws Exception {
        Document document = parse(GelSvg.of(scene()));
        List<Element> rects = elements(document, "rect");
        assertEquals("#ffffff", rects.get(0).getAttribute("fill"));
        assertEquals("#eeeeee", rects.get(1).getAttribute("fill"));
        assertEquals("", rects.get(1).getAttribute("fill-opacity"));
        assertEquals("none", rects.getLast().getAttribute("fill"));
        assertEquals("#000000", rects.getLast().getAttribute("stroke"));
        GelScene.Band band = scene().bands().getFirst();
        String body = GelSvg.toHex(band.body().fill());
        assertTrue(rects.stream().anyMatch(rect -> rect.getAttribute("fill").equals(body)
                && rect.getAttribute("fill-opacity").equals(GelSvg.format(band.body().fill().getOpacity()))),
                "a translucent band in a shade of its gene's colour");
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
