package ca.canada.inspection.insilicopcr;

import javafx.scene.Scene;
import javafx.application.Platform;
import javafx.scene.control.TextArea;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.BufferedWriter;
import java.util.stream.Stream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.util.SequenceFileUtils;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

public class Methods {

	private static final HashMap<Character, Character[]> degenerates = new HashMap<Character, Character[]>();
	private static Pattern degenRegex;

	// Input directory must contain at least one fastq/fasta format file
	public static boolean noFastaFile(Path inputFile) {
		if (Files.isDirectory(inputFile)) {
			try (Stream<Path> files = Files.list(inputFile)) {
				return files.noneMatch(SequenceFileUtils::looksLikeSequenceFile);
			} catch (IOException e) {
				throw new IllegalStateException("Unable to list input directory: " + inputFile, e);
			}
		}
		return !SequenceFileUtils.looksLikeSequenceFile(inputFile);
	}

	public static boolean noFastaFile(File inputFile) {
		return noFastaFile(inputFile.toPath());
	}

	public static boolean verifyPrimerFile(Path primerFile) {
		String line;
		HashMap<String, ArrayList<String>> primerNames = new HashMap<String, ArrayList<String>>();
		try(BufferedReader reader = new BufferedReader(Files.newBufferedReader(primerFile))){
			while((line = reader.readLine()) != null) {
				if(line.isEmpty()) {
					continue;
				}
				if(line.startsWith(">")) {
					String[] primerSections = line.split(">")[1].split("-");
					String[] primerNameSections = Arrays.copyOfRange(primerSections, 0, primerSections.length - 1);
					String primerName = String.join("", primerNameSections);
					String primerType = primerSections[primerSections.length - 1];
					if(!primerNames.containsKey(primerName)) {
						ArrayList<String> list = new ArrayList<String>();
						list.add(primerType);
						primerNames.put(primerName, list);
					}else {
						primerNames.get(primerName).add(primerType);
					}
				}
			}
		}catch(IOException e) {
			e.printStackTrace();
		}
		for(String key : primerNames.keySet()) {
			if(primerNames.get(key).contains("P")) {
				if(!primerNames.get(key).contains("F") && !primerNames.get(key).contains("R")) {
					return false;
				}
			}
		}
		return true;
	}

	public static boolean verifyPrimerFile(File primerFile) {
		return verifyPrimerFile(primerFile.toPath());
	}

	// Used to ensure a file is in fasta/fastq format.
	public static boolean verifyFastaFormat(Path checkFile) {
		return SequenceFileUtils.looksLikeSequenceFile(checkFile);
	}

	public static boolean verifyFastaFormat(File checkFile) {
		return verifyFastaFormat(checkFile.toPath());
	}

	// Create a list of samples
	public static HashMap<String, Sample> createSampleDict(Path inputFile) {
		return new HashMap<String, Sample>(SequenceFileUtils.createSampleMap(inputFile));
	}

	public static HashMap<String, Sample> createSampleDict(File inputFile) {
		return createSampleDict(inputFile.toPath());
	}

	// Parse a fasta file into a dictionary, where the ID is the key value for the sequence
	public static HashMap<String, String> parseFastaToDictionary(Path file){

		HashMap<String, String> fastaDict = new HashMap<String, String>();

		// First read in all lines
		String line;
		ArrayList<String> lines = new ArrayList<String>();
		try (BufferedReader reader = new BufferedReader(Files.newBufferedReader(file))){
			try {
				while((line = reader.readLine()) != null) {
					if(!line.isEmpty()) {
						lines.add(line);
					}
				}
			}catch(IOException e) {
				e.printStackTrace();
			}
			reader.close();
		}catch(IOException e) {
			e.printStackTrace();
		}
		// This will recreate the file in memory
		String joinedLines = String.join("\n", lines);

		//Split into entries
		String[] splitEntries = joinedLines.split(">");
		for(String entry : splitEntries) {

			//Get rid of first entry from the split, it will contain nothing
			if(!entry.isEmpty()) {
				String[] splitEntry = entry.split("\n");
				String id = splitEntry[0].trim();
				String seq = splitEntry[1].trim();
				if(seq.isEmpty()) {
					continue;
				}

				// Put the entry into the dictionary
				fastaDict.put(id, seq);
			}
		}

		// Return the filled dictionary
		return fastaDict;
	}

