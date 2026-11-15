package ca.canada.inspection.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One sample of a run and what the run learns about it. Its name is fixed when it is made; the
 * steps fill in the rest as they go: the input check its files and type, FASTQ mode its
 * assembly, the BLAST step its contigs and its accepted hits ({@link Hits}). Its accessors
 * return copies; it changes only through its own methods.
 */
public class Sample {

	/** What a sample's files hold. */
	public enum FileType {
		/** Assemblies or other sequences: searched as they are. */
		FASTA,
		/** Sequencing reads: baited and assembled first. */
		FASTQ,
		/** Neither by its name. */
		UNKNOWN
	}

	private final ArrayList<Path> sampleFiles = new ArrayList<>();
	private final Hits hits = new Hits();
	private final String name;
	private FileType fileType;
	private Path assemblyFile;

	/** A sample of this name, which names its files in the run's output folder and its rows in the reports. */
	public Sample(String name) {
		this.name = Objects.requireNonNull(name, "name");
	}

	public String getName() { return name; }

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

	/** The accepted hits on the sample's sequences, and their descriptions. */
	public Hits hits() { return hits; }

	/**
	 * The accepted hits of each oligo, by oligo ID; a copy, in no particular order (the report
	 * lists products in the primer file's order).
	 */
	public Map<String, List<BlastResult>> blastResults() { return hits.byOligo(); }

	public void addBlastResult(String key, BlastResult result) { hits.add(key, result); }

	public Path getAssemblyFile() { return assemblyFile; }
	public void setAssemblyFile(Path assemblyFile) { this.assemblyFile = assemblyFile; }

	public void addContig(String acc, String desc) { hits.describe(acc, desc); }

	/** Contig descriptions (the FASTA header after the ID), by contig ID; a copy. */
	public Map<String, String> contigs() { return hits.descriptions(); }

	public boolean isFastq() { return fileType == FileType.FASTQ; }

	/**
	 * The FASTA files BLAST searches for this sample: its mini-assembly for FASTQ input (none
	 * before it is assembled), its own file otherwise.
	 */
	public List<Path> searchedFasta() {
		if (isFastq()) {
			return assemblyFile == null ? List.of() : List.of(assemblyFile);
		}
		return files();
	}

	/** The accepted hits of {@code oligoIds}, in that order. */
	public List<BlastResult> hitsOf(Collection<String> oligoIds) { return hits.of(oligoIds); }
}
