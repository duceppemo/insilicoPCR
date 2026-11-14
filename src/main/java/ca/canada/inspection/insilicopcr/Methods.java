package ca.canada.inspection.insilicopcr;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.stream.Stream;

import ca.canada.inspection.pipeline.PipelineException;
import ca.canada.inspection.util.SequenceFileUtils;

@SuppressWarnings({"unused", "SameParameterValue"})
public class Methods {

	// Input directory must contain at least one fastq/fasta format file
	public static boolean noFastaFile(Path inputFile) {
		if (Files.isDirectory(inputFile)) {
			try (Stream<Path> files = Files.list(inputFile)) {
				return files.noneMatch(SequenceFileUtils::looksLikeSequenceFile);
			} catch (IOException e) {
				throw new PipelineException("Unable to list input directory: " + inputFile, e);
			}
		}
		return !SequenceFileUtils.looksLikeSequenceFile(inputFile);
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
					// The ID ends at the first blank; the rest is a description (it may
					// declare product sizes, e.g. size=420-450, which holds a dash too).
					String[] primerSections = line.substring(1).trim().split("\\s+", 2)[0].split("-");
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

	// Used to ensure a file is in fasta/fastq format.
	public static boolean verifyFastaFormat(Path checkFile) {
		return SequenceFileUtils.looksLikeSequenceFile(checkFile);
	}

	// Simple method to print a message to the output TextArea
	public static void logMessage(TextArea outputField, String msg) {
		Platform.runLater(() -> outputField.appendText("\n" + msg + "\n"));
	}

}
