package ca.canada.inspection.insilicopcr;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

public class Sample {

	private final ArrayList<Path> sampleFiles = new ArrayList<>();
	private String name;
	private String fileType;
	private final HashMap<String, ArrayList<BlastResult>> blastResults = new HashMap<>();
	private Path assemblyFile;
	private final HashMap<String, String> contigDict = new HashMap<>();

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public ArrayList<Path> getFiles() {
		return sampleFiles;
	}

	public void setFile(Path file) {
		sampleFiles.clear();
		sampleFiles.add(file);
	}

	public void setFile(String file) {
		setFile(Path.of(file));
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

	public void addFile(String fileName) {
		addFile(Path.of(fileName));
	}

	private void sortFiles() {
		sampleFiles.sort(Comparator.comparing(Path::toString));
	}

	public String getFileType() {
		return fileType;
	}

	public void setFileType(String fileType) {
		this.fileType = fileType;
	}

	public HashMap<String, ArrayList<BlastResult>> getBlastResults() {
		return blastResults;
	}

	public void addBlastResult(String key, BlastResult results) {
		blastResults.get(key).add(results);
	}

	public void addNewBlastResult(String key, BlastResult results) {
		ArrayList<BlastResult> temp = new ArrayList<>();
		temp.add(results);
		blastResults.put(key, temp);
	}

	public Path getAssemblyFile() {
		return assemblyFile;
	}

	public void setAssemblyFile(Path assemblyFile) {
		this.assemblyFile = assemblyFile;
	}

	public void setAssemblyFile(String assemblyFile) {
		setAssemblyFile(Path.of(assemblyFile));
	}

	public void addContig(String acc, String desc) {
		contigDict.put(acc, desc);
	}

	public HashMap<String, String> getContigDict() {
		return contigDict;
	}
}
