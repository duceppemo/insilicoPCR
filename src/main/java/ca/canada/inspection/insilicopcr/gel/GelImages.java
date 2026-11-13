package ca.canada.inspection.insilicopcr.gel;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntBinaryOperator;
import java.util.zip.DeflaterOutputStream;

/** Pictures of the synthetic gel as PNG and PDF files, and their default names. */
final class GelImages {
    private GelImages() {
    }

    /**
     * Default PNG of a consolidated report: {@code synthetic_gel_<report name>.png} in the folder
     * above the report's folder (the run's output folder).
     */
    static Path defaultOutput(Path consolidatedReport) {
        Path reportDir = consolidatedReport.getParent();
        Path outputDir = reportDir != null && reportDir.getParent() != null ? reportDir.getParent() : reportDir;
        if (outputDir == null) {
            outputDir = Path.of(".");
        }
        String baseName = consolidatedReport.getFileName().toString()
                .replaceFirst("\\.tsv$", "")
                .replaceAll("[^A-Za-z0-9._-]+", "_");
        return outputDir.resolve("synthetic_gel_" + baseName + ".png");
    }

    /** File name of {@code pngOutput} with its .png extension replaced by {@code extension}. */
    static String withExtension(Path pngOutput, String extension) {
        return pngOutput.getFileName().toString().replaceFirst("\\.png$", "." + extension);
    }

    /** "synthetic_gel_page_001.png" for the first page. */
    static String pageFileName(int pageIndex, String extension) {
        return "synthetic_gel_page_" + String.format("%03d", pageIndex + 1) + "." + extension;
    }

    /** Snapshot of a node, {@code width} x {@code height} rounded up; must run on the FX thread. */
    static BufferedImage snapshot(Node node, double width, double height, boolean whiteBackground) {
        WritableImage image = new WritableImage((int) Math.ceil(width), (int) Math.ceil(height));
        node.snapshot(new SnapshotParameters(), image);
        return toBufferedImage((int) image.getWidth(), (int) image.getHeight(), image.getPixelReader()::getArgb,
                whiteBackground);
    }

    /**
     * Image from ARGB pixels. With {@code whiteBackground}, an opaque RGB image in which every
     * pixel that is not fully opaque becomes white; otherwise an ARGB image.
     */
    static BufferedImage toBufferedImage(int width, int height, IntBinaryOperator argbAt, boolean whiteBackground) {
        BufferedImage buffered = new BufferedImage(width, height, whiteBackground ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = argbAt.applyAsInt(x, y);
                if (whiteBackground && ((argb >>> 24) & 0xff) < 255) {
                    argb = 0xffffffff;
                }
                buffered.setRGB(x, y, argb);
            }
        }
        return buffered;
    }

    /** Writes a PNG, creating the parent folder if needed. */
    static void writePng(BufferedImage image, Path outputFile) throws IOException {
        createParent(outputFile);
        ImageIO.write(image, "png", outputFile.toFile());
    }

    /** Writes a one-page PDF holding the image, creating the parent folder if needed. */
    static void writePdf(BufferedImage image, Path outputFile) throws IOException {
        createParent(outputFile);
        Files.write(outputFile, pdf(image));
    }

    /** Writes a text file in UTF-8, creating the parent folder if needed. */
    static void writeText(String text, Path outputFile) throws IOException {
        createParent(outputFile);
        Files.writeString(outputFile, text, StandardCharsets.UTF_8);
    }

    private static void createParent(Path outputFile) throws IOException {
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /** A minimal PDF 1.4 document with one page the size of the image, in points. */
    static byte[] pdf(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] imageData = compressedRgb(image);
        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        writeAscii(pdf, "%PDF-1.4\n%âãÏÓ\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + width + " " + height + "] /Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>\nendobj\n");
        String content = "q\n" + width + " 0 0 " + height + " 0 0 cm\n/Im0 Do\nQ\n";
        byte[] contentBytes = content.getBytes(StandardCharsets.US_ASCII);
        offsets.add(pdf.size());
        writeAscii(pdf, "4 0 obj\n<< /Length " + contentBytes.length + " >>\nstream\n");
        pdf.write(contentBytes);
        writeAscii(pdf, "endstream\nendobj\n");
        offsets.add(pdf.size());
        writeAscii(pdf, "5 0 obj\n<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height + " /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length " + imageData.length + " >>\nstream\n");
        pdf.write(imageData);
        writeAscii(pdf, "\nendstream\nendobj\n");
        int xref = pdf.size();
        writeAscii(pdf, "xref\n0 6\n0000000000 65535 f \n");
        for (int offset : offsets) {
            writeAscii(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offset));
        }
        writeAscii(pdf, "trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    /** The image's pixels as zlib-compressed 8-bit RGB triplets, row by row from the top. */
    static byte[] compressedRgb(BufferedImage image) throws IOException {
        int width = image.getWidth();
        int[] pixels = new int[width];
        byte[] rgbRow = new byte[width * 3];
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(raw)) {
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, width, 1, pixels, 0, width);
                for (int x = 0; x < width; x++) {
                    rgbRow[3 * x] = (byte) (pixels[x] >> 16);
                    rgbRow[3 * x + 1] = (byte) (pixels[x] >> 8);
                    rgbRow[3 * x + 2] = (byte) pixels[x];
                }
                compressed.write(rgbRow);
            }
        }
        return raw.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) throws IOException {
        out.write(value.getBytes(StandardCharsets.ISO_8859_1));
    }
}
