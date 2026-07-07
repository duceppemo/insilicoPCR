package ca.canada.inspection.commandpcr;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.ProcessRunner;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Command-line in silico PCR pipeline. */
public final class CommandMain {

	private static final String BLAST_OUTFMT = "6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq";
	private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;

	private final Path inputFile;
	private final Path outDir;
	private final Path primerFile;
	private final int threads;
	private final int mismatches;
	private final double evalue;

	private int memJavaGiB = 4;
	private Path detailedDir;
	private Path consolidatedDir;
	private Path bbToolsLocation;
	private Path blastLocation;
	private String javaCall;
	private HashMap<String, String> primerDict = new HashMap<>();
	private HashMap<String, Sample> sampleDict = new HashMap<>();

	public CommandMain(Path inputFile, Path outDir, Path primerFile, int threads, int mismatches, double evalue) {
		this.inputFile = Objects.requireNonNull(inputFile, "inputFile");
		this.outDir = Objects.requireNonNull(outDir, "outDir");
		this.primerFile = Objects.requireNonNull(primerFile, "primerFile");
		this.threads = Math.max(1, threads);
		this.mismatches = Math.max(0, mismatches);
		this.evalue = evalue;
	}

	public void run() {
		long startTime = System.nanoTime();
		System.out.println("Beginning Program Run");

		memJavaGiB = calculateJavaMemoryGiB();
		findDependencies();
		makeDirectories();

		sampleDict = CommandMethods.createSampleDict(inputFile);
		primerDict = CommandMethods.parseFastaToDictionary(primerFile);
		CommandMethods.processPrimers(primerDict, outDir, java.io.File.separator);

		boolean fastqPresent = sampleDict.values().stream().anyMatch(Sample::isFastq);
		if (fastqPresent) {
			System.out.println("FastQ files identified, conducting baiting and assembly");
			baitReads();
			secondBaitReads();
			assembleReads();
		}

		if (!System.getProperty("os.name").contains("Windows")) {
			CommandMethods.makeExecutable(blastLocation);
		}

		Path primerDatabase = outDir.resolve("primer_tmp.fasta");
		CommandMethods.makeBlastDB(primerDatabase, blastLocation);
		runBlast(primerDatabase);

		CommandMethods.addContigDict(sampleDict);
		CommandMethods.parseBlastOutput(consolidatedDir, detailedDir, primerDict, mismatches, sampleDict);
		CommandMethods.makeConsolidatedReport(consolidatedDir, java.io.File.separator, sampleDict, primerDict);
		CommandMethods.makeQALog(outDir.resolve("QAlog.txt"), Dispatcher.version, outDir, inputFile, primerFile, bbToolsLocation, blastLocation);

		long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startTime);
		System.out.println("Done in " + seconds + " seconds");
	}

	public void makeDirectories() {
		detailedDir = outDir.resolve("detailed_report");
		consolidatedDir = outDir.resolve("consolidated_report");
		try {
			Files.createDirectories(detailedDir);
			Files.createDirectories(consolidatedDir);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to create output directories under " + outDir, e);
		}
	}

	public void findDependencies() {
		AppPaths.RuntimeLayout layout = AppPaths.discover();
		bbToolsLocation = layout.bbmapDirectory();
		blastLocation = layout.blastBinDirectory();
		javaCall = layout.javaCommand();

		System.out.println("Application root: " + layout.appRoot());
		System.out.println("BBMap: " + bbToolsLocation);
		System.out.println("BLAST: " + blastLocation);
		System.out.println("Java: " + javaCall);
	}

	private int calculateJavaMemoryGiB() {
		long maxMemory = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
				? os.getTotalMemorySize()
				: Runtime.getRuntime().maxMemory();
		long half = Math.max(BYTES_PER_GIB, maxMemory / 2);
		return (int) Math.max(1, half / BYTES_PER_GIB);
	}

	private List<Sample> fastqSamples() {
		return sampleDict.values().stream()
				.filter(Sample::isFastq)
				.sorted(Comparator.comparing(Sample::getName))
				.toList();
	}

	private void baitReads() {
		int kLength = primerDict.values().stream().mapToInt(String::length).min()
				.orElseThrow(() -> new IllegalStateException("Primer dictionary is empty"));
		Path ref = outDir.resolve("primer_tmp.fasta");
		for (Sample sample : fastqSamples()) {
			Path sampleDir = detailedDir.resolve(sample.getName());
			createDirectory(sampleDir);
			List<String> command = bbdukCommand(sample, ref, sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz"), kLength);
			ProcessRunner.run(bbToolsLocation, command.toArray(String[]::new));
		}
	}

	private void secondBaitReads() {
		for (Sample sample : fastqSamples()) {
			Path sampleDir = detailedDir.resolve(sample.getName());
			Path ref = sampleDir.resolve(sample.getName() + "_targetMatches.fastq.gz");
			List<String> command = bbdukCommand(sample, ref, sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz"), null);
			ProcessRunner.run(bbToolsLocation, command.toArray(String[]::new));
		}
	}

	private List<String> bbdukCommand(Sample sample, Path ref, Path output, Integer kLength) {
		ArrayList<String> command = new ArrayList<>(List.of(javaCall, "-ea", "-Xmx" + memJavaGiB + "g", "-cp", "./current", "jgi.BBDuk",
				"ref=" + ref, "hdist=" + mismatches, "threads=" + threads, "overwrite=t", "interleaved=t"));
		if (kLength != null) {
			command.add("k=" + kLength);
		}
		if (sample.getFiles().size() == 2) {
			command.add("in1=" + sample.getFiles().getFirst());
			command.add("in2=" + sample.getFiles().get(1));
		} else {
			command.add("in=" + sample.getFiles().getFirst());
		}
		command.add("outm=" + output);
		return command;
	}

	private void assembleReads() {
		for (Sample sample : fastqSamples()) {
			Path sampleDir = detailedDir.resolve(sample.getName());
			Path input = sampleDir.resolve(sample.getName() + "_doubleTargetMatches.fastq.gz");
			Path output = sampleDir.resolve(sample.getName() + "_assembly.fasta");
			sample.setAssemblyFile(output);
			ProcessRunner.run(bbToolsLocation, javaCall, "-ea", "-Xmx" + memJavaGiB + "g", "-cp", "./current", "assemble.Tadpole",
					"in=" + input, "out=" + output, "overwrite=t", "threads=" + threads);
		}
	}

	private void runBlast(Path primerDatabase) {
		List<Path> queries = blastQueries();
		try (ExecutorService executor = Executors.newFixedThreadPool(Math.clamp(threads, 1, samples.size()))) {
			for (Callable<Void> task : tasks) {
				executor.submit(task);
			}
		}
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (Path query : queries) {
				futures.add(executor.submit(() -> runBlastForQuery(primerDatabase, query)));
			}
			for (Future<?> future : futures) {
				future.get();
			}
		} catch (Exception e) {
			throw new IllegalStateException("BLAST execution failed", e);
		} finally {
			executor.shutdownNow();
		}
	}

	private List<Path> blastQueries() {
		ArrayList<Path> queries = new ArrayList<>();
		for (Sample sample : sampleDict.values()) {
			if (sample.isFastq()) {
				queries.add(sample.getAssemblyFile());
			} else {
				queries.addAll(sample.getFiles());
			}
		}
		return queries.stream().sorted(Comparator.comparing(Path::toString)).toList();
	}

	private void runBlastForQuery(Path primers, Path query) {
		String name = query.getFileName().toString()
				.replaceFirst("_assembly\\.fasta$", "")
				.replaceFirst("\\.(fasta|fna|ffn|fa|fsa)(\\.gz)?$", "");
		Path blastOutput = detailedDir.resolve(name);
		createDirectory(blastOutput);
		Path blastTsv = blastOutput.resolve(name + ".tsv");

		ProcessRunner.run(null,
				AppPaths.executable(blastLocation, "blastn").toString(),
				"-task", "blastn-short",
				"-query", query.toString(),
				"-db", primers.toString(),
				"-evalue", Double.toString(evalue),
				"-num_alignments", "1000000",
				"-num_threads", "1",
				"-outfmt", BLAST_OUTFMT,
				"-out", blastTsv.toString());
		CommandMethods.addHeaderToTSV(blastTsv);
	}

	private static void createDirectory(Path directory) {
		try {
			Files.createDirectories(directory);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to create directory: " + directory, e);
		}
	}
}
