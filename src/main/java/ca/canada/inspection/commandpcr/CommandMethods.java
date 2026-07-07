package ca.canada.inspection.commandpcr;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.insilicopcr.BlastResult;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.util.SequenceFileUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.nio.file.Path;

public final class CommandMethods {

	private static final String[] FASTA_EXTENSIONS = SequenceFileUtils.FASTA_EXTENSIONS;
	private static final String[] FASTQ_EXTENSIONS = SequenceFileUtils.FASTQ_EXTENSIONS;
	private static final String[] ACCEPTED_EXTENSIONS = SequenceFileUtils.ACCEPTED_EXTENSIONS;

	private static final Map<Character, char[]> DEGENERATE_BASES = Map.ofEntries(
			Map.entry('R', new char[]{'A', 'G'}),
			Map.entry('Y', new char[]{'T', 'C'}),
			Map.entry('S', new char[]{'G', 'C'}),
			Map.entry('W', new char[]{'A', 'T'}),
			Map.entry('K', new char[]{'T', 'G'}),
			Map.entry('M', new char[]{'A', 'C'}),
			Map.entry('B', new char[]{'T', 'G', 'C'}),
			Map.entry('D', new char[]{'A', 'T', 'G'}),
			Map.entry('H', new char[]{'A', 'T', 'C'}),
			Map.entry('V', new char[]{'A', 'C', 'G'}),
			Map.entry('N', new char[]{'A', 'T', 'C', 'G'})
	);

	private static final Pattern VALID_PRIMER_SEQUENCE = Pattern.compile("^[ATCGRYSWKMBDHVN]+$");
	private static final Pattern RELEASE_TAG = Pattern.compile("/chmaraj/In[_s]ilico[_P]CR/releases/tag/[^>]*>(?:vV)?([0-9]+(?:\\.[0-9]+)*)<");
	private static final DateTimeFormatter QA_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

	private CommandMethods() {
		// Utility class.
	}

	public static boolean checkIfFileHasExtension(String fileName, String[] extensions) {
		return SequenceFileUtils.hasExtension(fileName, extensions);
	}

	public static boolean noFastaFile(Path inputFile) {
		Objects.requireNonNull(inputFile, "inputFile");
		if (!Files.isDirectory(inputFile)) {
			return !SequenceFileUtils.looksLikeSequenceFile(inputFile);
		}
		try (Stream<Path> files = Files.list(inputFile)) {
			return files.noneMatch(SequenceFileUtils::looksLikeSequenceFile);
		} catch (IOException e) {
			throw new IllegalStateException("Could not list input directory: " + inputFile, e);
		}
	}

	public static boolean noFastaFile(File inputFile) {
		return noFastaFile(inputFile.toPath());
	}

	public static boolean verifyPrimerFile(Path primerFile) {
		Objects.requireNonNull(primerFile, "primerFile");
		Map<String, List<String>> primerTypesByName = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(primerFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || !line.startsWith(">")) {
					continue;
				}
				PrimerId primerId = parsePrimerId(line.substring(1).trim());
				primerTypesByName
						.computeIfAbsent(primerId.name(), ignored -> new ArrayList<>())
						.add(primerId.type());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not read primer file: " + primerFile, e);
		}

