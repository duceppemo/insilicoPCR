package ca.canada.inspection.insilicopcr;

/** Immutable BLAST hit used by the report parser and consolidated-report writer. */
public record BlastResult(String name, String queryID, String subjectID, int mismatch,
                          int start, int end, int length, String seq) {

	public String getName() { return name; }
	public String getQueryID() { return queryID; }
	public String getSubjectID() { return subjectID; }
	public int getMismatch() { return mismatch; }
	public int getStart() { return start; }
	public int getEnd() { return end; }
	public int getLength() { return length; }
	public String getSeq() { return seq; }
}
