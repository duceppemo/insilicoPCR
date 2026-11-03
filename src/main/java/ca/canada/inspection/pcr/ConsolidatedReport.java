package ca.canada.inspection.pcr;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.pcr.AmpliconCaller.Amplicon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the final consolidated PCR/qPCR report. Shared by the command-line
 * pipeline and the GUI pipeline so both report identical amplicons.
 */
public final class ConsolidatedReport {

    private ConsolidatedReport() {
    }

    /**
     * Writes {@code report.tsv} (or {@code report(n).tsv} when it already exists)
     * into {@code consolidatedDir} and returns the file written.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options) {
        boolean qPcr = primers.keySet().stream().anyMatch(BlastHitParser::isProbe);
        Path report = nextAvailableReportFile(consolidatedDir);

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(header(qPcr));
            writer.newLine();
            // TreeMap keeps samples in a stable, alphabetical order.
            for (Map.Entry<String, Sample> sampleEntry : new TreeMap<>(samples).entrySet()) {
                writeSampleResults(writer, sampleEntry.getKey(), sampleEntry.getValue(), options, qPcr);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write consolidated report: " + report, e);
        }
        return report;
    }

    private static Path nextAvailableReportFile(Path consolidatedDir) {
        Path report = consolidatedDir.resolve("report.tsv");
        int counter = 1;
        while (Files.exists(report)) {
            report = consolidatedDir.resolve("report(" + counter + ").tsv");
            counter++;
        }
        return report;
    }

    private static String header(boolean qPcr) {
        List<String> columns = new ArrayList<>(List.of(
                "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand"
        ));
        if (qPcr) {
            columns.addAll(List.of("Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches"));
        }
        return String.join("\t", columns);
    }

    private static void writeSampleResults(BufferedWriter writer, String sampleName, Sample sample,
                                           AmpliconOptions options, boolean qPcr) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        if (blastResults.isEmpty()) {
            return;
        }

        Map<String, PrimerGroup> assays = groupByAssay(blastResults.keySet());
        for (Map.Entry<String, PrimerGroup> assayEntry : assays.entrySet()) {
            String assayName = assayEntry.getKey();
            PrimerGroup group = assayEntry.getValue();
            if (group.forward.isEmpty() || group.reverse.isEmpty()) {
                continue;
            }
            for (String forwardPrimer : group.forward) {
                for (String reversePrimer : group.reverse) {
                    writePrimerPairResults(writer, sampleName, sample, assayName, group,
                            forwardPrimer, reversePrimer, options, qPcr);
                }
            }
        }
    }

    private static void writePrimerPairResults(BufferedWriter writer, String sampleName, Sample sample,
                                               String assayName, PrimerGroup group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options, boolean qPcr)
            throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        for (BlastResult forwardHit : blastResults.getOrDefault(forwardPrimer, List.of())) {
            for (BlastResult reverseHit : blastResults.getOrDefault(reversePrimer, List.of())) {
                Amplicon amplicon = AmpliconCaller.call(forwardHit, reverseHit, options).orElse(null);
                if (amplicon == null) {
                    continue;
                }
                String contigDescription = sample.contigs().getOrDefault(amplicon.contig(), "");
                List<String> baseColumns = List.of(
                        sampleName,
                        assayName,
                        amplicon.location(),
                        String.valueOf(amplicon.size()),
                        amplicon.contig(),
                        contigDescription,
                        forwardPrimer,
                        reversePrimer,
                        String.valueOf(effectiveMismatches(forwardHit)),
                        String.valueOf(effectiveMismatches(reverseHit)),
                        String.valueOf(forwardHit.threePrimeMismatches(options.threePrimeWindow())),
                        String.valueOf(reverseHit.threePrimeMismatches(options.threePrimeWindow())),
                        String.valueOf(amplicon.forwardStrand())
                );

                if (qPcr) {
                    writeProbeRows(writer, sample, assayName, group, amplicon, baseColumns);
                } else {
                    writer.write(String.join("\t", baseColumns));
                    writer.newLine();
                }
            }
        }
    }

    /** Mismatches as counted against the -m budget: substitutions, indels, and trimmed 3' bases. */
    private static int effectiveMismatches(BlastResult hit) {
        return hit.mismatch() + hit.gaps() + hit.threePrimeUncovered();
    }

    private static void writeProbeRows(BufferedWriter writer, Sample sample, String assayName, PrimerGroup group,
                                       Amplicon amplicon, List<String> baseColumns) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        for (String probeId : group.probe) {
            for (BlastResult probeHit : blastResults.getOrDefault(probeId, List.of())) {
                if (!probeHit.queryID().equals(amplicon.contig())) {
                    continue;
                }
                int probeStart = Math.min(probeHit.qstart(), probeHit.qend());
                int probeEnd = Math.max(probeHit.qstart(), probeHit.qend());
                // The probe must anneal within the amplified product.
                if (!amplicon.contains(probeStart, probeEnd)) {
                    continue;
                }
                List<String> columns = new ArrayList<>(baseColumns);
                columns.add(probeId);
                columns.add(probeStart + "-" + probeEnd);
                columns.add(String.valueOf(probeEnd - probeStart + 1));
                columns.add(String.valueOf(probeHit.mismatch() + probeHit.gaps()));
                writer.write(String.join("\t", columns));
                writer.newLine();
            }
        }
    }

    /**
     * Groups expanded primer IDs by assay name. {@code assay-F}, {@code assay-R_1}
     * and {@code assay-P} all land under {@code assay} with their full IDs kept
     * in the forward/reverse/probe buckets.
     */
    private static Map<String, PrimerGroup> groupByAssay(Iterable<String> primerIds) {
        Map<String, PrimerGroup> groups = new TreeMap<>();
        for (String primerId : primerIds) {
            int lastDash = primerId.lastIndexOf('-');
            if (lastDash <= 0 || lastDash == primerId.length() - 1) {
                throw new IllegalArgumentException("Primer ID must end with -F, -R, or -P: " + primerId);
            }
            String assayName = primerId.substring(0, lastDash);
            char type = primerId.charAt(lastDash + 1);
            PrimerGroup group = groups.computeIfAbsent(assayName, ignored -> new PrimerGroup());
            switch (type) {
                case 'F' -> group.forward.add(primerId);
                case 'R' -> group.reverse.add(primerId);
                case 'P' -> group.probe.add(primerId);
                default -> throw new IllegalArgumentException("Primer ID must end with -F, -R, or -P: " + primerId);
            }
        }
        return groups;
    }

    private static final class PrimerGroup {
        private final List<String> forward = new ArrayList<>();
        private final List<String> reverse = new ArrayList<>();
        private final List<String> probe = new ArrayList<>();
    }
}