		return primerTypesByName.values().stream()
				.noneMatch(types -> types.stream().anyMatch(type -> type.startsWith("P"))
						&& (types.stream().noneMatch(type -> type.startsWith("F"))
						|| types.stream().noneMatch(type -> type.startsWith("R"))));
	}

	public static boolean verifyPrimerFile(File primerFile) {
		return verifyPrimerFile(primerFile.toPath());
	}

	public static boolean verifyFastaFormat(Path checkFile) {
		return SequenceFileUtils.looksLikeSequenceFile(checkFile);
	}

	public static boolean verifyFastaFormat(File checkFile) {
		return verifyFastaFormat(checkFile.toPath());
	}

	public static HashMap<String, Sample> createSampleDict(Path inputFile) {
		return new HashMap<>(SequenceFileUtils.createSampleMap(inputFile));
	}

	public static HashMap<String, Sample> createSampleDict(File inputFile) {
		return createSampleDict(inputFile.toPath());
	}

	public static HashMap<String, String> parseFastaToDictionary(Path file) {
		Objects.requireNonNull(file, "file");
		HashMap<String, String> fasta = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String currentId = null;
			StringBuilder sequence = new StringBuilder();
			String line;

			while ((line = reader.readLine()) != null) {
				line = line.trim();
				if (line.isEmpty()) {
					continue;
				}
				if (line.startsWith(">")) {
					putFastaEntry(fasta, currentId, sequence);
					currentId = line.substring(1).trim();
					sequence.setLength(0);
				} else {
					sequence.append(line);
				}
			}
			putFastaEntry(fasta, currentId, sequence);
		} catch (IOException e) {
			throw new IllegalStateException("Could not parse FASTA file: " + file, e);
		}
		return fasta;
	}

	public static HashMap<String, String> parseFastaToDictionary(File file) {
		return parseFastaToDictionary(file.toPath());
	}

	public static void processPrimers(HashMap<String, String> primerDict, Path outDir, String sep) {
		Objects.requireNonNull(primerDict, "primerDict");
		Objects.requireNonNull(outDir, "outDir");

		Map<String, String> processed = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : primerDict.entrySet()) {
			String id = entry.getKey();
			String sequence = normalizePrimerSequence(id, entry.getValue());
			List<String> expanded = expandDegenerated(sequence);

			if (expanded.size() == 1 && expanded.getFirst().equals(sequence)) {
				processed.put(id, sequence);
			} else {
				for (int i = 0; i < expanded.size(); i++) {
					processed.put(id + "_" + i, expanded.get(i));
				}
			}
		}

		primerDict.clear();
		primerDict.putAll(processed);
		writePrimerTmpFile(outDir.resolve("primer_tmp.fasta"), processed);
	}

	public static void processPrimers(HashMap<String, String> primerDict, File outDir, String sep) {
		processPrimers(primerDict, outDir.toPath(), sep);
	}

	public static ArrayList<String> expandDegenerated(String sequence, int index, ArrayList<String> primerContainer) {
		Objects.requireNonNull(primerContainer, "primerContainer");
		if (sequence == null || sequence.isBlank()) {
			return primerContainer;
		}
		expandDegenerated(sequence.toUpperCase().toCharArray(), Math.max(index, 0), primerContainer);
		return primerContainer;
	}

	public static void makeExecutable(Path blastLocation) {
		Objects.requireNonNull(blastLocation, "blastLocation");
		runProcess(blastLocation, "chmod", "+x", "makeblastdb", "blastn");
	}

	public static void makeExecutable(File blastLocation) {
		makeExecutable(blastLocation.toPath());
	}

	public static void makeBlastDB(Path reference, Path blastLocation) {
		Objects.requireNonNull(reference, "reference");
		Objects.requireNonNull(blastLocation, "blastLocation");
		runProcess(null,
				AppPaths.executable(blastLocation, "makeblastdb").toString(),
				"-dbtype", "nucl",
				"-hash_index",
				"-in", reference.toString());
	}

	public static void makeBlastDB(File reference, File blastLocation) {
		makeBlastDB(reference.toPath(), blastLocation.toPath());
	}

	public static void addHeaderToTSV(Path path) {
		Objects.requireNonNull(path, "tsvFile");
		String header = String.join("\t", List.of(
				"qseqid", "sseqid", "positive", "mismatch", "gaps", "evalue",
				"bitscore", "slen", "length", "qstart", "qend", "qseq", "sstart", "send", "sseq"
		));

		try {
			List<String> originalLines = Files.exists(path)
					? Files.readAllLines(path, StandardCharsets.UTF_8)
					: List.of();
			try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
				writer.write(header);
				writer.newLine();
				for (String line : originalLines) {
					writer.write(line);
					writer.newLine();
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not add BLAST TSV header to: " + path, e);
		}
	}

	public static void addHeaderToTSV(File tsvFile) {
		addHeaderToTSV(tsvFile.toPath());
	}

	public static void parseBlastOutput(Path consolidatedDir, Path detailedReport, HashMap<String, String> primerDict,
	                                    int mismatches, HashMap<String, Sample> sampleDict) {
		Objects.requireNonNull(detailedReport, "detailedReport");
		Objects.requireNonNull(primerDict, "primerDict");
		Objects.requireNonNull(sampleDict, "sampleDict");

		for (File report : listTsvFiles(detailedReport.toFile())) {
			parseSingleBlastReport(report, primerDict, mismatches, sampleDict);
		}
	}

	public static void addContigDict(HashMap<String, Sample> sampleDict) {
		Objects.requireNonNull(sampleDict, "sampleDict");
		for (Map.Entry<String, Sample> entry : sampleDict.entrySet()) {
			Sample sample = entry.getValue();
			if ("fastq".equals(sample.getFileType())) {
				addContigsFromFasta(sample, sample.getAssemblyFile());
			} else {
				for (Path file : sample.getFiles()) {
					addContigsFromFasta(sample, file);
				}
			}
		}
	}

	public static void makeConsolidatedReport(Path consolidatedDir, String sep, HashMap<String, Sample> sampleDict,
	                                          HashMap<String, String> primerDict) {
		Objects.requireNonNull(consolidatedDir, "consolidatedDir");
		Objects.requireNonNull(sampleDict, "sampleDict");
		Objects.requireNonNull(primerDict, "primerDict");

		boolean qPcr = hasProbePrimers(primerDict);
		Path report = consolidatedDir.resolve("report.tsv");

		try (BufferedWriter writer = Files.newBufferedWriter(report, StandardCharsets.UTF_8)) {
			writer.write(consolidatedHeader(qPcr));
			writer.newLine();

			for (Map.Entry<String, Sample> sampleEntry : sampleDict.entrySet()) {
				writeSampleConsolidatedResults(writer, sampleEntry.getKey(), sampleEntry.getValue(), primerDict, qPcr);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write consolidated report: " + report, e);
		}
	}

	public static String getContigDescription(HashMap<String, Sample> sampleDict, String sampleName, String contig) {
		Sample sample = sampleDict.get(sampleName);
		if (sample == null || sample.getContigDict() == null) {
			return "";
		}
		return sample.getContigDict().getOrDefault(contig, "");
	}

	public static void makeQALog(Path qLog, String version, Path outputDir, Path inputFile,
	                             Path primerFile, Path bbToolsLocation, Path blastLocation) {
		Objects.requireNonNull(qLog, "qLog");

		try (BufferedWriter writer = Files.newBufferedWriter(qLog, StandardCharsets.UTF_8)) {
			writeLine(writer, "In Silico PCR version: " + version);
			writeLine(writer, "Date run: " + QA_DATE_FORMAT.format(LocalDateTime.now()));
			writeLine(writer, "Run by user: " + System.getProperty("user.name"));
			writeLine(writer, "BBTools Location: " + bbToolsLocation.toString());
			writeLine(writer, "BLAST Location: " + blastLocation.toString());
			writeLine(writer, "Output Folder: " + outputDir.toString());
			writeLine(writer, "Primer File: " + primerFile.toString());
			writeLine(writer, "Input File(s) :");

			if (Files.isDirectory(inputFile)) {
				try (Stream<Path> files = Files.list(inputFile)) {
					for (Path file : files.toList()) {
						writeLine(writer, file.toAbsolutePath().toString());
					}
				}
			} else {
				writeLine(writer, inputFile.toAbsolutePath().toString());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write QA log: " + qLog, e);
		}
	}

	public static void makeQALog(File qLog, String version, File outputDir, File inputFile, File primerFile, File bbToolsLocation, File blastLocation) {
		makeQALog(qLog.toPath(), version, outputDir.toPath(), inputFile.toPath(), primerFile.toPath(), bbToolsLocation.toPath(), blastLocation.toPath());
	}

	public static boolean checkVersion() {
		try (BufferedReader reader = Files.newBufferedReader(
				downloadReleasePageToTempFile(), StandardCharsets.UTF_8)) {
			int latestVersion = 0;
			String line;
			while ((line = reader.readLine()) != null) {
				Matcher matcher = RELEASE_TAG.matcher(line);
				while (matcher.find()) {
					latestVersion = Math.max(latestVersion, versionToInt(matcher.group(1)));
				}
			}
			return versionToInt(Dispatcher.version.replaceFirst("^[vV]", "")) >= latestVersion;
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static void putFastaEntry(Map<String, String> fasta, String id, StringBuilder sequence) {
		if (id != null && !sequence.isEmpty()) {
			fasta.put(id, sequence.toString());
		}
	}

	private static PrimerId parsePrimerId(String id) {
		int lastDash = id.lastIndexOf('-');
		if (lastDash < 0 || lastDash == id.length() - 1) {
			throw new IllegalArgumentException("Primer ID must end with -F, -R, or -P: " + id);
		}
		return new PrimerId(id.substring(0, lastDash), id.substring(lastDash + 1));
	}

	private static String normalizePrimerSequence(String id, String sequence) {
		if (sequence == null || sequence.isBlank()) {
			throw new IllegalArgumentException("Primer sequence is empty for: " + id);
		}
		String normalized = sequence.trim().toUpperCase();
		if (!VALID_PRIMER_SEQUENCE.matcher(normalized).matches()) {
			throw new IllegalArgumentException("Primer sequence contains incompatible characters:\n" + id + "\n" + sequence);
		}
		return normalized;
	}

	private static List<String> expandDegenerated(String sequence) {
		ArrayList<String> expanded = new ArrayList<>();
		expandDegenerated(sequence.toCharArray(), 0, expanded);
		return expanded;
	}

	private static void expandDegenerated(char[] sequence, int start, List<String> output) {
		for (int i = start; i < sequence.length; i++) {
			char[] replacements = DEGENERATE_BASES.get(sequence[i]);
			if (replacements == null) {
				continue;
			}
			char original = sequence[i];
			for (char replacement : replacements) {
				sequence[i] = replacement;
				expandDegenerated(sequence, i + 1, output);
			}
			sequence[i] = original;
			return;
		}
		output.add(new String(sequence));
	}

	private static void writePrimerTmpFile(Path output, Map<String, String> primers) {
		try {
			Files.createDirectories(output.getParent());
			try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
				for (Map.Entry<String, String> entry : primers.entrySet()) {
					writer.write(">" + entry.getKey());
					writer.newLine();
					writer.write(entry.getValue());
					writer.newLine();
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not write cleaned primer file: " + output, e);
		}
	}

	private static void runProcess(Path workingDirectory, String... command) {
		try {
			ProcessBuilder builder = new ProcessBuilder(command);

			if (workingDirectory != null) {
				builder.directory(workingDirectory.toFile());
			}

			try (Process process = builder.inheritIO().start()) {
				int exitCode = process.waitFor();

				if (exitCode != 0) {
					throw new IllegalStateException(
							"Command failed with exit code " + exitCode + ": "
									+ String.join(" ", command));
				}
			}

		} catch (IOException e) {
			throw new IllegalStateException(
					"Could not start command: " + String.join(" ", command), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(
					"Interrupted while running command: " + String.join(" ", command), e);
		}
	}

	private static List<File> listTsvFiles(File detailedReport) {
		try (Stream<Path> paths = Files.walk(detailedReport.toPath(), 2)) {
			return paths
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".tsv"))
					.map(Path::toFile)
					.toList();
		} catch (IOException e) {
			throw new IllegalStateException("Could not list BLAST report files in: " + detailedReport, e);
		}
	}

	private static void parseSingleBlastReport(File sampleReport, Map<String, String> primerDict,
	                                           int allowedMismatches, Map<String, Sample> sampleDict) {
		String sampleName = sampleReport.getName().replaceFirst("\\.tsv$", "");
		Sample sample = sampleDict.get(sampleName);
		if (sample == null) {
			throw new IllegalStateException("BLAST report has no matching sample entry: " + sampleName);
		}

		try (BufferedReader reader = Files.newBufferedReader(sampleReport.toPath(), StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("qseqid")) {
					continue;
				}
				parseBlastLine(line, sampleName, primerDict, allowedMismatches).ifPresent(result -> addBlastResult(sample, result));
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not parse BLAST report: " + sampleReport, e);
		}
	}

	private static Optional<BlastResult> parseBlastLine(String line, String sampleName,
	                                                    Map<String, String> primerDict, int allowedMismatches) {
		String[] fields = line.split("\t", -1);
		if (fields.length < 15) {
			return Optional.empty();
		}

		String qseqid = fields[0];
		String sseqid = fields[1];
		String primer = primerDict.get(sseqid);
		if (primer == null) {
			return Optional.empty();
		}

		int length = Integer.parseInt(fields[8]);
		int mismatches = Integer.parseInt(fields[3]);
		int expectedLength = primer.length();
		if (length > expectedLength || length < expectedLength - 2 || mismatches > allowedMismatches) {
			return Optional.empty();
		}

		return Optional.of(new BlastResult(
				sampleName,
				qseqid,
				sseqid,
				mismatches,
				Integer.parseInt(fields[9]),
				Integer.parseInt(fields[10]),
				length,
				fields[14]
		));
	}

	private static void addBlastResult(Sample sample, BlastResult result) {
		if (sample.getBlastResults().containsKey(result.getSubjectID())) {
			sample.addBlastResult(result.getSubjectID(), result);
		} else {
			sample.addNewBlastResult(result.getSubjectID(), result);
		}
	}

	private static void addContigsFromFasta(Sample sample, Path fasta) {
		try (BufferedReader reader = Files.newBufferedReader(fasta, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith(">")) {
					continue;
				}
				ContigHeader header = parseContigHeader(line.substring(1));
				sample.addContig(header.id(), header.description());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Could not parse contigs from: " + fasta, e);
		}
	}

	private static ContigHeader parseContigHeader(String rawHeader) {
		String trimmed = rawHeader.trim();
		int firstWhitespace = findFirstWhitespace(trimmed);
		if (firstWhitespace < 0) {
			return new ContigHeader(trimmed, "");
		}
		return new ContigHeader(trimmed.substring(0, firstWhitespace), trimmed.substring(firstWhitespace + 1).trim());
	}

	private static int findFirstWhitespace(String value) {
		for (int i = 0; i < value.length(); i++) {
			if (Character.isWhitespace(value.charAt(i))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean hasProbePrimers(Map<String, String> primerDict) {
		return primerDict.keySet().stream()
				.map(CommandMethods::parsePrimerId)
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

	private static void writeSampleConsolidatedResults(BufferedWriter writer, String sampleName, Sample sample,
	                                                   Map<String, String> primerDict, boolean qPcr) throws IOException {
		Map<String, ArrayList<BlastResult>> blastResults = sample.getBlastResults();
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
			writePrimerPairResults(writer, sampleName, sample, primerName, group, primerDict, qPcr);
		}
	}

	private static Map<String, PrimerGroup> groupPrimerHits(Iterable<String> primerHits) {
		Map<String, PrimerGroup> groups = new LinkedHashMap<>();
		for (String primer : primerHits) {
			PrimerId primerId = parsePrimerId(primer);
			PrimerGroup group = groups.computeIfAbsent(primerId.name(), ignored -> new PrimerGroup());
			group.add(primerId.type());
		}
		return groups;
	}

	private static void writePrimerPairResults(BufferedWriter writer, String sampleName, Sample sample, String primerName,
	                                           PrimerGroup group, Map<String, String> primerDict, boolean qPcr) throws IOException {
		for (String forwardType : group.forward()) {
			for (String reverseType : group.reverse()) {
				String forwardPrimer = primerName + "-" + forwardType;
				String reversePrimer = primerName + "-" + reverseType;
				List<BlastResult> forwardResults = sample.getBlastResults().getOrDefault(forwardPrimer, new ArrayList<>());
				List<BlastResult> reverseResults = sample.getBlastResults().getOrDefault(reversePrimer, new ArrayList<>());

				for (BlastResult forwardResult : forwardResults) {
					for (BlastResult reverseResult : reverseResults) {
						if (!forwardResult.getQueryID().equals(reverseResult.getQueryID())) {
							continue;
						}
						Amplicon amplicon = Amplicon.from(forwardResult, reverseResult);
						String contigDescription = sample.getContigDict().getOrDefault(amplicon.contig(), "");

						if (qPcr) {
							writeProbeRows(writer, sampleName, sample, primerName, group, primerDict,
									forwardPrimer, reversePrimer, forwardResult, reverseResult, amplicon, contigDescription);
						} else {
							writePcrRow(writer, sampleName, primerName, primerDict, forwardPrimer, reversePrimer,
									forwardResult, reverseResult, amplicon, contigDescription);
						}
					}
				}
			}
		}
	}

	private static void writeProbeRows(BufferedWriter writer, String sampleName, Sample sample, String primerName,
	                                   PrimerGroup group, Map<String, String> primerDict, String forwardPrimer,
	                                   String reversePrimer, BlastResult forwardResult, BlastResult reverseResult,
	                                   Amplicon amplicon, String contigDescription) throws IOException {
		for (String probeType : group.probe()) {
			String probePrimer = primerName + "-" + probeType;
			List<BlastResult> probeResults = sample.getBlastResults().getOrDefault(probePrimer, new ArrayList<>());
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
						endMismatch(forwardResult, primerDict),
						endMismatch(reverseResult, primerDict),
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
	                                Map<String, String> primerDict, String forwardPrimer, String reversePrimer,
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
				endMismatch(forwardResult, primerDict),
				endMismatch(reverseResult, primerDict)
		)));
		writer.newLine();
	}

	private static String endMismatch(BlastResult result, Map<String, String> primerDict) {
		String primer = primerDict.get(result.getSubjectID());
		if (primer == null) {
			return "";
		}
		return String.valueOf(result.getLength() - primer.length());
	}

	private static void writeLine(BufferedWriter writer, String line) throws IOException {
		writer.write(line);
		writer.newLine();
	}

	private static Path downloadReleasePageToTempFile() throws IOException {
		Path temp = Files.createTempFile("in-silico-pcr-releases", ".html");
		try (var input = URI.create("https://github.com/chmaraj/In_Silico_PCR/releases").toURL().openStream()) {
			Files.copy(input, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		temp.toFile().deleteOnExit();
		return temp;
	}

	private static int versionToInt(String version) {
		String[] fields = version.replaceFirst("^[vV]", "").split("\\.");
		StringBuilder normalized = new StringBuilder();
		for (String field : fields) {
			normalized.append(String.format("%03d", Integer.parseInt(field)));
		}
		return Integer.parseInt(normalized.toString());
	}

	private record PrimerId(String name, String type) {
	}

	private record ContigHeader(String id, String description) {
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

		ArrayList<String> forward() {
			return forward;
		}

		ArrayList<String> reverse() {
			return reverse;
		}

		ArrayList<String> probe() {
			return probe;
		}
	}
}