	public static HashMap<String, String> parseFastaToDictionary(File file) {
		return parseFastaToDictionary(file.toPath());
	}

	// Process the primers in the primer dictionary
	public static void processPrimers(HashMap<String, String> primerDict, TextArea outputField, Path outDir, String sep) {

		// Need to generate the degen Regex
		// Unfortunately cannot convert directly from Object[] to char[], or even from Character[] to char[]
		degenerates.put('R', new Character[] {'A', 'G'});
		degenerates.put('Y', new Character[] {'C', 'T'});
		degenerates.put('S', new Character[] {'G', 'C'});
		degenerates.put('W', new Character[] {'A', 'T'});
		degenerates.put('K', new Character[] {'G', 'T'});
		degenerates.put('M', new Character[] {'A', 'C'});
		degenerates.put('B', new Character[] {'G', 'C', 'T'});
		degenerates.put('D', new Character[] {'A', 'G', 'T'});
		degenerates.put('H', new Character[] {'A', 'C', 'T'});
		degenerates.put('V', new Character[] {'A', 'C', 'G'});
		degenerates.put('N', new Character[] {'A', 'C', 'G', 'T'});
//		Character[] degenCharArray = degenerates.keySet().toArray(new Character[degenerates.keySet().size()]);
		Character[] degenCharArray = degenerates.keySet().toArray(new Character[0]);
		char[] charDegen = new char[degenCharArray.length];
		for(int i = 0; i < charDegen.length; i++) {
			charDegen[i] = (char)degenCharArray[i];
		}
		String degenRegexString = String.join("", new String(charDegen));
		degenRegex = Pattern.compile("[" + degenRegexString + "]");

		// This regex will find any incompatible characters in the primer sequences
		Pattern regex = Pattern.compile("[^ATCGRYSWKMBDHVN]");

		// Have to make a deep copy of the primerDict keys, otherwise we get a reference that changes when we change the primerDict
		ArrayList<String> keySet = new ArrayList<String>();
//		for(String key : primerDict.keySet()) {
//			keySet.add(key);
//		}
		keySet.addAll(primerDict.keySet());
		for(String key : keySet) {
			String seq = primerDict.get(key);

			// Check for illegal bases
			Matcher matcher = regex.matcher(seq);
			if(matcher.find()) {
				logMessage(outputField, "Primer sequence contains incompatible characters:\n" + key + "\n" + seq);
				return;
			}

			// If the sequence contains degenerated bases, create sequences for all possible iterations
			Matcher degenMatcher = degenRegex.matcher(seq);
			if(degenMatcher.find()) {
				ArrayList<String> expandedSeq = expandDegenerated(seq, 0, new ArrayList<String>());

				// Remove the original entry which contained degenerate bases, replace with all the possible sequences
				primerDict.remove(key);
				for(int i = 0; i < expandedSeq.size(); i++) {
					String newID = key + "_" + Integer.toString(i);
					String newSeq = expandedSeq.get(i);
					primerDict.put(newID, newSeq);
				}
			}
		}

		// Must now write a primer file containing no degenerate bases for the BLAST
		Path cleanedPrimers = outDir.resolve("primer_tmp.fasta");
		try (BufferedWriter writer = Files.newBufferedWriter(cleanedPrimers, StandardCharsets.UTF_8)) {
			for(String key : primerDict.keySet()) {
				writer.write(">" + key);
				writer.newLine();
				writer.write(primerDict.get(key));
				writer.newLine();
			}
		}catch(IOException e) {
			throw new IllegalStateException("Unable to write cleaned primer file: " + cleanedPrimers, e);
		}
	}

	public static void processPrimers(HashMap<String, String> primerDict, TextArea outputField, File outDir, String sep) {
		processPrimers(primerDict, outputField, outDir.toPath(), sep);
	}

