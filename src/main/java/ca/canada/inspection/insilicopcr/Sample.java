package ca.canada.inspection.insilicopcr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Mutable per-sample state collected during one PCR run. */
public class Sample {

	private final ArrayList<Path> sampleFiles = new ArrayList<>();
	private final HashMap<String, ArrayList<BlastResult>> blastResults = new HashMap<>();
	private final HashMap<String, String> contigDict = new HashMap<>();
	private String name;
	private String fileType;
	private Path assemblyFile;

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	/** Compatibility accessor for legacy GUI/helper code. Prefer files(). */
	public ArrayList<Path> getFiles() { return sampleFiles; }
	public List<Path> files() { return List.copyOf(sampleFiles); }

	public void setFile(Path file) {
		sampleFiles.clear();
		sampleFiles.add(file);
	}

	public void setFile(String file) { setFile(Path.of(file)); }

	public void setFiles(List<Path> files) {
		sampleFiles.clear();
		sampleFiles.addAll(files);
		sortFiles();
	}

	public void addFile(Path file) {
		sampleFiles.add(file);
		sortFiles();
	}

	private void sortFiles() { sampleFiles.sort(Comparator.comparing(Path::toString)); }

	public String getFileType() { return fileType; }
	public void setFileType(String fileType) { this.fileType = fileType; }

	/** Compatibility accessor for legacy GUI/helper code. Prefer blastResults(). */
	public HashMap<String, ArrayList<BlastResult>> getBlastResults() { return blastResults; }
	public Map<String, List<BlastResult>> blastResults() {
		HashMap<String, List<BlastResult>> copy = new HashMap<>();
		blastResults.forEach((key, value) -> copy.put(key, List.copyOf(value)));
		return Map.copyOf(copy);
	}

	public void addBlastResult(String key, BlastResult result) {
		blastResults.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
	}

	/** Compatibility alias for older report parser code. */
	public void addNewBlastResult(String key, BlastResult result) { addBlastResult(key, result); }

	public Path getAssemblyFile() { return assemblyFile; }
	public void setAssemblyFile(Path assemblyFile) { this.assemblyFile = assemblyFile; }
	public void setAssemblyFile(String assemblyFile) { setAssemblyFile(Path.of(assemblyFile)); }

	public void addContig(String acc, String desc) { contigDict.put(acc, desc); }

	/** Compatibility accessor for legacy GUI/helper code. Prefer contigs(). */
	public HashMap<String, String> getContigDict() { return contigDict; }
	public Map<String, String> contigs() { return Map.copyOf(contigDict); }

	public boolean isFastq() { return "fastq".equals(fileType); }
}
