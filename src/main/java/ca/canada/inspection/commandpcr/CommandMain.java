package ca.canada.inspection.commandpcr;

import ca.canada.inspection.insilicopcr.Find;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.dispatchpcr.Dispatcher;
import ca.canada.inspection.dispatchpcr.AppPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class CommandMain {

	public static String sep = java.io.File.separator;

	private Path inputFile = null, outDir = null, primerFile = null;
	private int threads = Runtime.getRuntime().availableProcessors();
	private int memJava = 4;
	private double evalue = Double.parseDouble("1e5");
	private Path detailedDir, consolidatedDir;
	private Path BBToolsLocation, BLASTLocation, JavaLocation;
	private String javaCall;
	private int mismatches = 0;
	private HashMap<String, String> primerDict = new HashMap<String, String>();
	private HashMap<String, Sample> sampleDict = new HashMap<String, Sample>();
	private boolean fastqPresent = false;

	public CommandMain(Path inputFile, Path outDir, Path primerFile, int threads, int mismatches, double evalue) {
		this.inputFile = inputFile;
		this.outDir = outDir;
		this.primerFile = primerFile;
		this.threads = threads;
		this.mismatches = mismatches;
		this.evalue = evalue;
	}

	public void run() {

		if(System.getProperties().getProperty("os.name").contains("Windows")) {
			String[] command = {"wmic", "computersystem", "get", "TotalPhysicalMemory"};
			try {
				String line;
				ArrayList<String> output = new ArrayList<String>();
				Process p = Runtime.getRuntime().exec(command);
				BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
				while((line = reader.readLine()) != null) {
					output.add(line);
				}
				String fullOutput = String.join("", output);
				String trimmedOutput = fullOutput.split("\\s+")[1];
				memJava = Integer.parseInt(trimmedOutput) / 1000000 / 2;  // G
			}catch(IOException e) {
				e.printStackTrace();
			}
		}else {
			String[] command = {"grep", "MemTotal", "/proc/meminfo"};
			try {
				String line;
				ArrayList<String> output = new ArrayList<String>();
				Process p = new ProcessBuilder(command).start();
				BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
				while((line = reader.readLine()) != null) {
					output.add(line);
				}
				String fullOutput = String.join("",output);
				String trimmedOutput = fullOutput.split("\\s+")[1];
				memJava = Integer.parseInt(trimmedOutput) / 1000000 / 2;  // G
			}catch(IOException e) {
				e.printStackTrace();
			}
		}

		RunPCRTask task = new RunPCRTask();
		Thread t = new Thread(task);
		t.setDaemon(true);
		t.start();
		try {
			t.join();
		}catch(InterruptedException e) {
			e.printStackTrace();
		}
	}

	// Makes directories within the output directory
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

	// Find dependencies
	public void findDependencies() {
		AppPaths.RuntimeLayout layout = AppPaths.discover();
		BBToolsLocation = layout.bbmapDirectory();
		BLASTLocation = layout.blastBinDirectory();
		JavaLocation = layout.javaExecutable().getParent();
		javaCall = layout.javaCommand();

		System.out.println("Application root: " + layout.appRoot());
		System.out.println("BBMap: " + BBToolsLocation);
		System.out.println("BLAST: " + BLASTLocation);
		System.out.println("Java: " + javaCall);
	}

	// Main body of the pipeline, runs the contained methods in order
	public class RunPCRTask implements Runnable {

		public RunPCRTask() {

		}

		public void run() {

			long startTime = System.nanoTime();

			System.out.println("Beginning Program Run");
			findDependencies();
			System.out.println("Found Dependencies");
			makeDirectories();
			System.out.println("Created Directories");
			sampleDict = CommandMethods.createSampleDict(inputFile);
			System.out.println("Created Sample Dictionary");
			primerDict = CommandMethods.parseFastaToDictionary(primerFile);
			System.out.println("Created Primer Dictionary");
			CommandMethods.processPrimers(primerDict, outDir, sep);
			System.out.println("Finished Formatting Primers");
			// Check if any fastq files are present
			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {
					System.out.println("FastQ files identified, conducting baiting and assembly");
					fastqPresent = true;
					break;
				}
			}
			if(fastqPresent) {
				runBaitTask();
				System.out.println("Completed First Baiting");
				runSecondBaitTask();
				System.out.println("Completed Second Baiting");
				runAssembleTask();
				System.out.println("Completed Assembly");
			}
			if(!System.getProperty("os.name").contains("Windows")) {
				CommandMethods.makeExecutable(BLASTLocation);
			}
			CommandMethods.makeBlastDB(outDir.resolve("primer_tmp.fasta"), BLASTLocation);
			System.out.println("Completed Database Creation");
			// If files were fastq, need to use the assembly file instead of raw files
			runBLASTTask task = new runBLASTTask();
			Thread t = new Thread(task);
			t.setDaemon(true);
			t.start();
			try {
				t.join();
			}catch(InterruptedException e) {
				e.printStackTrace();
			}
			System.out.println("Completed BLAST");
			CommandMethods.addContigDict(sampleDict);
			CommandMethods.parseBlastOutput(consolidatedDir, detailedDir, primerDict, mismatches, sampleDict);
			System.out.println("Parsed BLAST output");
			CommandMethods.makeConsolidatedReport(consolidatedDir, sep, sampleDict, primerDict);
			System.out.println("Created Consolidated Report");
			CommandMethods.makeQALog(outDir.resolve("QAlog.txt"), Dispatcher.version, outDir, inputFile, primerFile, BBToolsLocation, BLASTLocation);

			long endTime = System.nanoTime();

			System.out.println("Done in " + Long.toString((endTime - startTime) / 1000000000) + " seconds");
		}
	}

	//Method to make a thread to run the FirstBaitTask to prevent UI from hanging
	public void runBaitTask() {
		BaitTask task = new BaitTask();
		Thread t = new Thread(task);
		t.setDaemon(true);
		t.start();
		try {
			t.join();
		}catch(InterruptedException e) {
			e.printStackTrace();
		}
	}

	//Method to make a thread to run the SecondBaitTask to prevent UI from hanging
	public void runSecondBaitTask() {
		SecondBaitTask task = new SecondBaitTask();
		Thread t = new Thread(task);
		t.setDaemon(true);
		t.start();
		try {
			t.join();
		}catch(InterruptedException e) {
			e.printStackTrace();
		}
	}

	//Method to make a thread to run the AssemblyTask to prevent UI from hanging
	public void runAssembleTask() {
		AssembleTask task = new AssembleTask();
		Thread t = new Thread(task);
		t.setDaemon(true);
		t.start();
		try {
			t.join();
		}catch(InterruptedException e) {
			e.printStackTrace();
		}
	}

	public class runBLASTTask implements Runnable {

		public runBLASTTask() {
		}

		public void run() {
			ThreadPoolExecutor mainPool = new ThreadPoolExecutor(threads, Integer.MAX_VALUE, Long.MAX_VALUE, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {
					BlastTask task = new BlastTask(outDir.resolve("primer_tmp.fasta"), sampleDict.get(key).getAssemblyFile(),
							detailedDir, sep, BLASTLocation, evalue);
					mainPool.submit(task);
				}else {
					for(Path file : sampleDict.get(key).getFiles()) {
						BlastTask task = new BlastTask(outDir.resolve("primer_tmp.fasta"), file,
								detailedDir, sep, BLASTLocation, evalue);
						mainPool.submit(task);
					}
				}
			}
			try {
				mainPool.shutdown();
				mainPool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
			}catch(InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	// Bait FastQ reads from input files using BBDuk and the primer file as the target
	public class BaitTask implements Runnable {

		public BaitTask() {
		}

		public void run() {

			// Need to make sure that whatever k-value is being used is no longer than the shortest primer length
			int klength = Integer.MAX_VALUE;
			for(String key : primerDict.keySet()) {
				if(primerDict.get(key).length() < klength) {
					klength = primerDict.get(key).length();
				}
			}

			String ref = outDir.resolve("primer_tmp.fasta").toString();

			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {
					Sample currentSample = sampleDict.get(key);
					Path sampleDir = detailedDir.resolve(currentSample.getName());
					try { Files.createDirectories(sampleDir); } catch (IOException e) { throw new IllegalStateException("Unable to create sample directory: " + sampleDir, e); }
					String[] fullProcessCall;
					if(currentSample.getFiles().size() == 2) {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref, "k=" + klength,
								"in1=" + currentSample.getFiles().getFirst(), "in2=" + currentSample.getFiles().get(1), "hdist=" + mismatches,
								"threads=" + threads, "overwrite=t", "interleaved=t", "outm=" + sampleDir.resolve(currentSample.getName() + "_targetMatches.fastq.gz").toString()};
					}else {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref, "k=" + klength,
								"in=" + currentSample.getFiles().getFirst(), "hdist=" + mismatches, "threads=" + threads, "overwrite=t", "interleaved=t",
								"outm=" + sampleDir.resolve(currentSample.getName() + "_targetMatches.fastq.gz").toString()};
					}
					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation.toFile()).start();
						// To write stdout to terminal (Debug)
//						ProcessBuilder pb = new ProcessBuilder(fullProcessCall);
//						pb.directory(BBToolsLocation);
//						pb.inheritIO();
//						Process p = pb.start();
						try {
							p.waitFor();
						}catch(InterruptedException e) {
							e.printStackTrace();
						}
					}catch(IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	// Bait more FastQ read nearby the originally baited reads using the originally baited reads as bait themselves
	// If USERS find issues with memory overflow, can use qhdist instead of hdist. Sacrifices speed for memory by
	// Conducting mutations on query instead of reference? Dramatically reduces memory usage.
	public class SecondBaitTask implements Runnable {

		public SecondBaitTask() {
		}

		public void run() {
			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {
					Sample currentSample = sampleDict.get(key);
					Path sampleDir = detailedDir.resolve(currentSample.getName());
					String ref = sampleDir.resolve(currentSample.getName() + "_targetMatches.fastq.gz").toString();
					String[] fullProcessCall;
					if(currentSample.getFiles().size() == 2) {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref,
								"in1=" + currentSample.getFiles().getFirst(), "in2=" + currentSample.getFiles().get(1), "hdist=" + mismatches,
								"threads=" + threads, "overwrite=t", "interleaved=t", "outm=" + sampleDir.resolve(currentSample.getName() + "_doubleTargetMatches.fastq.gz").toString()};
					}else {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref,
								"in=" + currentSample.getFiles().getFirst(), "hdist=" + mismatches, "threads=" + threads, "overwrite=t", "interleaved=t",
								"outm=" + sampleDir.resolve(currentSample.getName() + "_doubleTargetMatches.fastq.gz").toString()};
					}
					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation.toFile()).start();
						try {
							p.waitFor();
						}catch(InterruptedException e) {
							e.printStackTrace();
						}
					}catch(IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	// Assemble reads from both rounds of baiting to attempt to get long enough contigs to ensure as many primer hits are contained on the same contigs as possible
	public class AssembleTask implements Runnable {

		public AssembleTask() {
		}

		public void run() {
			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {

					Sample currentSample = sampleDict.get(key);
					Path sampleDir = detailedDir.resolve(currentSample.getName());

					String in = sampleDir.resolve(currentSample.getName() + "_doubleTargetMatches.fastq.gz").toString();
					String out = sampleDir.resolve(currentSample.getName() + "_assembly.fasta").toString();

					// Make sure that the sample contains a reference to its own assembly file
					currentSample.setAssemblyFile(Path.of(out));
					String[] fullProcessCall = {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "assemble.Tadpole",
							"in=" + in, "out=" + out, "overwrite=t", "threads=" + threads};

					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation.toFile()).start();
						try {
							p.waitFor();
						}catch(InterruptedException e) {
							e.printStackTrace();
						}
					}catch(IOException e) {
						e.printStackTrace();
					}

				}
			}
		}
	}

	// Run Blast on the provided primers and query, calls addHeaderToTSV on the resulting .tsv file
	public static class BlastTask implements Runnable {

		private final Path primers;
		private final Path query;
		private final Path detailedDir;
		private final String sep;
		private final Path BLASTLocation;
		private final double evalue;


		public BlastTask(Path primers, Path query, Path detailedDir, String sep, Path BLASTLocation, double evalue) {
			this.primers = primers;
			this.query = query;
			this.detailedDir = detailedDir;
			this.sep = sep;
			this.BLASTLocation = BLASTLocation;
			this.evalue = evalue;
		}

		public void run() {

			String name = query.getFileName().toString().split("_assembly\\.fasta")[0];
			name = name.split("\\.fasta")[0];
			name = name.split("\\.fna")[0];
			name = name.split("\\.ffn")[0];
			Path blastOutput = detailedDir.resolve(name);
			try { Files.createDirectories(blastOutput); } catch (IOException e) { throw new IllegalStateException("Unable to create BLAST output directory: " + blastOutput, e); }
			Path blastTSV = blastOutput.resolve(name + ".tsv");
			String[] windowsFullProcessCall = {AppPaths.executable(BLASTLocation, "blastn").toString(), "-task", "blastn-short", "-query",
					query.toString(), "-db", primers.toString(), "-evalue", Double.toString(evalue), "-num_alignments", "1000000", "-num_threads", "1", "-outfmt",
					"6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
					"-out", blastTSV.toString()};
			String[] linuxFullProcessCall = {AppPaths.executable(BLASTLocation, "blastn").toString(), "-task", "blastn-short", "-query",
					query.toString(), "-db", primers.toString(), "-evalue", Double.toString(evalue), "-num_alignments", "1000000", "-num_threads", "1", "-outfmt",
					"6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
					"-out", blastTSV.toString()};
			try {
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
			CommandMethods.addHeaderToTSV(blastTSV);
		}
	}
}