	// Expand the sequences that contain degenerate bases into every possibility
	public static ArrayList<String> expandDegenerated(String seq, int index, ArrayList<String> primerContainer){

		char[] seqChars = seq.toCharArray();
		// Due to recursive nature, need to keep going from where we left off
		for(int i = index; i < seq.length(); i++) {
			char c = seqChars[i];

			// Check if the current character is contained in the list of degenerate bases. If so, replace this instance with each possible base.
//			if(degenerates.keySet().contains(c)) {
			if(degenerates.containsKey(c)) {
				for(char s : degenerates.get(c)) {
					String newSeq = seq.replaceFirst(Character.toString(c), Character.toString(s));
					Matcher matcher = degenRegex.matcher(newSeq);

					// Check the resulting primers.
					// If more degenerate bases are found, do the same as above, but starting from the base following the one just replaced.
					if(matcher.find()) {
						int j = i + 1;
						ArrayList<String> expanded = expandDegenerated(newSeq, j, primerContainer);

						// If no more degenerate bases are found, we have reached the end, and can add the sequence to the list to be returned.
					}else {
						primerContainer.add(newSeq);
					}
				}
			}
		}
		return primerContainer;
	}

	// Make BLAST binaries executable
	public static void makeExecutable(Path BLASTLocation) {
		String[] processCall = {"chmod", "+x", "makeblastdb", "blastn"};
		try {
			Process p = new ProcessBuilder(processCall).directory(BLASTLocation.toFile()).start();
			try {
				p.waitFor();
			}catch(InterruptedException e) {
				e.printStackTrace();
			}
		}catch(IOException e) {
			e.printStackTrace();
		}
	}

	public static void makeExecutable(File BLASTLocation) {
		makeExecutable(BLASTLocation.toPath());
	}

	// Make a BLAST database from the primers
	public static void makeBlastDB(Path reference, Path BLASTLocation, TextArea outputField) {
		String in = reference.toString();
		String[] windowsFullProcessCall = {AppPaths.executable(BLASTLocation, "makeblastdb").toString(),
				"-dbtype", "nucl", "-hash_index", "-in", in};
		String[] linuxFullProcessCall = {AppPaths.executable(BLASTLocation, "makeblastdb").toString(),
				"-dbtype", "nucl", "-hash_index", "-in", in};
		try{
			Process p;
			if(System.getProperty("os.name").contains("Windows")) {
				p = new ProcessBuilder(windowsFullProcessCall).start();
			}else {
				p = new ProcessBuilder(linuxFullProcessCall).start();
			}
			try {
				p.waitFor();
			}catch(InterruptedException e) {
				e.printStackTrace();
			}
		}catch(IOException e) {
			e.printStackTrace();
		}
	}

	public static void makeBlastDB(File reference, File BLASTLocation, TextArea outputField) {
		makeBlastDB(reference.toPath(), BLASTLocation.toPath(), outputField);
	}

	// Add the correct result headers to the Blast tsv output file
	public static void addHeaderToTSV(Path tsvFile) {
		String tab = "\t";
		String[] headerFileIDs = new String[] {"qseqid", "sseqid", "positive", "mismatch", "gaps", "evalue",
				"bitscore", "slen", "length", "qstart", "qend", "qseq", "sstart", "send", "sseq"};
		String header = String.join(tab, headerFileIDs);
		try {
			ArrayList<String> lines = new ArrayList<>(Files.readAllLines(tsvFile, StandardCharsets.UTF_8));
			lines.addFirst(header);
			Files.write(tsvFile, lines, StandardCharsets.UTF_8);
		}catch(IOException e) {
			throw new IllegalStateException("Unable to add header to BLAST TSV: " + tsvFile, e);
		}
	}

	public static void addHeaderToTSV(File tsvFile) {
		addHeaderToTSV(tsvFile.toPath());
	}

