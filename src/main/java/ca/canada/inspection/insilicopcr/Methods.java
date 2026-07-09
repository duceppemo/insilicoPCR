package ca.canada.inspection.insilicopcr;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;


import ca.canada.inspection.dispatchpcr.AppPaths;
import ca.canada.inspection.util.SequenceFileUtils;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;

@SuppressWarnings({"unused", "SameParameterValue"})
public class Methods {

	private static final HashMap<Character, Character[]> degenerates = new HashMap<>();
	private static Pattern degenRegex;

	private static final int GEL_MIN_BP = 0;
	private static final int GEL_MAX_BP = 25_000;
	private static final double GEL_LOG_OFFSET_BP = 50.0;

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
		HashMap<String, ArrayList<String>> primerNames = new HashMap<>();
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
						ArrayList<String> list = new ArrayList<>();
						list.add(primerType);
						primerNames.put(primerName, list);
					}else {
						primerNames.get(primerName).add(primerType);
					}
				}
			}
		}catch(IOException e) {
			throw new IllegalStateException("I/O operation failed", e);
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
		return new HashMap<>(SequenceFileUtils.createSampleMap(inputFile));
	}

	public static HashMap<String, Sample> createSampleDict(File inputFile) {
		return createSampleDict(inputFile.toPath());
	}

	// Parse a fasta file into a dictionary, where the ID is the key value for the sequence
	public static HashMap<String, String> parseFastaToDictionary(Path file){

		HashMap<String, String> fastaDict = new HashMap<>();

		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String id = null;
			StringBuilder sequence = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					continue;
				}
				if (line.startsWith(">")) {
					if (id != null && !sequence.isEmpty()) {
						fastaDict.put(id, sequence.toString());
					}
					id = line.substring(1).trim();
					sequence.setLength(0);
				} else {
					sequence.append(line.trim());
				}
			}
			if (id != null && !sequence.isEmpty()) {
				fastaDict.put(id, sequence.toString());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to parse FASTA file: " + file, e);
		}

		// Return the filled dictionary
		return fastaDict;
	}

	public static HashMap<String, String> parseFastaToDictionary(File file) {
		return parseFastaToDictionary(file.toPath());
	}

	// Process the primers in the primer dictionary
	public static Path processPrimers(HashMap<String, String> primerDict, TextArea outputField, Path workDir, String sep) {

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
			charDegen[i] = degenCharArray[i];
		}
		String degenRegexString = String.join("", new String(charDegen));
		degenRegex = Pattern.compile("[" + degenRegexString + "]");

		// This regex will find any incompatible characters in the primer sequences
		Pattern regex = Pattern.compile("[^ATCGRYSWKMBDHVN]");

		// Have to make a deep copy of the primerDict keys, otherwise we get a reference that changes when we change the primerDict
		List<String> keySet = new ArrayList<>(primerDict.keySet());
		for(String key : keySet) {
			String seq = primerDict.get(key);

			// Check for illegal bases
			Matcher matcher = regex.matcher(seq);
			if(matcher.find()) {
				String message = "Primer sequence contains incompatible characters:\n" + key + "\n" + seq;
				logMessage(outputField, message);
				throw new IllegalArgumentException(message);
			}

			// If the sequence contains degenerated bases, create sequences for all possible iterations
			Matcher degenMatcher = degenRegex.matcher(seq);
			if(degenMatcher.find()) {
				ArrayList<String> expandedSeq = expandDegenerated(seq, 0, new ArrayList<>());

				// Remove the original entry which contained degenerate bases, replace with all the possible sequences
				primerDict.remove(key);
				for(int i = 0; i < expandedSeq.size(); i++) {
					String newID = key + "_" + i;
					String newSeq = expandedSeq.get(i);
					primerDict.put(newID, newSeq);
				}
			}
		}

		// Must now write a primer file containing no degenerate bases for the BLAST
		Path cleanedPrimers = workDir.resolve("primer_tmp.fasta");
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
		return cleanedPrimers;
	}

	public static Path processPrimers(HashMap<String, String> primerDict, TextArea outputField, File workDir, String sep) {
		return processPrimers(primerDict, outputField, workDir.toPath(), sep);
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
						expandDegenerated(newSeq, j, primerContainer);

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
	@SuppressWarnings("resource")
	public static void makeExecutable(Path BLASTLocation) {
		String[] processCall = {"chmod", "+x", "makeblastdb", "blastn"};
		Process process = null;
		try {
			process = new ProcessBuilder(processCall).directory(BLASTLocation.toFile()).start();
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IllegalStateException("Unable to mark BLAST binaries executable. Exit code: " + exitCode);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to run chmod in: " + BLASTLocation, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while marking BLAST binaries executable", e);
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	public static void makeExecutable(File BLASTLocation) {
		makeExecutable(BLASTLocation.toPath());
	}

	// Make a BLAST database from the primers
	@SuppressWarnings("resource")
	public static void makeBlastDB(Path reference, Path BLASTLocation, TextArea outputField) {
		String[] processCall = {
				AppPaths.executable(BLASTLocation, "makeblastdb").toString(),
				"-dbtype", "nucl", "-hash_index", "-in", reference.toString()
		};
		Process process = null;
		try {
			process = new ProcessBuilder(processCall).start();
			int exitCode = process.waitFor();
			if (exitCode != 0) {
				throw new IllegalStateException("makeblastdb failed with exit code: " + exitCode);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to run makeblastdb for reference: " + reference, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while running makeblastdb", e);
		} finally {
			if (process != null) {
				process.destroy();
			}
		}
	}

	public static void makeBlastDB(File reference, File BLASTLocation, TextArea outputField) {
		makeBlastDB(reference.toPath(), BLASTLocation.toPath(), outputField);
	}

	// Add the correct result headers to the Blast tsv output file
	public static void addHeaderToTSV(Path tsvFile) {
		String tab = "\t";
		String[] headerFileIDs = {"qseqid", "sseqid", "positive", "mismatch", "gaps", "evalue",
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

		ArrayList<Path> reportList = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(detailedReport, 2)) {
			paths.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".tsv"))
					.forEach(reportList::add);
		} catch (IOException e) {
			throw new IllegalStateException("Unable to list BLAST reports in: " + detailedReport, e);
		}

		for (Path sampleReport : reportList) {
			String sampleName = sampleReport.getFileName().toString().replaceFirst("\\.tsv$", "");
			Sample sample = sampleDict.get(sampleName);
			if (sample == null) {
				continue;
			}

			try (BufferedReader reader = Files.newBufferedReader(sampleReport, StandardCharsets.UTF_8)) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isEmpty() || line.startsWith("qseqid")) {
						continue;
					}

					String[] fields = line.split("\t", -1);
					if (fields.length < 15) {
						continue;
					}

					String qseqid = fields[0];
					String sseqid = fields[1];

					String primer = primerDict.get(sseqid);
					if (primer == null) {
						continue;
					}

					int length = Integer.parseInt(fields[8]);
					int actualMismatches = Integer.parseInt(fields[3]);

					if (length <= primer.length()
							&& length >= primer.length() - 2
							&& actualMismatches <= mismatches) {

						int qstart = Integer.parseInt(fields[9]);
						int qend = Integer.parseInt(fields[10]);
						String sseq = fields[14];

						BlastResult result = new BlastResult(
								sampleName, qseqid, sseqid, actualMismatches, qstart, qend, length, sseq
						);

						sample.addBlastResult(sseqid, result);
					}
				}
			} catch (IOException e) {
				throw new IllegalStateException("Unable to parse BLAST report: " + sampleReport, e);
			}
		}
	}

	public static void parseBlastOutput(File consolidatedDir, File detailedReport, HashMap<String, String> primerDict,
	                                    int mismatches, HashMap<String, Sample> sampleDict) {
		parseBlastOutput(consolidatedDir.toPath(), detailedReport.toPath(), primerDict, mismatches, sampleDict);
	}

	public static void addContigDict(HashMap<String, Sample> sampleDict) {
		for (Map.Entry<String, Sample> entry : sampleDict.entrySet()) {
			String sampleName = entry.getKey();
			Sample sample = entry.getValue();

			if (sample.getFileType().equals("fastq")) {
				addContigsFromFasta(sample.getAssemblyFile(), sample, "assembly file for sample: " + sampleName);
			} else {
				for (Path file : sample.getFiles()) {
					addContigsFromFasta(file, sample, "sequence file for sample: " + sampleName + ": " + file);
				}
			}
		}
	}

	private static void addContigsFromFasta(Path fastaFile, Sample sample, String description) {
		try (BufferedReader reader = Files.newBufferedReader(fastaFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.startsWith(">")) {
					addContigHeader(line, sample);
				}
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to read " + description, e);
		}
	}

	private static void addContigHeader(String headerLine, Sample sample) {
		String fullLine = headerLine.substring(1).trim();
		String[] items = fullLine.split("\\s+", 2);
		String contigID = items[0];
		String description = items.length > 1 ? items[1] : "";
		sample.addContig(contigID, description);
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
		String header;
		if(qPCR) {
			header = String.join("\t", "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
					"ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
					"ForwardEndMismatch", "ReverseEndMismatch", "Probe", "ProbeLocation", "ProbeSize", "ProbeMismatches");
		}else {
			header = String.join("\t", "Sample", "Gene", "GenomeLocation", "AmpliconSize", "Contig", "Contig Description",
					"ForwardPrimers", "ReversePrimers", "ForwardMismatches", "ReverseMismatches",
					"ForwardEndMismatch", "ReverseEndMismatch");
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
					HashMap<String, HashMap<String, ArrayList<String>>> primers = new HashMap<>();

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
							HashMap<String, ArrayList<String>> list = new HashMap<>();
							ArrayList<String> fList = new ArrayList<>();
							ArrayList<String> rList = new ArrayList<>();
							ArrayList<String> pList = new ArrayList<>();
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
											String location = start + "-" + end;
											String size = String.valueOf(end - start + 1);
											String contig = fResult.getQueryID();
											String contigDescription = getContigDescription(sampleDict, key, contig);
											String fwdMismatch = String.valueOf(fResult.getMismatch());
											String revMismatch = String.valueOf(rResult.getMismatch());
											String fwdEndMismatch = String.valueOf(fResult.getLength() - primerDict.get(fResult.getSubjectID()).length());
											String revEndMismatch = String.valueOf(rResult.getLength() - primerDict.get(rResult.getSubjectID()).length());

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
															String locationP = startP + "-" + endP;
															String sizeP = String.valueOf(endP - startP + 1);
															String pMismatch = String.valueOf(pResult.getMismatch());

															// Probe only valid if it is contained within the surrounding amplicon
															if(startP >= start && endP <= end) {
																writer.write(String.join("\t", key, primerKey, location, size, contig,
																		contigDescription, fwdPrimer, revPrimer, fwdMismatch, revMismatch,
																		fwdEndMismatch, revEndMismatch, probePrimer, locationP, sizeP, pMismatch));
																writer.write(System.lineSeparator());
															}

														}
													}
												}
											}else {
												writer.write(String.join("\t", key, primerKey, location, size, contig, contigDescription,
														fwdPrimer, revPrimer, fwdMismatch, revMismatch, fwdEndMismatch, revEndMismatch));
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

	public static void makeSyntheticGel(Scene scene, Path consolidatedReport) {
		makeSyntheticGel(scene, new HashMap<>(), consolidatedReport);
	}

	public static void makeSyntheticGel(Scene scene, HashMap<String, Sample> sampleDict, Path consolidatedReport) {
		if (scene == null) {
			throw new IllegalArgumentException("Unable to draw synthetic gel because the application scene is not available.");
		}
		if (consolidatedReport == null || !Files.isRegularFile(consolidatedReport)) {
			throw new IllegalArgumentException("Consolidated report not found: " + consolidatedReport);
		}

		LinkedHashMap<String, ArrayList<Integer>> sampleBands = readSyntheticGelBands(consolidatedReport, sampleDict);
		if (sampleBands.isEmpty()) {
			throw new IllegalStateException("No amplicons were found in consolidated report: " + consolidatedReport);
		}

		Canvas canvas = drawSyntheticGelCanvas(scene, sampleBands);
		Path automaticOutput = defaultSyntheticGelOutput(consolidatedReport);
		saveCanvasAsPng(canvas, automaticOutput);

		Button saveButton = new Button("Save Image As...");
		saveButton.setOnAction(event -> {
			FileChooser chooser = new FileChooser();
			chooser.setTitle("Save Synthetic Gel Image");
			chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG image", "*.png"));
			chooser.setInitialFileName(automaticOutput.getFileName().toString());
			Path parent = automaticOutput.getParent();
			if (parent != null && Files.isDirectory(parent)) {
				chooser.setInitialDirectory(parent.toFile());
			}

			File selectedFile = chooser.showSaveDialog(null);
			if (selectedFile != null) {
				saveCanvasAsPng(canvas, selectedFile.toPath());
			}
		});

		HBox toolbar = new HBox(10, saveButton);
		toolbar.setAlignment(Pos.CENTER_LEFT);
		toolbar.setPadding(new Insets(8));

		ScrollPane scrollPane = new ScrollPane(canvas);
		scrollPane.setFitToHeight(false);
		scrollPane.setFitToWidth(false);

		BorderPane root = new BorderPane(scrollPane);
		root.setBottom(toolbar);

		var screenBounds = Screen.getPrimary().getVisualBounds();
		double toolbarHeight = 56;
		double windowMargin = 80;
		double preferredWidth = canvas.getWidth() + 32;
		double preferredHeight = canvas.getHeight() + toolbarHeight + 32;
		double maxWindowWidth = Math.max(900, screenBounds.getWidth() - windowMargin);
		double maxWindowHeight = Math.max(700, screenBounds.getHeight() - windowMargin);
		double windowWidth = Math.min(preferredWidth, maxWindowWidth);
		double windowHeight = Math.min(preferredHeight, maxWindowHeight);

		Stage stage = new Stage();
		stage.setTitle("Synthetic Gel - " + consolidatedReport.getFileName() + " - saved to " + automaticOutput.getFileName());
		stage.setScene(new Scene(root, windowWidth, windowHeight));
		stage.setMinWidth(Math.min(900, windowWidth));
		stage.setMinHeight(Math.min(650, windowHeight));
		stage.show();
	}

	private static Canvas drawSyntheticGelCanvas(Scene scene, LinkedHashMap<String, ArrayList<Integer>> sampleBands) {
		int laneCount = sampleBands.size() + 1; // lane 0 is the ladder
		double visibleWidth = Math.max(scene.getWidth(), 800);
		double visibleHeight = Math.max(scene.getHeight(), 500);

		// Keep the gel itself tall. Long sample names grow only the label area below the gel.
		double leftLabelWidth = 82;
		double rightInset = 18;
		double topInset = 18;
		double gelHeight = Math.max(540, visibleHeight * 1.10);
		double longestLabelWidth = longestLaneLabelWidth(sampleBands);
		double labelAreaHeight = Math.max(150, Math.min(360, longestLabelWidth * 0.72 + 36));
		double laneWidth = Math.max(74, (visibleWidth - leftLabelWidth - rightInset) / Math.max(laneCount, 11));
		double gelLeft = leftLabelWidth;
		double gelWidth = laneWidth * laneCount;
		double gelBottom = topInset + gelHeight;
		double canvasWidth = Math.max(visibleWidth, gelLeft + gelWidth + rightInset);
		double canvasHeight = gelBottom + labelAreaHeight;
		double bandHeight = 2.0;

		Canvas canvas = new Canvas(canvasWidth, canvasHeight);
		GraphicsContext gc = canvas.getGraphicsContext2D();

		gc.setFill(Color.WHITE);
		gc.fillRect(0, 0, canvasWidth, canvasHeight);

		drawGelBackground(gc, gelLeft, topInset, gelWidth, gelHeight, laneCount, laneWidth);
		drawLadder(gc, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
		drawLadderLabels(gc, topInset, gelHeight, gelLeft - 8);
		drawSamples(gc, sampleBands, gelLeft, topInset, gelHeight, laneWidth, bandHeight);
		drawGelBorder(gc, gelLeft, topInset, gelWidth, gelHeight);
		drawLaneLabels(gc, sampleBands, gelLeft, gelBottom + 34, laneWidth);
		return canvas;
	}

	private static double longestLaneLabelWidth(LinkedHashMap<String, ArrayList<Integer>> sampleBands) {
		double longest = "Ladder".length();
		for (String sampleName : sampleBands.keySet()) {
			longest = Math.max(longest, sampleName.length());
		}
		return longest * 6.2;
	}

	private static Path defaultSyntheticGelOutput(Path consolidatedReport) {
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

	private static void saveCanvasAsPng(Canvas canvas, Path outputFile) {
		try {
			Path parent = outputFile.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			WritableImage image = new WritableImage((int) Math.ceil(canvas.getWidth()), (int) Math.ceil(canvas.getHeight()));
			canvas.snapshot(new SnapshotParameters(), image);
			ImageIO.write(toBufferedImage(image), "png", outputFile.toFile());
		} catch (IOException e) {
			throw new IllegalStateException("Unable to save synthetic gel image: " + outputFile, e);
		}
	}

	private static BufferedImage toBufferedImage(WritableImage image) {
		int width = (int) image.getWidth();
		int height = (int) image.getHeight();
		BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		var reader = image.getPixelReader();
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				buffered.setRGB(x, y, reader.getArgb(x, y));
			}
		}
		return buffered;
	}

	private static LinkedHashMap<String, ArrayList<Integer>> readSyntheticGelBands(Path consolidatedReport,
	                                                                               HashMap<String, Sample> sampleDict) {
		LinkedHashMap<String, ArrayList<Integer>> sampleBands = new LinkedHashMap<>();
		if (sampleDict != null) {
			for (String sampleName : sampleDict.keySet()) {
				sampleBands.put(sampleName, new ArrayList<>());
			}
		}

		try (BufferedReader reader = Files.newBufferedReader(consolidatedReport, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("Sample\t")) {
					continue;
				}

				String[] fields = line.split("\t", -1);
				if (fields.length < 4) {
					continue;
				}

				String sampleName = fields[0];
				int ampliconSize;
				try {
					ampliconSize = Integer.parseInt(fields[3]);
				} catch (NumberFormatException ignored) {
					continue;
				}

				sampleBands.computeIfAbsent(sampleName, key -> new ArrayList<>()).add(ampliconSize);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to read consolidated report: " + consolidatedReport, e);
		}

		sampleBands.entrySet().removeIf(entry -> entry.getValue().isEmpty());
		return sampleBands;
	}

	private static void drawGelBackground(GraphicsContext gc, double x, double y, double width, double height,
	                                      int laneCount, double laneWidth) {
		gc.setFill(Color.rgb(238, 238, 238));
		gc.fillRect(x, y, width, height);

		for (int i = 0; i < laneCount; i++) {
			double laneX = x + (i * laneWidth);
			gc.setFill(i % 2 == 0 ? Color.rgb(244, 244, 244) : Color.rgb(232, 232, 232));
			gc.fillRect(laneX, y, laneWidth, height);

			gc.setStroke(Color.rgb(255, 255, 255));
			gc.setLineWidth(3.0);
			gc.strokeLine(laneX, y, laneX, y + height);
		}
		gc.strokeLine(x + width, y, x + width, y + height);

		// Very subtle horizontal gel texture, similar to a photographed electrophoresis gel.
		gc.setLineWidth(1.0);
		for (int row = 24; row < height; row += 42) {
			gc.setStroke(Color.rgb(246, 246, 246, 0.45));
			gc.strokeLine(x, y + row, x + width, y + row);
			gc.setStroke(Color.rgb(222, 222, 222, 0.25));
			gc.strokeLine(x, y + row + 2, x + width, y + row + 2);
		}
	}

	private static void drawGelBorder(GraphicsContext gc, double x, double y, double width, double height) {
		gc.setStroke(Color.BLACK);
		gc.setLineWidth(3.0);
		gc.strokeRect(x, y, width, height);
	}

	private static void drawLaneLabels(GraphicsContext gc, LinkedHashMap<String, ArrayList<Integer>> sampleBands,
	                                   double gelLeft, double labelY, double laneWidth) {
		gc.setFill(Color.BLACK);
		gc.setStroke(Color.BLACK);
		gc.setFont(new Font("Verdana", 10));

		drawBottomLaneLabel(gc, "Ladder", gelLeft + (laneWidth / 2), labelY);

		int laneIndex = 1;
		for (String sampleName : sampleBands.keySet()) {
			double x = gelLeft + (laneIndex * laneWidth) + (laneWidth / 2);
			drawBottomLaneLabel(gc, sampleName, x, labelY);
			laneIndex++;
		}
	}

	private static void drawBottomLaneLabel(GraphicsContext gc, String label, double centerX, double y) {
		gc.save();
		gc.translate(centerX, y);
		gc.rotate(45);
		gc.fillText(label, 0, 0);
		gc.restore();
	}

	public static void drawLadder(GraphicsContext gc, double gelLeft, double gelTop, double gelHeight,
	                              double laneWidth, double bandHeight) {
		int[] ladderSizes = {20000, 10000, 7000, 5000, 4000, 3000, 2000, 1500, 1000, 700, 500, 400, 300, 200, 100};

		for (int size : ladderSizes) {
			drawGelBand(gc, gelLeft, ladderY(gelTop, gelHeight, size), laneWidth, bandHeight, 0.45);
		}
	}

	private static void drawLadderLabels(GraphicsContext gc, double gelTop, double gelHeight, double labelRightX) {
		int[] ladderSizes = {20000, 10000, 7000, 5000, 4000, 3000, 2000, 1500, 1000, 700, 500, 400, 300, 200, 100};
		String[] ladderLabels = {"20kb", "10kb", "7kb", "5kb", "4kb", "3kb", "2kb", "1.5kb", "1kb", "700", "500", "400", "300", "200", "100"};

		gc.setFill(Color.BLACK);
		gc.setFont(new Font("Verdana", 10));
		gc.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
		for (int i = 0; i < ladderSizes.length; i++) {
			double y = ladderY(gelTop, gelHeight, ladderSizes[i]) + 4;
			gc.fillText(ladderLabels[i], labelRightX, y);
		}
		gc.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
	}

	private static double ladderY(double gelTop, double gelHeight, int basePairs) {
		return gelTop + gelHeight - (gelHeight * normalizedGelPosition(basePairs));
	}

	private static double normalizedGelPosition(int basePairs) {
		double clampedBasePairs = Math.max(GEL_MIN_BP, Math.min(GEL_MAX_BP, basePairs));
		double minLog = Math.log10(GEL_MIN_BP + GEL_LOG_OFFSET_BP);
		double maxLog = Math.log10(GEL_MAX_BP + GEL_LOG_OFFSET_BP);
		double valueLog = Math.log10(clampedBasePairs + GEL_LOG_OFFSET_BP);
		return Math.clamp((valueLog - minLog) / (maxLog - minLog), 0.0, 1.0);
	}

	public static void drawSamples(GraphicsContext gc, String consolidatedReport, HashMap<String, Sample> sampleDict, double gelLeft,
	                               double gelTop, double gelHeight, double laneWidth, double bandHeight) {
		drawSamples(gc, Path.of(consolidatedReport), sampleDict, gelLeft, gelTop, gelHeight, laneWidth, bandHeight);
	}

	public static void drawSamples(GraphicsContext gc, Path consolidatedReport, HashMap<String, Sample> sampleDict, double gelLeft,
	                               double gelTop, double gelHeight, double laneWidth, double bandHeight) {
		drawSamples(gc, readSyntheticGelBands(consolidatedReport, sampleDict), gelLeft, gelTop, gelHeight, laneWidth, bandHeight);
	}

	private static void drawSamples(GraphicsContext gc, LinkedHashMap<String, ArrayList<Integer>> sampleBands, double gelLeft,
	                                double gelTop, double gelHeight, double laneWidth, double baseBandHeight) {
		int laneIndex = 1; // lane 0 is the ladder
		for (Map.Entry<String, ArrayList<Integer>> sampleEntry : sampleBands.entrySet()) {
			String sampleName = sampleEntry.getKey();
			double x = gelLeft + (laneIndex * laneWidth);
			Map<Integer, Integer> bandCounts = countBandsByRoundedSize(sampleEntry.getValue());
			for (Map.Entry<Integer, Integer> bandEntry : bandCounts.entrySet()) {
				int size = bandEntry.getKey();
				int count = bandEntry.getValue();

				double laneVariation = deterministicRange(sampleName, 0.90, 1.10);
				double verticalJitter = deterministicRange(sampleName + ':' + size, -0.45, 0.45);
				double intensity = Math.min(1.0, bandIntensity(size, count) * laneVariation);
				double adjustedBandHeight = baseBandHeight + (intensity * 1.2);

				drawGelBand(gc, x, ladderY(gelTop, gelHeight, size) + verticalJitter, laneWidth, adjustedBandHeight, intensity);
			}
			laneIndex++;
		}
	}

	private static Map<Integer, Integer> countBandsByRoundedSize(ArrayList<Integer> bands) {
		Map<Integer, Integer> bandCounts = new LinkedHashMap<>();
		for (int size : bands) {
			int roundedSize = roundAmpliconSizeForGel(size);
			bandCounts.merge(roundedSize, 1, Integer::sum);
		}
		return bandCounts;
	}

	private static int roundAmpliconSizeForGel(int size) {
		if (size >= 1000) {
			return Math.round(size / 50.0f) * 50;
		}
		if (size >= 300) {
			return Math.round(size / 25.0f) * 25;
		}
		return Math.max(GEL_MIN_BP, Math.round(size / 10.0f) * 10);
	}

	private static double bandIntensity(int basePairs, int count) {
		double sizeFactor = 0.35 + (0.30 * (1.0 - normalizedGelPosition(basePairs)));
		double countFactor = Math.min(0.35, Math.max(0, count - 1) * 0.12);
		return Math.min(1.0, Math.max(0.30, sizeFactor + countFactor));
	}

	private static double deterministicRange(String key, double minimum, double maximum) {
		int hash = key == null ? 0 : key.hashCode();
		double unit = ((hash & 0x7fffffff) % 10_000) / 9_999.0;
		return minimum + ((maximum - minimum) * unit);
	}

	private static void drawGelBand(GraphicsContext gc, double x, double centerY, double laneWidth, double bandHeight, double intensity) {
		double bandWidth = Math.max(4, laneWidth * 0.66);
		double bandX = x + ((laneWidth - bandWidth) / 2.0);
		double bandY = centerY - (bandHeight / 2.0);

		// Soft dark halo around the band so thin bands stay visible without looking like solid rectangles.
		gc.setFill(Color.rgb(25, 25, 25, intensity * 0.20));
		gc.fillRect(bandX - 1.5, bandY - 2.0, bandWidth + 3.0, bandHeight + 4.0);

		// Main high-contrast dark band. Higher intensity means darker and slightly thicker.
		gc.setFill(Color.rgb(8, 8, 8, intensity));
		gc.fillRect(bandX, bandY, bandWidth, bandHeight);

		// Subtle highlight and lower shadow make the band look less computer-generated.
		gc.setFill(Color.rgb(255, 255, 255, Math.min(0.18, intensity * 0.14)));
		gc.fillRect(bandX, bandY + 0.5, bandWidth, Math.max(0.75, bandHeight * 0.22));

		gc.setFill(Color.rgb(0, 0, 0, Math.min(0.32, intensity * 0.24)));
		gc.fillRect(bandX, bandY + bandHeight - 0.75, bandWidth, 0.75);
	}

	// Simple method to print a message to the output TextArea
	public static void logMessage(TextArea outputField, String msg) {
		Platform.runLater(() -> outputField.appendText("\n" + msg + "\n"));
	}

}
