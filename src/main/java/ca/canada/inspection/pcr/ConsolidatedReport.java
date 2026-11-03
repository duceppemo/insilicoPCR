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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

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
        // Probe requirement is decided per assay from the primer FILE, so standard
        // PCR and qPCR assays can be mixed in one run: an assay whose probe is
        // defined but never hits must stay negative, while a probe-less assay is
        // reported on its primer pair alone. The probe columns are present
        // whenever at least one assay defines a probe.
        Set<String> assaysWithProbe = assaysWithProbe(primers.keySet());
        boolean probeColumns = !assaysWithProbe.isEmpty();
        Path report = nextAvailableReportFile(consolidatedDir);

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(header(probeColumns));
            writer.newLine();
            // TreeMap keeps samples in a stable, alphabetical order.
            for (Map.Entry<String, Sample> sampleEntry : new TreeMap<>(samples).entrySet()) {
                writeSampleResults(writer, sampleEntry.getKey(), sampleEntry.getValue(),
                        options, assaysWithProbe, probeColumns);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write consolidated report: " + report, e);
        }
        return report;
    }

    /** Assay names that define at least one -P probe in the primer file. */
    private static Set<String> assaysWithProbe(Iterable<String> primerIds) {
        Set<String> assays = new TreeSet<>();
        for (String primerId : primerIds) {
            if (BlastHitParser.isProbe(primerId)) {
                assays.add(primerId.substring(0, primerId.lastIndexOf('-')));
            }
        }
        return assays;
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

    private static String header(boolean probeColumns) {
        List<String> columns = new ArrayList<>(List.of(
                "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand"
        ));
        if (probeColumns) {
            columns.addAll(List.of("Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches"));
        }
        return String.join("\t", columns);
    }

    private static void writeSampleResults(BufferedWriter writer, String sampleName, Sample sample,
                                           AmpliconOptions options, Set<String> assaysWithProbe,
                                           boolean probeColumns) throws IOException {
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
            boolean probeRequired = assaysWithProbe.contains(assayName);
            for (String forwardPrimer : group.forward) {
                for (String reversePrimer : group.reverse) {
                    writePrimerPairResults(writer, sampleName, sample, assayName, group,
                            forwardPrimer, reversePrimer, options, probeRequired, probeColumns);
                }
            }
        }
    }

    private static void writePrimerPairResults(BufferedWriter writer, String sampleName, Sample sample,
                                               String assayName, PrimerGroup group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options,
                                               boolean probeRequired, boolean probeColumns)
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

                if (probeRequired) {
                    writeProbeRows(writer, sample, assayName, group, amplicon, baseColumns);
                } else {
                    List<String> columns = new ArrayList<>(baseColumns);
                    if (probeColumns) {
                        // Probe-less assay in a mixed run: pad the probe columns.
                        columns.addAll(List.of("", "", "", ""));
                    }
                    writer.write(String.join("\t", columns));
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
