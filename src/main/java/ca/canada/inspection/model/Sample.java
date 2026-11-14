package ca.canada.inspection.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One sample of a run and what the run learns about it. The steps fill it in as they go: the
 * input check its files and type, FASTQ mode its assembly, the BLAST step its contigs and its
 * accepted hits. Its accessors return copies; it changes only through its own methods.
 */
public class Sample {

	/** What a sample's files hold. */
	public enum FileType {
		/** Assemblies or other sequences: searched as they are. */
		FASTA,
		/** Sequencing reads: baited and assembled first. */
		FASTQ,
		/** Reads already baited, searched one by one (a step of FASTQ mode, not an input). */
		READS,
		/** Neither by its name. */
		UNKNOWN
	}

	private final ArrayList<Path> sampleFiles = new ArrayList<>();
	private final HashMap<String, ArrayList<BlastResult>> blastResults = new HashMap<>();
	private final HashMap<String, String> contigDict = new HashMap<>();
	private String name;
	private FileType fileType;
	private Path assemblyFile;
	/** Results worked out from the hits and contigs (the sample's products), until either changes. */
	private final HashMap<Object, Object> derived = new HashMap<>();

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	/** The sample's files (one assembly, or the reads of one library), in name order; a copy. */
	public List<Path> files() { return List.copyOf(sampleFiles); }

	public void setFile(Path file) {
		sampleFiles.clear();
		sampleFiles.add(file);
	}

	public void setFiles(List<Path> files) {
		sampleFiles.clear();
		sampleFiles.addAll(files);
		sortFiles();
	}

	public void addFile(Path file) {
		sampleFiles.add(file);
		sortFiles();
	}

	/** In name order, whatever the case: s_r1 before s_R2, so the first file of a pair is read 1. */
	private void sortFiles() {
		sampleFiles.sort(Comparator.comparing((Path file) -> file.toString().toLowerCase(Locale.ROOT))
				.thenComparing(Path::toString));
	}

	public FileType getFileType() { return fileType; }
	public void setFileType(FileType fileType) { this.fileType = fileType; }

	/**
	 * The accepted hits of each oligo, by oligo ID; a copy, in no particular order (the report
	 * lists products in the primer file's order).
	 */
	public synchronized Map<String, List<BlastResult>> blastResults() {
		HashMap<String, List<BlastResult>> copy = new HashMap<>();
		blastResults.forEach((key, value) -> copy.put(key, List.copyOf(value)));
		return Map.copyOf(copy);
	}

	public synchronized void addBlastResult(String key, BlastResult result) {
		blastResults.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
		derived.clear();
	}

	public Path getAssemblyFile() { return assemblyFile; }
	public void setAssemblyFile(Path assemblyFile) { this.assemblyFile = assemblyFile; }

	public synchronized void addContig(String acc, String desc) {
		contigDict.put(acc, desc);
		derived.clear();
	}

	/**
	 * What {@code compute} gives for {@code key}, worked out once while the sample's hits and
	 * contigs stay as they are: several steps of a run ask for the same sample's products.
	 */
	@SuppressWarnings("unchecked")
	public synchronized <T> T derived(Object key, Supplier<T> compute) {
		Object value = derived.get(key);
		if (value == null) {
			value = compute.get();
			derived.put(key, value);
		}
		return (T) value;
	}

	/** Contig descriptions (the FASTA header after the ID), by contig ID; a copy. */
	public synchronized Map<String, String> contigs() { return Map.copyOf(contigDict); }

	public boolean isFastq() { return fileType == FileType.FASTQ; }
}
