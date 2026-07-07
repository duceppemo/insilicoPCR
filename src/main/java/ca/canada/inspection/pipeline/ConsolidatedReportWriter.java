package ca.canada.inspection.pipeline;

import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the final consolidated PCR/qPCR report. */
final class ConsolidatedReportWriter {
    void write(PipelineContext context) {
        boolean qPcr = hasProbePrimers(context.primers());
        Path report = context.directories().consolidated().resolve("report.tsv");

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(consolidatedHeader(qPcr));
            writer.newLine();

            for (Map.Entry<String, Sample> sampleEntry : context.samples().entrySet()) {
                writeSampleResults(writer, sampleEntry.getKey(), sampleEntry.getValue(), context.primers(), qPcr);
            }
        } catch (IOException e) {
            throw new PipelineException("Could not write consolidated report: " + report, e);
        }
    }

    private static boolean hasProbePrimers(Map<String, String> primers) {
        return primers.keySet().stream()
                .map(PrimerId::parse)
                .anyMatch(primerId -> primerId.type().startsWith("P"));
    }

    private static String consolidatedHeader(boolean qPcr) {
        if (qPcr) {
            return String.join("\t", List.of(
                    "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                    "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                    "ForwardEndMismatch", "ReverseEndMismatch", "Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches"
            ));
        }
        return String.join("\t", List.of(
                "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                "ForwardEndMismatch", "ReverseEndMismatch"
        ));
    }

    private static void writeSampleResults(BufferedWriter writer, String sampleName, Sample sample,
                                           Map<String, String> primers, boolean qPcr) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        if (blastResults.isEmpty()) {
            return;
        }

        Map<String, PrimerGroup> primerGroups = groupPrimerHits(blastResults.keySet());
        for (Map.Entry<String, PrimerGroup> groupEntry : primerGroups.entrySet()) {
            String primerName = groupEntry.getKey();
            PrimerGroup group = groupEntry.getValue();
            if (group.forward().isEmpty() || group.reverse().isEmpty()) {
                continue;
            }
            writePrimerPairResults(writer, sampleName, sample, primerName, group, primers, qPcr);
        }
    }

    private static Map<String, PrimerGroup> groupPrimerHits(Iterable<String> primerHits) {
        Map<String, PrimerGroup> groups = new LinkedHashMap<>();
        for (String primer : primerHits) {
            PrimerId primerId = PrimerId.parse(primer);
            PrimerGroup group = groups.computeIfAbsent(primerId.name(), ignored -> new PrimerGroup());
            group.add(primerId.type());
        }
        return groups;
    }

    private static void writePrimerPairResults(BufferedWriter writer, String sampleName, Sample sample, String primerName,
                                               PrimerGroup group, Map<String, String> primers, boolean qPcr) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        for (String forwardType : group.forward()) {
            for (String reverseType : group.reverse()) {
                String forwardPrimer = primerName + "-" + forwardType;
                String reversePrimer = primerName + "-" + reverseType;
                List<BlastResult> forwardResults = blastResults.getOrDefault(forwardPrimer, List.of());
                List<BlastResult> reverseResults = blastResults.getOrDefault(reversePrimer, List.of());

                for (BlastResult forwardResult : forwardResults) {
                    for (BlastResult reverseResult : reverseResults) {
                        if (!forwardResult.getQueryID().equals(reverseResult.getQueryID())) {
                            continue;
                        }
                        Amplicon amplicon = Amplicon.from(forwardResult, reverseResult);
                        String contigDescription = sample.contigs().getOrDefault(amplicon.contig(), "");

                        if (qPcr) {
                            writeProbeRows(writer, sampleName, sample, primerName, group, primers,
                                    forwardPrimer, reversePrimer, forwardResult, reverseResult, amplicon, contigDescription);
                        } else {
                            writePcrRow(writer, sampleName, primerName, primers, forwardPrimer, reversePrimer,
                                    forwardResult, reverseResult, amplicon, contigDescription);
                        }
                    }
                }
            }
        }
    }

    private static void writeProbeRows(BufferedWriter writer, String sampleName, Sample sample, String primerName,
                                       PrimerGroup group, Map<String, String> primers, String forwardPrimer,
                                       String reversePrimer, BlastResult forwardResult, BlastResult reverseResult,
                                       Amplicon amplicon, String contigDescription) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        for (String probeType : group.probe()) {
            String probePrimer = primerName + "-" + probeType;
            List<BlastResult> probeResults = blastResults.getOrDefault(probePrimer, List.of());
            for (BlastResult probeResult : probeResults) {
                if (!probeResult.getQueryID().equals(amplicon.contig())) {
                    continue;
                }
                int probeStart = Math.min(probeResult.getStart(), probeResult.getEnd());
                int probeEnd = Math.max(probeResult.getStart(), probeResult.getEnd());
                if (probeStart < amplicon.start() || probeEnd > amplicon.end()) {
                    continue;
                }
                writer.write(String.join("\t", List.of(
                        sampleName,
                        primerName,
                        amplicon.location(),
                        String.valueOf(amplicon.size()),
                        amplicon.contig(),
                        contigDescription,
                        forwardPrimer,
                        reversePrimer,
                        String.valueOf(forwardResult.getMismatch()),
                        String.valueOf(reverseResult.getMismatch()),
                        endMismatch(forwardResult, primers),
                        endMismatch(reverseResult, primers),
                        probePrimer,
                        probeStart + "-" + probeEnd,
                        String.valueOf(probeEnd - probeStart + 1),
                        String.valueOf(probeResult.getMismatch())
                )));
                writer.newLine();
            }
        }
    }

    private static void writePcrRow(BufferedWriter writer, String sampleName, String primerName,
                                    Map<String, String> primers, String forwardPrimer, String reversePrimer,
                                    BlastResult forwardResult, BlastResult reverseResult,
                                    Amplicon amplicon, String contigDescription) throws IOException {
        writer.write(String.join("\t", List.of(
                sampleName,
                primerName,
                amplicon.location(),
                String.valueOf(amplicon.size()),
                amplicon.contig(),
                contigDescription,
                forwardPrimer,
                reversePrimer,
                String.valueOf(forwardResult.getMismatch()),
                String.valueOf(reverseResult.getMismatch()),
                endMismatch(forwardResult, primers),
                endMismatch(reverseResult, primers)
        )));
        writer.newLine();
    }

    private static String endMismatch(BlastResult result, Map<String, String> primers) {
        String primer = primers.get(result.getSubjectID());
        if (primer == null) {
            return "";
        }
        return String.valueOf(result.getLength() - primer.length());
    }

    private record PrimerId(String name, String type) {
        static PrimerId parse(String id) {
            int lastDash = id.lastIndexOf('-');
            if (lastDash < 0 || lastDash == id.length() - 1) {
                throw new PipelineException("Primer ID must end with -F, -R, or -P: " + id);
            }
            return new PrimerId(id.substring(0, lastDash), id.substring(lastDash + 1));
        }
    }

    private record Amplicon(String contig, int start, int end) {
        static Amplicon from(BlastResult forward, BlastResult reverse) {
            int start = Math.min(Math.min(forward.getStart(), forward.getEnd()), Math.min(reverse.getStart(), reverse.getEnd()));
            int end = Math.max(Math.max(forward.getStart(), forward.getEnd()), Math.max(reverse.getStart(), reverse.getEnd()));
            return new Amplicon(forward.getQueryID(), start, end);
        }

        String location() {
            return start + "-" + end;
        }

        int size() {
            return end - start + 1;
        }
    }

    private static final class PrimerGroup {
        private final ArrayList<String> forward = new ArrayList<>();
        private final ArrayList<String> reverse = new ArrayList<>();
        private final ArrayList<String> probe = new ArrayList<>();

        void add(String type) {
            if (type.startsWith("F")) {
                forward.add(type);
            } else if (type.startsWith("R")) {
                reverse.add(type);
            } else if (type.startsWith("P")) {
                probe.add(type);
            }
        }

        List<String> forward() {
            return forward;
        }

        List<String> reverse() {
            return reverse;
        }

        List<String> probe() {
            return probe;
        }
    }
}
