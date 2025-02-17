package ca.canada.inspection.commandpcr;

import ca.canada.inspection.insilicopcr.Find;
import ca.canada.inspection.insilicopcr.Sample;
import ca.canada.inspection.dispatchpcr.Dispatcher;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;


public class CommandMain {
	
	public static String sep = File.separator;

    private File inputFile = null, outDir = null, primerFile = null;
	private int threads = Runtime.getRuntime().availableProcessors();
	private int memJava = 4;
    private double evalue = Double.parseDouble("1e10");
	private File detailedDir, consolidatedDir;
	private File BBToolsLocation, BLASTLocation, JavaLocation;
	private String javaCall;
	private int mismatches = 0;
    private HashMap<String, String> primerDict = new HashMap<String, String>();
	private HashMap<String, Sample> sampleDict = new HashMap<String, Sample>();
	private boolean fastqPresent = false;

    public CommandMain(File inputFile, File outDir, File primerFile, int threads, int mismatches, double evalue) {
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
		detailedDir = new File(outDir.getAbsolutePath() + sep + "detailed_report");
		detailedDir.mkdirs();
		consolidatedDir = new File(outDir.getAbsolutePath() + sep + "consolidated_report");
		consolidatedDir.mkdirs();
	}
	
	// Find dependencies
	public void findDependencies() {
		File jarDir = new File(Dispatcher.class.getProtectionDomain().getCodeSource().getLocation().getPath());
		String codeLocation = jarDir.getParent(); // to get the parent dir name
//		String codeParent = codeLocation;
		String codeParent = "/media/marco/marco/insilicoPCR_v0.5";  // debug
//		System.out.println(codeParent);  // Print location
//		try{
//			codeParent = (new File(codeLocation)).getCanonicalPath();
//		}catch(IOException e) {
//			e.printStackTrace();
//		}
		Path dir = Paths.get(codeParent);
		Find.Finder finder = new Find.Finder("**bbmap", dir);
		for(Path path: finder.run()) {
			File directory = path.toFile();
			for(File file : Objects.requireNonNull(directory.listFiles())) {
                if (file.getName().contains("tadpole.sh")) {
                    BBToolsLocation = path.toFile();
                    break;
                }
			}
		}
//		System.out.println(BBToolsLocation);  // Print location
		
		Find.Finder finder2;
		if(System.getProperties().getProperty("os.name").contains("Windows")) {
			finder2 = new Find.Finder("**makeblastdb.exe", dir);
		}else {
			finder2 = new Find.Finder("**makeblastdb", dir);
		}
		for(Path path : finder2.run()) {
			File blastdbpath = path.toFile();
			BLASTLocation = blastdbpath.getParentFile();
		}
		if(BBToolsLocation == null || BLASTLocation == null) {
			System.out.println("BBToolsLocation or BLASTLocation is null");
		}
		
		Find.Finder finder3;
		if(System.getProperties().getProperty("os.name").contains("Windows")) {
			finder3 = new Find.Finder("**windows/jdk-21.0.3", dir);
		}else {
			finder3 = new Find.Finder("**linux/jdk-21.0.3", dir);
		}
		for(Path path : finder3.run()) {
			File javapath = path.toFile();
			if(javapath.isDirectory()) {
				for(File item : Objects.requireNonNull(javapath.listFiles())) {
					if(item.isDirectory()) {
						for(File item2 : Objects.requireNonNull(item.listFiles())) {
							if(System.getProperties().getProperty("os.name").contains("Windows")) {
								if(item2.getName().equals("java.exe")) {
									JavaLocation = item.getAbsoluteFile();
								}
							}else {
								if(item2.getName().equals("java")) {
									JavaLocation = item.getAbsoluteFile();
								}
							}
						}
					}
				}
			}
		}
		if(System.getProperties().getProperty("os.name").contains("Windows")) {
			javaCall = JavaLocation.getAbsolutePath() + sep + "java.exe";
		}else {
			javaCall = JavaLocation.getAbsolutePath() + sep + "java";
		}
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
			CommandMethods.makeBlastDB(new File(outDir.getAbsolutePath() + sep + "primer_tmp.fasta"), BLASTLocation);
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
			CommandMethods.makeQALog(new File(outDir.getAbsolutePath() + sep + "QAlog.txt"), Dispatcher.version, outDir, inputFile, primerFile, BBToolsLocation, BLASTLocation);
			
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
					BlastTask task = new BlastTask(outDir.getAbsolutePath() + sep + "primer_tmp.fasta", sampleDict.get(key).getAssemblyFile(),
                            detailedDir, sep, BLASTLocation, evalue);
					mainPool.submit(task);
				}else {
					for(String file : sampleDict.get(key).getFiles()) {
						BlastTask task = new BlastTask(outDir.getAbsolutePath() + sep + "primer_tmp.fasta", file,
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
			
			String ref = outDir.getAbsolutePath() + sep + "primer_tmp.fasta";
			
			for(String key : sampleDict.keySet()) {
				if(sampleDict.get(key).getFileType().equals("fastq")) {
					Sample currentSample = sampleDict.get(key);
					File sampleDir = new File(detailedDir.getAbsolutePath() + sep + currentSample.getName());
					sampleDir.mkdirs();
					String[] fullProcessCall;
					if(currentSample.getFiles().size() == 2) {
                        fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref, "k=" + klength,
								"in1=" + currentSample.getFiles().getFirst(), "in2=" + currentSample.getFiles().get(1), "hdist=" + mismatches,
								"threads=" + threads, "overwrite=t", "interleaved=t", "outm=" + sampleDir.getAbsolutePath() + sep + currentSample.getName() +
								"_targetMatches.fastq.gz"};
					}else {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref, "k=" + klength,
								"in=" + currentSample.getFiles().getFirst(), "hdist=" + mismatches, "threads=" + threads, "overwrite=t", "interleaved=t",
								"outm=" + sampleDir.getAbsolutePath() + sep + currentSample.getName() + "_targetMatches.fastq.gz"};
					}
					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation).start();
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
					File sampleDir = new File(detailedDir.getAbsolutePath() + sep + currentSample.getName());
					String ref = sampleDir.getAbsolutePath() + sep + currentSample.getName() + "_targetMatches.fastq.gz";
					String[] fullProcessCall;
					if(currentSample.getFiles().size() == 2) {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref,
								"in1=" + currentSample.getFiles().getFirst(), "in2=" + currentSample.getFiles().get(1), "hdist=" + mismatches,
								"threads=" + threads, "overwrite=t", "interleaved=t", "outm=" + sampleDir.getAbsolutePath() + sep + currentSample.getName() +
								"_doubleTargetMatches.fastq.gz"};
					}else {
						fullProcessCall = new String[] {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "jgi.BBDuk", "ref=" + ref,
								"in=" + currentSample.getFiles().getFirst(), "hdist=" + mismatches, "threads=" + threads, "overwrite=t", "interleaved=t",
								"outm=" + sampleDir.getAbsolutePath() + sep + currentSample.getName() + "_doubleTargetMatches.fastq.gz"};
					}
					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation).start();
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
					File sampleDir = new File(detailedDir.getAbsolutePath() + sep + currentSample.getName());
					