	// Fills the sampleDict to be used in the consolidated report method
	public static void parseBlastOutput(Path consolidatedDir, Path detailedReport, HashMap<String, String> primerDict,
	                                    int mismatches, HashMap<String, Sample> sampleDict) {

		// List of blast report files
		ArrayList<Path> reportList = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(detailedReport, 2)) {
			paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".tsv"))
					.forEach(reportList::add);
		}catch(IOException e) {
			throw new IllegalStateException("Unable to list BLAST reports in: " + detailedReport, e);
		}

		for(Path sampleReport : reportList) {
			String sampleName = sampleReport.getFileName().toString().split("\\.tsv")[0];
			String line;
			try (BufferedReader reader = Files.newBufferedReader(sampleReport, StandardCharsets.UTF_8)) {
				while((line = reader.readLine()) != null) {
					if(line.isEmpty() || line.startsWith("qseqid")) {
						continue;
					}
					String[] fields = line.split("\t");
					String qseqid = fields[0];
					String sseqid = fields[1];
					int length = Integer.parseInt(fields[8]);
					int weightedLength = primerDict.get(sseqid).length();
					int actualMismatches = Integer.parseInt(fields[3]);

					if(length <= weightedLength && length >= (weightedLength - 2) && actualMismatches <= mismatches) {

						int qstart = Integer.parseInt(fields[9]);
						int qend = Integer.parseInt(fields[10]);
						String sseq = fields[14];
						if(sampleDict.get(sampleName).getBlastResults().containsKey(sseqid)) {
							sampleDict.get(sampleName).addBlastResult(sseqid, new BlastResult(sampleName, qseqid, sseqid, actualMismatches, qstart, qend, length, sseq));
						}else {
							sampleDict.get(sampleName).addNewBlastResult(sseqid, new BlastResult(sampleName, qseqid, sseqid, actualMismatches, qstart, qend, length, sseq));
						}
					}
				}
			}catch(IOException e) {
				throw new IllegalStateException("Unable to parse BLAST report: " + sampleReport, e);
			}
		}
	}

	public static void parseBlastOutput(File consolidatedDir, File detailedReport, HashMap<String, String> primerDict,
	                                    int mismatches, HashMap<String, Sample> sampleDict) {
		parseBlastOutput(consolidatedDir.toPath(), detailedReport.toPath(), primerDict, mismatches, sampleDict);
	}

	public static void addContigDict(HashMap<String, Sample> sampleDict) {
		String line;
		for(String key : sampleDict.keySet()) {
			if(sampleDict.get(key).getFileType().equals("fastq")) {
				try(BufferedReader reader = new BufferedReader(Files.newBufferedReader(sampleDict.get(key).getAssemblyFile()))){
					while((line = reader.readLine()) != null) {
						if(line.startsWith(">")) {
							String fullLine = line.split(">")[1];
							String[] items = fullLine.split(" ");
							String contigID = items[0];
							if(items.length > 1) {
								String[] contigDescItems = Arrays.copyOfRange(items, 1, items.length);
								String description = String.join(" ", contigDescItems);
								sampleDict.get(key).addContig(contigID, description);
							}
							else {
								sampleDict.get(key).addContig(contigID, "");
							}
						}
					}
				}catch(IOException e) {
					e.printStackTrace();
				};
			}else {
				for(Path file : sampleDict.get(key).getFiles()) {
					try(BufferedReader reader = new BufferedReader(Files.newBufferedReader(file))){
						while((line = reader.readLine()) != null) {
							if(line.startsWith(">")) {
								String fullLine = line.split(">")[1];
								String[] items = fullLine.split(" ");
								String contigID = items[0];
								if(items.length > 1) {
									String[] contigDescItems = Arrays.copyOfRange(items, 1, items.length);
									String description = String.join(" ", contigDescItems);
									sampleDict.get(key).addContig(contigID, description);
								}else {
									sampleDict.get(key).addContig(contigID, "");
								}
							}
						}
					}catch(IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	// Makes the final consolidated report from the multiple blast reports
	public static void makeConsolidatedReport(Path consolidatedDir, String sep, HashMap<String, Sample> sampleDict,
	                                          HashMap<String, String> primerDict) {

		// Check to see if this is a qPCR or a regular PCR for formatting purposes
		boolean qPCR = false;
		for(String key : primerDict.keySet()) {
			if(key.split("-")[key.split("-").length - 1].startsWith("P")) {
				qPCR = true;
				break;
			}
		}

		// The header for the consolidated report
		String header = "";
		if(qPCR) {
			header = String.join("\t", new String[] {"Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
					"ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
					"ForwardEndMismatch", "ReverseEndMismatch", "Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches"});
		}else {
			header = String.join("\t", new String[] {"Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
					"ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
					"ForwardEndMismatch", "ReverseEndMismatch"});
		}

		// Generate the file to be filled in
		Path consolidatedReport = consolidatedDir.resolve("report.tsv");
		int temp = 1;
		while(Files.exists(consolidatedReport)) {
			consolidatedReport = consolidatedDir.resolve("report(" + temp + ").tsv");
			temp++;
		}
		try (BufferedWriter writer = Files.newBufferedWriter(consolidatedReport, StandardCharsets.UTF_8)) {
			writer.write(header);
			writer.write(System.lineSeparator());

			for(String key : sampleDict.keySet()) {

				// Set up all necessary values
				Sample sample = sampleDict.get(key);
//				String sampleName = key;
//				HashMap<String, ArrayList<BlastResult>> blastResults = sampleDict.get(key).getBlastResults();
				HashMap<String, ArrayList<BlastResult>> blastResults = sampleDict.get(key).getBlastResults();
				if(!blastResults.isEmpty()) {
//					String[] primerHits = blastResults.keySet().toArray(new String[blastResults.keySet().size()]); // Similar to what we did with the degenRegex issue
					String[] primerHits = blastResults.keySet().toArray(new String[0]); // Similar to what we did with the degenRegex issue
					HashMap<String, HashMap<String, ArrayList<String>>> primers = new HashMap<String, HashMap<String, ArrayList<String>>>();

					/* What this is actually doing is placing the primers into a hashmap based on their base name, alongside
					 *A list of directions. Therefore, a primer set of NAME-F and NAME-R would be listed under NAME with
					 *Directions F and R. Similarly, a degenerate primer of NAME-F_1, NAME-F_2, NAME-R_1, and NAME-R_2 would be
					 *listed under NAME with directions F_1, F_2, R_1, and R_2
					 */
					for(String primer : primerHits) {
						String[] splitPrimer = primer.split("-");
						String direction = splitPrimer[splitPrimer.length - 1];
						String primerName = String.join("-", Arrays.copyOfRange(splitPrimer, 0, splitPrimer.length - 1));
						if(!primers.containsKey(primerName)) {
							HashMap<String, ArrayList<String>> list = new HashMap<String, ArrayList<String>>();
							ArrayList<String> fList = new ArrayList<String>();
							ArrayList<String> rList = new ArrayList<String>();
							ArrayList<String> pList = new ArrayList<String>();
							if(direction.startsWith("F")) {
								fList.add(direction);
							}else if(direction.startsWith("R")) {
								rList.add(direction);
							}else if(direction.startsWith("P")) {
								pList.add(direction);
							}
							list.put("F", fList);
							list.put("R", rList);
							list.put("P", pList);
							primers.put(primerName, list);
						}else {
							if(direction.startsWith("F")) {
								primers.get(primerName).get("F").add(direction);
							}else if(direction.startsWith("R")) {
								primers.get(primerName).get("R").add(direction);
							}else if(direction.startsWith("P")) {
								primers.get(primerName).get("P").add(direction);
							}
						}
					}

					// Check if primer pairs are present
					for(String primerKey : primers.keySet()) {
						HashMap<String, ArrayList<String>> primersList = primers.get(primerKey);
						if(!primersList.get("F").isEmpty() && !primersList.get("R").isEmpty()) { // Have both F and R primers
							for(String fPrimer : primersList.get("F")) {
								for(String rPrimer : primersList.get("R")) {
									String fwdPrimer = primerKey + "-" + fPrimer;
									String revPrimer = primerKey + "-" + rPrimer;

									for(BlastResult fResult : sample.getBlastResults().get(fwdPrimer)) {
										for(BlastResult rResult : sample.getBlastResults().get(revPrimer)) {

											// If this pair of primers are not on the same contig, skip and keep going
											if(!fResult.getQueryID().equals(rResult.getQueryID())) {
												continue;
											}
											int startF = fResult.getStart();
											int endF = fResult.getEnd();
											int startR = rResult.getStart();
											int endR = rResult.getEnd();
											Integer[] positions = {startF, endF, startR, endR};
											int start = Collections.min(Arrays.asList(positions));
											int end = Collections.max(Arrays.asList(positions));
											String location = Integer.toString(start) + "-" + Integer.toString(end);
											String size = Integer.toString(end - start + 1);
											String contig = fResult.getQueryID();
											String contigDescription = getContigDescription(sampleDict, key, contig);
											String fwdMismatch = Integer.toString(fResult.getMismatch());
											String revMismatch = Integer.toString(rResult.getMismatch());
											String fwdEndMismatch = Integer.toString(fResult.getLength() - primerDict.get(fResult.getSubjectID()).length());
											String revEndMismatch = Integer.toString(rResult.getLength() - primerDict.get(rResult.getSubjectID()).length());

											// If a qPCR probe exists
											if(qPCR) {
												if(!primersList.get("P").isEmpty()) {
													for(String pPrimer : primersList.get("P")) {
														String probePrimer = primerKey + "-" + pPrimer;
														for(BlastResult pResult : sample.getBlastResults().get(probePrimer)) {
															if(!pResult.getQueryID().equals(fResult.getQueryID())) {
																continue;
															}
															int startP = pResult.getStart();
															int endP = pResult.getEnd();
															String locationP = Integer.toString(startP) + "-" + Integer.toString(endP);
															String sizeP = Integer.toString(endP - startP + 1);
															String pMismatch = Integer.toString(pResult.getMismatch());

															// Probe only valid if it is contained within the surrounding amplicon
															if(startP >= start && endP <= end) {
																writer.write(String.join("\t", new String[] {key, primerKey, location, size, contig,
																		contigDescription, fwdPrimer, revPrimer, fwdMismatch, revMismatch,
																		fwdEndMismatch, revEndMismatch, probePrimer, locationP, sizeP, pMismatch}));
																writer.write(System.lineSeparator());
															}
														}
													}
												}
											}else {
												writer.write(String.join("\t", new String[] {key, primerKey, location, size, contig, contigDescription,
														fwdPrimer, revPrimer, fwdMismatch, revMismatch, fwdEndMismatch, revEndMismatch}));
												writer.write(System.lineSeparator());
											}
										}
									}
								}
							}
						}
					}
				}
			}
		}catch(IOException e) {
			throw new IllegalStateException("Unable to write consolidated report: " + consolidatedReport, e);
		}
	}

	public static void makeConsolidatedReport(Path consolidatedDir, String sep, HashMap<String, Sample> sampleDict,
	                                          HashMap<String, String> primerDict, TextArea outputField) {
		makeConsolidatedReport(consolidatedDir, sep, sampleDict, primerDict);
	}

	public static void makeConsolidatedReport(File consolidatedDir, String sep, HashMap<String, Sample> sampleDict,
	                                          HashMap<String, String> primerDict, TextArea outputField) {
		makeConsolidatedReport(consolidatedDir.toPath(), sep, sampleDict, primerDict, outputField);
	}

	public static String getContigDescription(HashMap<String, Sample> sampleDict, String sampleName, String contig) {
		Sample sample = sampleDict.get(sampleName);
		HashMap<String, String> contigDict = sample.getContigDict();
		if(contigDict.containsKey(contig)) {
			return contigDict.get(contig);
		}
		return "";
	}

	public static void makeQALog(File qLog, String version, File outputDir, File inputFile, File primerFile, File BBToolsLocation, File BLASTLocation) {
		makeQALog(qLog.toPath(), version, outputDir.toPath(), inputFile.toPath(), primerFile.toPath(), BBToolsLocation.toPath(), BLASTLocation.toPath());
	}

	public static void makeQALog(Path qLog, String version, Path outputDir, Path inputFile, Path primerFile, Path BBToolsLocation, Path BLASTLocation) {
		try(BufferedWriter writer = Files.newBufferedWriter(qLog, StandardCharsets.UTF_8)) {
			String sep = System.lineSeparator();
			writer.write("In Silico PCR version: " + version);
			writer.write(sep);
			DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
			Date date = new Date();
			writer.write("Date run: " + dateFormat.format(date));
			writer.write(sep);
			writer.write("Run by user: " + System.getProperty("user.name"));
			writer.write(sep);
			writer.write("BBTools Location: " + BBToolsLocation);
			writer.write(sep);
			writer.write("BLAST Location: " + BLASTLocation);
			writer.write(sep);
			writer.write("Output Folder: " + outputDir);
			writer.write(sep);
			writer.write("Primer File: " + primerFile);
			writer.write(sep);
			writer.write("Input File(s) :");
			if(Files.isDirectory(inputFile)) {
				try (Stream<Path> entries = Files.list(inputFile)) {
					for (Path file : entries.sorted().toList()) {
						writer.write(sep);
						writer.write(file.toString());
					}
				}
			}else {
				writer.write(sep);
				writer.write(inputFile.toString());
			}
		}catch(IOException e) {
			throw new IllegalStateException("Unable to write QA log: " + qLog, e);
		}
	}

	public static boolean checkVersion() {
		return false;
	}

	// Make a synthetic gel image of what the PCR would look like
	public static void makeSyntheticGel(Scene scene, HashMap<String, Sample> sampleDict, File consolidatedReport) {
		makeSyntheticGel(scene, sampleDict, consolidatedReport.toPath());
	}

	public static void makeSyntheticGel(Scene scene, HashMap<String, Sample> sampleDict, Path consolidatedReport) {
		Canvas canvas = new Canvas();
		if(sampleDict.keySet().size() < 20) {
			canvas.setWidth(scene.getWidth());
		}else {
			canvas.setWidth((scene.getWidth() / 22) * sampleDict.keySet().size());
		}
		canvas.setHeight(scene.getHeight());
		GraphicsContext gc = canvas.getGraphicsContext2D();

		gc.setStroke(Color.WHITE);
		gc.setFill(Color.BLACK);
		double laneWidth = canvas.getWidth() / 22;
		double laneHeight = canvas.getHeight() * 8 / 10;
		double horizontalInset = canvas.getWidth() * 1 / 20;
		double verticalInset = (canvas.getHeight() * 2 / 10) - 20;
		double bandWidth = 5;
		for(int i = 0; i < 20; i++) {
			gc.fillRect((i * laneWidth) + horizontalInset, verticalInset, laneWidth, laneHeight);
			gc.strokeRect((i * laneWidth) + horizontalInset, verticalInset, laneWidth, laneHeight);
		}

		double controlInset = verticalInset + (laneHeight * 2 / 10);
		double controlHeight = laneHeight * 6 / 10;

		drawLadder(gc, horizontalInset, controlInset, controlHeight, laneWidth, bandWidth);
//		drawSamples(gc, sampleDict, horizontalInset, verticalInset, laneWidth, bandWidth);

		gc.save();
		gc.rotate(-90);
		gc.setFont(new Font("Verdana", 10));
		gc.strokeText("Ladder", -verticalInset + 3, horizontalInset + (laneWidth / 2) + 5);
	}

	public static void drawLadder(GraphicsContext gc, double horizontalInset, double controlInset, double controlHeight, double laneWidth, double bandWidth) {
		// Set out the max and min band heights, corresponding to 20kb and 100bp, respectively.
		gc.setFill(Color.WHITE);
		gc.fillRect(horizontalInset, controlInset, laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + controlHeight, laneWidth, bandWidth);


		// Normalize control band values so they make sense
		double band10kb = (Math.log10(10000) - 2) / 2.3;
		double band7kb = (Math.log10(7000) - 2) / 2.3;
		double band5kb = (Math.log10(5000) - 2) / 2.3;
		double band4kb = (Math.log10(4000) - 2) / 2.3;
		double band3kb = (Math.log10(3000) - 2) / 2.3;
		double band2kb = (Math.log10(2000) - 2) / 2.3;
		double band1_5kb = (Math.log10(1500) - 2) / 2.3;
		double band1kb = (Math.log10(1000) - 2) / 2.3;
		double band700 = (Math.log10(700) - 2) / 2.3;
		double band500 = (Math.log10(500) - 2) / 2.3;
		double band400 = (Math.log10(400) - 2) / 2.3;
		double band300 = (Math.log10(300) - 2) / 2.3;
		double band200 = (Math.log10(200) - 2) / 2.3;

		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band10kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band7kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band5kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band4kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band3kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band2kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band1_5kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band1kb)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band700)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band500)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band400)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band300)), laneWidth, bandWidth);
		gc.fillRect(horizontalInset, controlInset + (controlHeight - (controlHeight * band200)), laneWidth, bandWidth);

		gc.setStroke(Color.BLACK);
		gc.strokeText("20kb", 5, controlInset + 5);
		gc.strokeText("10kb", 5, controlInset + (controlHeight - (controlHeight * band10kb)) + 5);
		gc.strokeText("7kb", 5, controlInset + (controlHeight - (controlHeight * band7kb)) + 5);
		gc.strokeText("5kb", 5, controlInset + (controlHeight - (controlHeight * band5kb)) + 5);
		gc.strokeText("4kb", 5, controlInset + (controlHeight - (controlHeight * band4kb)) + 5);
		gc.strokeText("3kb", 5, controlInset + (controlHeight - (controlHeight * band3kb)) + 5);
		gc.strokeText("2kb", 5, controlInset + (controlHeight - (controlHeight * band2kb)) + 5);
		gc.strokeText("1.5kb", 5, controlInset + (controlHeight - (controlHeight * band1_5kb)) + 5);
		gc.strokeText("1kb", 5, controlInset + (controlHeight - (controlHeight * band1kb)) + 5);
		gc.strokeText("700", 5, controlInset + (controlHeight - (controlHeight * band700)) + 5);
		gc.strokeText("500", 5, controlInset + (controlHeight - (controlHeight * band500)) + 5);
		gc.strokeText("400", 5, controlInset + (controlHeight - (controlHeight * band400)) + 5);
		gc.strokeText("300", 5, controlInset + (controlHeight - (controlHeight * band300)) + 5);
		gc.strokeText("200", 5, controlInset + (controlHeight - (controlHeight * band200)) + 5);
		gc.strokeText("100", 5, controlInset + controlHeight + 5);
	}

	public static void drawSamples(GraphicsContext gc, String consolidatedReport, HashMap<String, Sample> sampleDict, double horizontalInset,
	                               double verticalInset, double laneWidth, double bandWidth) {
		drawSamples(gc, Path.of(consolidatedReport), sampleDict, horizontalInset, verticalInset, laneWidth, bandWidth);
	}

	public static void drawSamples(GraphicsContext gc, Path consolidatedReport, HashMap<String, Sample> sampleDict, double horizontalInset,
	                               double verticalInset, double laneWidth, double bandWidth) {
		String line;
		ArrayList<String> lines = new ArrayList<String>();
		ArrayList<String> targets = new ArrayList<String>();
		try(BufferedReader reader = Files.newBufferedReader(consolidatedReport, StandardCharsets.UTF_8)){
			while((line = reader.readLine()) != null) {
				lines.add(line);
			}
		}catch(IOException e) {
			throw new IllegalStateException("Unable to read consolidated report: " + consolidatedReport, e);
		}
		for(String item : lines) {
			String[] fields = item.split("\t");
			if(!targets.contains(fields[1])) {
				targets.add(fields[1]);
			}
		}
		for(String target : targets) {
			for(String item : lines) {
				String[] fields = item.split("\t");
				if(fields[1].equals(target)) {
					int size = Integer.parseInt(fields[3]);
					double band = (Math.log10(size) - 2) / 2.3;
				}
			}
		}

	}

	// Simple method to print a message to the output TextArea
	public static void logMessage(TextArea outputField, String msg) {
		Platform.runLater(() -> outputField.appendText("\n" + msg + "\n"));
	}

}
