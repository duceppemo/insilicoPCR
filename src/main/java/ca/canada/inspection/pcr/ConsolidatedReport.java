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
     * (keyed by sample name, already in report column order) after each sample's
     * own rows; used for FASTQ-mode amplicons called on reads rather than contigs.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options,
                             Map<String, List<List<String>>> extraRows) {
        return write(consolidatedDir, samples, primers, options, extraRows, ExpectedSizes.NONE);
    }

    /**
     * As {@link #write(Path, Map, Map, AmpliconOptions, Map)}, labelling each row with what
     * its amplicon size is for the assay when the primer file declares product sizes
     * (last column, {@link ExpectedSizes#COLUMN}). The label never removes a row.
     */
    public static Path write(Path consolidatedDir, Map<String, Sample> samples,
                             Map<String, String> primers, AmpliconOptions options,
                             Map<String, List<List<String>>> extraRows, ExpectedSizes expectedSizes) {
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
            RowSink sink = columns -> {
                writer.write(String.join("\t", columns));
                if (sizeColumn) {
                    writer.write("\t" + expectedSizes.labelText(columns.get(GENE_COLUMN),
                            Integer.parseInt(columns.get(AMPLICON_SIZE_COLUMN))));
                }
                writer.newLine();
            };
            for (Map.Entry<String, Sample> sampleEntry : new TreeMap<>(samples).entrySet()) {
                writeSampleResults(sink, sampleEntry.getKey(), sampleEntry.getValue(),
                        options, assaysWithProbe, probeColumns);
                for (List<String> row : extraRows.getOrDefault(sampleEntry.getKey(), List.of())) {
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
        for (List<String> row : rows(sample, primers, options)) {
            sizes.computeIfAbsent(row.get(GENE_COLUMN), ignored -> new TreeSet<>())
                    .add(Integer.parseInt(row.get(AMPLICON_SIZE_COLUMN)));
        }
        return sizes;
    }

    /** The rows {@link #write} would write for {@code sample}, in report column order. */
    public static List<List<String>> rows(Sample sample, Map<String, String> primers, AmpliconOptions options) {
        Set<String> assaysWithProbe = assaysWithProbe(primers.keySet());
        List<List<String>> rows = new ArrayList<>();
        try {
            writeSampleResults(columns -> rows.add(List.copyOf(columns)), sample.getName(), sample,
                    options, assaysWithProbe, !assaysWithProbe.isEmpty());
        } catch (IOException e) {
            throw new IllegalStateException("Collecting rows in memory cannot fail", e);
        }
        return rows;
    }

    /** Report column indexes used by callers that post-process rows. */
    public static final int GENE_COLUMN = 1;
    public static final int LOCATION_COLUMN = 2;
    public static final int CONTIG_COLUMN = 4;
    public static final int CONTIG_DESCRIPTION_COLUMN = 5;
    public static final int FORWARD_MISMATCHES_COLUMN = 8;
    public static final int REVERSE_MISMATCHES_COLUMN = 9;
    public static final int AMPLICON_SIZE_COLUMN = 3;

    /**
     * Columns giving, per primer, the bases at its 5' end that BLAST left out of the
     * alignment: bases that do not pair with the template. The mismatch columns do not
     * count them (the run's {@code --5p-truncation} allows them), so without these a
     * primer held by its last few bases reads as a perfect match. They follow the probe
     * columns; {@link ExpectedSizes#COLUMN} stays last.
     */
    public static final String FORWARD_UNALIGNED_COLUMN = "Forward5PrimeUnaligned";
    public static final String REVERSE_UNALIGNED_COLUMN = "Reverse5PrimeUnaligned";

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
                                           AmpliconOptions options, Set<String> assaysWithProbe,
                                           boolean probeColumns) throws IOException {
        Map<String, List<BlastResult>> blastResults = sample.blastResults();
        if (blastResults.isEmpty()) {
            return;
        }

        // Fetched once: both accessors copy, and a FASTQ sample searched at read level
        // has one "contig" per read.
        Map<String, String> contigs = sample.contigs();
        HitIndex hits = new HitIndex(blastResults);
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
                    writePrimerPairResults(writer, sampleName, contigs, hits, assayName, group,
                            forwardPrimer, reversePrimer, options, probeRequired, probeColumns);
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
                                               String assayName, PrimerGroup group, String forwardPrimer,
                                               String reversePrimer, AmpliconOptions options,
                                               boolean probeRequired, boolean probeColumns)
            throws IOException {
        for (BlastResult forwardHit : hits.of(forwardPrimer)) {
            for (BlastResult reverseHit : hits.on(reversePrimer, forwardHit.queryID())) {
                Amplicon amplicon = AmpliconCaller.call(forwardHit, reverseHit, options).orElse(null);
                if (amplicon == null) {
                    continue;
                }
                String contigDescription = contigs.getOrDefault(amplicon.contig(), "");
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

                List<String> unaligned = List.of(
                        String.valueOf(forwardHit.fivePrimeUncovered()),
                        String.valueOf(reverseHit.fivePrimeUncovered()));

                if (probeRequired) {
                    writeProbeRows(writer, hits, group, amplicon, baseColumns, unaligned);
                } else {
                    List<String> columns = new ArrayList<>(baseColumns);
                    if (probeColumns) {
                        // Probe-less assay in a mixed run: pad the probe columns.
                        columns.addAll(List.of("", "", "", ""));
                    }
                    columns.addAll(unaligned);
                    writer.accept(columns);
                }
            }
        }
    }

    /** Mismatches as counted against the -m budget: substitutions, indels, and trimmed 3' bases. */
    private static int effectiveMismatches(BlastResult hit) {
        return hit.mismatch() + hit.gaps() + hit.threePrimeUncovered();
    }

    private static void writeProbeRows(RowSink writer, HitIndex hits,
                                       PrimerGroup group, Amplicon amplicon, List<String> baseColumns,
                                       List<String> unaligned)
            throws IOException {
        for (String probeId : group.probe) {
            for (BlastResult probeHit : hits.on(probeId, amplicon.contig())) {
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
                columns.addAll(unaligned);
                writer.accept(columns);
            }
        }
    }

    /**
     * Groups expanded primer IDs by assay name. {@code assay-F}, {@code assay-R_1}
     * and {@code assay-P} all land under {@code assay} with their full IDs kept
     * in the forward/reverse/probe buckets.
     */
    static Map<String, PrimerGroup> groupByAssay(Iterable<String> primerIds) {
        Map<String, PrimerGroup> groups = new TreeMap<>();
        for (String primerId : primerIds) {
            // IDs are validated at load time (PrimerService); skip rather than
            // abort mid-write if something nonconforming slips through anyway.
            int lastDash = primerId.lastIndexOf('-');
            if (lastDash <= 0 || lastDash == primerId.length() - 1) {
                continue;
            }
            String assayName = primerId.substring(0, lastDash);
            char type = primerId.charAt(lastDash + 1);
            if (type != 'F' && type != 'R' && type != 'P') {
                continue;
            }
            PrimerGroup group = groups.computeIfAbsent(assayName, ignored -> new PrimerGroup());
            switch (type) {
                case 'F' -> group.forward.add(primerId);
                case 'R' -> group.reverse.add(primerId);
                default -> group.probe.add(primerId);
            }
        }
        return groups;
    }

    /** Expanded primer IDs of one assay, by role. */
    static final class PrimerGroup {
        final List<String> forward = new ArrayList<>();
        final List<String> reverse = new ArrayList<>();
        final List<String> probe = new ArrayList<>();
    }

    /** Receives one report row; lets row generation feed a file or an in-memory check. */
    @FunctionalInterface
    private interface RowSink {
        void accept(List<String> columns) throws IOException;
    }
}
