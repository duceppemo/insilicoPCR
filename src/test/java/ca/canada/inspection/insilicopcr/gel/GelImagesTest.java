package ca.canada.inspection.insilicopcr.gel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GelImagesTest {

    @TempDir
    Path dir;

    @Test
    void defaultOutputGoesNextToTheReportFolder() {
        assertEquals(Path.of("run", "synthetic_gel_report.png"),
                GelImages.defaultOutput(Path.of("run", "consolidated_report", "report.tsv")));
        assertEquals(Path.of("run", "synthetic_gel_report.png"), GelImages.defaultOutput(Path.of("run", "report.tsv")));
        assertEquals(Path.of(".", "synthetic_gel_report.png"), GelImages.defaultOutput(Path.of("report.tsv")));
    }

    @Test
    void defaultOutputNameIsMadeFileSystemSafe() {
        assertEquals("synthetic_gel_my_report_1_.png",
                GelImages.defaultOutput(Path.of("a", "b", "my report (1).tsv")).getFileName().toString());
        assertEquals("synthetic_gel_report.tsv.bak.png",
                GelImages.defaultOutput(Path.of("a", "b", "report.tsv.bak")).getFileName().toString());
    }

    @Test
    void otherFormatsReuseThePngName() {
        assertEquals("synthetic_gel_report.svg", GelImages.withExtension(Path.of("x", "synthetic_gel_report.png"), "svg"));
        assertEquals("synthetic_gel_report.pdf", GelImages.withExtension(Path.of("synthetic_gel_report.png"), "pdf"));
        assertEquals("gel.png.old", GelImages.withExtension(Path.of("gel.png.old"), "svg"));
    }

    @Test
    void pageFileNamesAreNumberedFromOne() {
        assertEquals("synthetic_gel_page_001.png", GelImages.pageFileName(0, "png"));
        assertEquals("synthetic_gel_page_124.pdf", GelImages.pageFileName(123, "pdf"));
        assertEquals("synthetic_gel_page_1000.svg", GelImages.pageFileName(999, "svg"));
    }

    @Test
    void argbImageKeepsTransparency() {
        int[] pixels = {0x00000000, 0x80ff0000, 0xff00ff00, 0xff0000ff};
        BufferedImage image = GelImages.toBufferedImage(2, 2, (x, y) -> pixels[y * 2 + x], false);
        assertEquals(BufferedImage.TYPE_INT_ARGB, image.getType());
        assertEquals(0x80ff0000, image.getRGB(1, 0));
        assertEquals(0xff0000ff, image.getRGB(1, 1));
    }

    @Test
    void whiteBackgroundImageTurnsTranslucentPixelsWhite() {
        int[] pixels = {0x00000000, 0x80ff0000, 0xff00ff00, 0xff0000ff};
        BufferedImage image = GelImages.toBufferedImage(2, 2, (x, y) -> pixels[y * 2 + x], true);
        assertEquals(BufferedImage.TYPE_INT_RGB, image.getType());
        assertEquals(0xffffffff, image.getRGB(0, 0));
        assertEquals(0xffffffff, image.getRGB(1, 0));
        assertEquals(0xff00ff00, image.getRGB(0, 1));
        assertEquals(0xff0000ff, image.getRGB(1, 1));
    }

    private static BufferedImage sample() {
        BufferedImage image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        int[] rgb = {0x102030, 0xffffff, 0x000000, 0xff0000, 0x00ff00, 0x0000ff};
        for (int i = 0; i < rgb.length; i++) {
            image.setRGB(i % 3, i / 3, rgb[i]);
        }
        return image;
    }

    @Test
    void pngIsWrittenIntoANewFolder() throws IOException {
        Path png = dir.resolve("new").resolve("gel.png");
        GelImages.writePng(sample(), png);
        BufferedImage read = ImageIO.read(png.toFile());
        assertEquals(3, read.getWidth());
        assertEquals(2, read.getHeight());
        assertEquals(0xff102030, read.getRGB(0, 0));
        assertEquals(0xff0000ff, read.getRGB(2, 1));
    }

    @Test
    void textIsWrittenInUtf8IntoANewFolder() throws IOException {
        Path svg = dir.resolve("svg").resolve("gel.svg");
        GelImages.writeText("<svg>échantillon</svg>\n", svg);
        assertEquals("<svg>échantillon</svg>\n", Files.readString(svg, StandardCharsets.UTF_8));
    }

    @Test
    void compressedRgbHoldsThePixelsRowByRow() throws IOException {
        byte[] raw = inflate(GelImages.compressedRgb(sample()));
        assertArrayEquals(new byte[]{
                0x10, 0x20, 0x30, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0, 0, 0,
                (byte) 0xff, 0, 0, 0, (byte) 0xff, 0, 0, 0, (byte) 0xff}, raw);
    }

    @Test
    void pdfIsOnePageTheSizeOfTheImage() throws IOException {
        byte[] pdf = GelImages.pdf(sample());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("%PDF-1.4\n"));
        assertTrue(text.endsWith("%%EOF\n"));
        assertTrue(text.contains("/MediaBox [0 0 3 2]"));
        assertTrue(text.contains("q\n3 0 0 2 0 0 cm\n/Im0 Do\nQ\n"));
        assertTrue(text.contains("/Width 3 /Height 2 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode"));
    }

    @Test
    void pdfCrossReferenceTablePointsAtEachObject() throws IOException {
        byte[] pdf = GelImages.pdf(sample());
        String text = new String(pdf, StandardCharsets.ISO_8859_1);

        Matcher startxref = Pattern.compile("startxref\n(\\d+)\n%%EOF\n$").matcher(text);
        assertTrue(startxref.find());
        int xref = Integer.parseInt(startxref.group(1));
        assertTrue(text.startsWith("xref\n0 6\n", xref));

        Matcher entries = Pattern.compile("(\\d{10}) 00000 n \n").matcher(text.substring(xref));
        int object = 1;
        while (entries.find()) {
            int offset = Integer.parseInt(entries.group(1));
            assertTrue(text.startsWith(object + " 0 obj\n", offset), "object " + object + " at " + offset);
            object++;
        }
        assertEquals(6, object);
    }

    @Test
    void pdfImageStreamInflatesToThePixels() throws IOException {
        BufferedImage image = sample();
        byte[] pdf = GelImages.pdf(image);
        String text = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher length = Pattern.compile("/FlateDecode /Length (\\d+) >>\nstream\n").matcher(text);
        assertTrue(length.find());
        int start = length.end();
        int size = Integer.parseInt(length.group(1));
        assertTrue(text.startsWith("\nendstream", start + size));
        byte[] stream = new byte[size];
        System.arraycopy(pdf, start, stream, 0, size);
        assertArrayEquals(inflate(GelImages.compressedRgb(image)), inflate(stream));
    }

    @Test
    void pdfIsWrittenIntoANewFolder() throws IOException {
        Path pdf = dir.resolve("pdf").resolve("gel.pdf");
        GelImages.writePdf(sample(), pdf);
        assertArrayEquals(GelImages.pdf(sample()), Files.readAllBytes(pdf));
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return in.readAllBytes();
        }
    }
}