					String in = sampleDir.getAbsolutePath() + sep + currentSample.getName() + "_doubleTargetMatches.fastq.gz";
					String out = sampleDir.getAbsolutePath() + sep + currentSample.getName() + "_assembly.fasta";
					
					// Make sure that the sample contains a reference to its own assembly file
					currentSample.setAssemblyFile(out);
					String[] fullProcessCall = {javaCall, "-ea", String.format("-Xmx%sg", memJava), "-cp", "./current", "assemble.Tadpole",
							"in=" + in, "out=" + out, "overwrite=t", "threads=" + threads};
				
					try {
						Process p = new ProcessBuilder(fullProcessCall).directory(BBToolsLocation).start();
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
		
		private final String primers;
		private final String query;
		private final File detailedDir;
		private final String sep;
		private final File BLASTLocation;
		private final double evalue;


		public BlastTask(String primers, String query, File detailedDir, String sep, File BLASTLocation, double evalue) {
			this.primers = primers;
			this.query = query;
			this.detailedDir = detailedDir;
			this.sep = sep;
			this.BLASTLocation = BLASTLocation;
            this.evalue = evalue;
        }
		
		public void run() {
			
			File file = new File(query);
			String name = file.getName().split("_assembly\\.fasta")[0];
			name = name.split("\\.fasta")[0];
			name = name.split("\\.fna")[0];
			name = name.split("\\.ffn")[0];
			File blastOutput = new File(detailedDir.getAbsolutePath() + sep + name);
			blastOutput.mkdirs();
			File blastTSV = new File(blastOutput.getAbsolutePath() + sep + name + ".tsv");
			String[] windowsFullProcessCall = {BLASTLocation.getAbsolutePath() + sep + "ca/canada/inspection/insilicopcr/blastn.exe", "-task", "blastn-short", "-query",
					query, "-db", primers, "-evalue", Double.toString(evalue), "-num_alignments", "1000000", "-num_threads", "1", "-outfmt",
					"6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
					"-out", blastTSV.getAbsolutePath()};
			String[] linuxFullProcessCall = {BLASTLocation.getAbsolutePath() + sep + "blastn", "-task", "blastn-short", "-query",
					query, "-db", primers, "-evalue", Double.toString(evalue), "-num_alignments", "1000000", "-num_threads", "1", "-outfmt",
					"6 qseqid sseqid positive mismatch gaps evalue bitscore slen length qstart qend qseq sstart send sseq",
					"-out", blastTSV.getAbsolutePath()};
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
