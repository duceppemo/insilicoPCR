package ca.canada.inspection.model;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One sample of a run and what the run learns about it. Its name is fixed when it is made; the
 * steps fill in the rest as they go: the input check its files and type, the BLAST step its
 * contigs and its accepted hits ({@link Hits}). Where its files are in the output folder (the
 * mini-assembly of reads among them) is the pipeline's {@code SampleFiles}. Its accessors
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

	/** A sample of this name, which names its files in the run's output folder and its rows in the reports. */
	public Sample(String name) {
		this.name = Objects.requireNonNull(name, "name");
	}

	public String getName() { return name; }

	/** The sample's files (one assembly, or the reads of one library), in name order; a copy. */
	public List<Path> files() { return List.copyOf(sampleFiles); }

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

	public boolean isFastq() { return fileType == FileType.FASTQ; }
}
