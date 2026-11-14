package ca.canada.inspection.pcr;

import ca.canada.inspection.model.BlastResult;
import ca.canada.inspection.model.Sample;
import ca.canada.inspection.pcr.AmpliconCaller.Amplicon;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
        return write(consolidatedDir, samples, primers, options, Map.of());
    }

    /**
     * As {@link #write(Path, Map, Map, AmpliconOptions)}, appending {@code extraRows}
     * (keyed by sample name) after each sample's
     * own rows; used for FASTQ-mode amplicons called on reads rather than contigs.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options,
                             Map<String, List<ReportRow>> extraRows) {
        return write(consolidatedDir, samples, primers, options, extraRows, ExpectedSizes.NONE);
    }

    /**
     * As {@link #write(Path, Map, Map, AmpliconOptions, Map)}, labelling each row with what
     * its amplicon size is for the assay when the primer file declares product sizes
     * (last column, {@link ExpectedSizes#COLUMN}). The label never removes a row.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options,
                             Map<String, List<ReportRow>> extraRows, ExpectedSizes expectedSizes) {
        // Probe requirement is decided per assay from the primer FILE, so standard
        // PCR and qPCR assays can be mixed in one run: an assay whose probe is
        // defined but never hits must stay negative, while a probe-less assay is
        // reported on its primer pair alone. The probe columns are present
        // whenever at least one assay defines a probe.
        Set<String> assaysWithProbe = assaysWithProbe(primers.keySet());
        boolean probeColumns = !assaysWithProbe.isEmpty();
        boolean sizeColumn = !expectedSizes.isEmpty();
        Path report = nextAvailableReportFile(consolidatedDir);

        try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
            writer.write(header(probeColumns, sizeColumn));
            writer.newLine();
            // TreeMap keeps samples in a stable, alphabetical order.
            RowSink sink = row -> {
                writer.write(String.join("\t", row.columns(probeColumns)));
                if (sizeColumn) {
                    writer.write("\t" + expectedSizes.labelText(row.assay(), row.size()));
                }
                writer.newLine();
            };
            for (Map.Entry<String, Sample> sampleEntry : new TreeMap<>(samples).entrySet()) {
                writeSampleResults(sink, sampleEntry.getKey(), sampleEntry.getValue(), options, assaysWithProbe);
                for (ReportRow row : extraRows.getOrDefault(sampleEntry.getKey(), List.of())) {
                    sink.accept(row);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write consolidated report: " + report, e);
        }
        return report;
    }

    /**
     * Amplicon sizes {@link #write} reports in {@code sample}, per assay: the same
     * pairing, size and probe rules, without writing anything. An assay with no row is
     * absent.
     */
    public static Map<String, Set<Integer>> reportedSizes(Sample sample, Map<String, String> primers,
                                                          AmpliconOptions options) {
        Map<String, Set<Integer>> sizes = new TreeMap<>();
        for (ReportRow row : rows(sample, primers, options)) {
            sizes.computeIfAbsent(row.assay(), ignored -> new TreeSet<>()).add(row.size());
        }
        return sizes;
    }

    /** The rows {@link #write} would write for {@code sample}, in report order. */
    public static List<ReportRow> rows(Sample sample, Map<String, String> primers, AmpliconOptions options) {
        List<ReportRow> rows = new ArrayList<>();
        try {
            writeSampleResults(rows::add, sample.getName(), sample, options, assaysWithProbe(primers.keySet()));
        } catch (IOException e) {
            throw new IllegalStateException("Collecting rows in memory cannot fail", e);
        }
        return rows;
    }

    /**
     * Columns giving, per primer, the bases at its 5' end that do not pair with the
     * template: of the bases BLAST left out of the alignment, those that differ from the
     * template (or lie beyond its end). The mismatch columns do not count them (the
     * run's {@code --5p-truncation} allows them), so without these a primer held by its
     * last few bases reads as a perfect match. They follow the probe
     * columns; {@link ExpectedSizes#COLUMN} stays last.
     */
    public static final String FORWARD_UNALIGNED_COLUMN = "Forward5PrimeUnaligned";
    public static final String REVERSE_UNALIGNED_COLUMN = "Reverse5PrimeUnaligned";

    /** Assay names that define at least one -P probe in the primer file. */
    private static Set<String> assaysWithProbe(Iterable<String> primerIds) {
        Set<String> assays = new TreeSet<>();
        for (String primerId : primerIds) {
            PrimerId parsed = PrimerId.parse(primerId);
            if (parsed != null && parsed.role() == PrimerId.Role.PROBE) {
                assays.add(parsed.assay());
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

    private static String header(boolean probeColumns, boolean sizeColumn) {
        List<String> columns = new ArrayList<>(List.of(
                "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
                "ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
                "Forward3PrimeMismatches", "Reverse3PrimeMismatches", "Strand"
        ));
        if (probeColumns) {
            columns.addAll(List.of("Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches"));
        }
        columns.addAll(List.of(FORWARD_UNALIGNED_COLUMN, REVERSE_UNALIGNED_COLUMN));
        if (sizeColumn) {
            columns.add(ExpectedSizes.COLUMN);
        }
        return String.join("\t", columns);
    }

    private static void writeSampleResults(RowSink writer, String sampleName, Sample sample,
                                           AmpliconOptions options, Set<String> assaysWithProbe) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        if (blastResults.isEmpty()) {
            return;
        }

        // Fetched once: both accessors copy, and a FASTQ sample searched at read level
        // has one "contig" per read.
        Map<String, String> contigs = sample.contigs();
        HitIndex hits = new HitIndex(blastResults);
        Map<String, PrimerId.Assay> assays = PrimerId.byAssay(blastResults.keySet());
        for (Map.Entry<String, PrimerId.Assay> assayEntry : assays.entrySet()) {
            String assayName = assayEntry.getKey();
            PrimerId.Assay group = assayEntry.getValue();
            if (!group.isComplete()) {
                continue;
            }
            boolean probeRequired = assaysWithProbe.contains(assayName);
            for (String forwardPrimer : group.forward()) {
                for (String reversePrimer : group.reverse()) {
                    writePrimerPairResults(writer, sampleName, contigs, hits, assayName, group,
                            forwardPrimer, reversePrimer, options, probeRequired);
                }
            }
        }
    }

    /**
     * Accepted hits of one sample, by oligo and by the contig they sit on. Primers only
     * pair, and probes only count, on one contig, so looking hits up per contig keeps the
     * work proportional to the hits rather than to every forward/reverse combination in
     * the sample (millions of reads each carry a hit or two in a read-level search).
     */
    private static final class HitIndex {
        private final Map<String, List<BlastResult>> byOligo;
        private final Map<String, Map<String, List<BlastResult>>> byOligoAndContig = new HashMap<>();

        HitIndex(Map<String, List<BlastResult>> byOligo) {
            this.byOligo = byOligo;
        }

        List<BlastResult> of(String oligoId) {
            return byOligo.getOrDefault(oligoId, List.of());
        }

        /** Hits of {@code oligoId} on {@code contig}, in the order BLAST reported them. */
        List<BlastResult> on(String oligoId, String contig) {
            return byOligoAndContig.computeIfAbsent(oligoId, id -> {
                Map<String, List<BlastResult>> byContig = new HashMap<>();
                for (BlastResult hit : of(id)) {
                    byContig.computeIfAbsent(hit.queryID(), ignored -> new ArrayList<>()).add(hit);
                }
                return byContig;
            }).getOrDefault(contig, List.of());
        }
    }

    private static void writePrimerPairResults(RowSink writer, String sampleName, Map<String, String> contigs,
                                               HitIndex hits,
                                               String assayName, PrimerId.Assay group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options, boolean probeRequired)
            throws IOException {
        for (BlastResult forwardHit : hits.of(forwardPrimer)) {
            for (BlastResult reverseHit : hits.on(reversePrimer, forwardHit.queryID())) {
                Amplicon amplicon = AmpliconCaller.call(forwardHit, reverseHit, options).orElse(null);
                if (amplicon == null) {
                    continue;
                }
                ReportRow row = new ReportRow(sampleName, assayName, amplicon.start(), amplicon.end(), amplicon.size(),
                        amplicon.contig(), contigs.getOrDefault(amplicon.contig(), ""), forwardPrimer, reversePrimer,
                        effectiveMismatches(forwardHit), effectiveMismatches(reverseHit),
                        forwardHit.threePrimeMismatches(options.threePrimeWindow()),
                        reverseHit.threePrimeMismatches(options.threePrimeWindow()), amplicon.forwardStrand(), null,
                        forwardHit.fivePrimeUnpairedBases(), reverseHit.fivePrimeUnpairedBases());
                if (probeRequired) {
                    writeProbeRows(writer, hits, group, amplicon, row);
                } else {
                    writer.accept(row);
                }
            }
        }
    }

    /** Mismatches as counted against the -m budget: substitutions, indels, and trimmed 3' bases. */
    private static int effectiveMismatches(BlastResult hit) {
        return hit.mismatch() + hit.gaps() + hit.threePrimeUncovered();
    }

    /** One row per probe hit within the amplicon; none when the probe does not bind there. */
    private static void writeProbeRows(RowSink writer, HitIndex hits, PrimerId.Assay group, Amplicon amplicon,
                                       ReportRow pair) throws IOException {
        for (String probeId : group.probe()) {
            for (BlastResult probeHit : hits.on(probeId, amplicon.contig())) {
                int probeStart = Math.min(probeHit.qstart(), probeHit.qend());
                int probeEnd = Math.max(probeHit.qstart(), probeHit.qend());
                // The probe must anneal within the amplified product.
                if (!amplicon.contains(probeStart, probeEnd)) {
                    continue;
                }
                writer.accept(pair.withProbe(new ReportRow.Probe(probeId, probeStart, probeEnd,
                        probeHit.mismatch() + probeHit.gaps())));
            }
        }
    }

    /** Receives one report row; lets row generation feed a file or an in-memory check. */
    @FunctionalInterface
    private interface RowSink {
        void accept(ReportRow row) throws IOException;
    }
}
