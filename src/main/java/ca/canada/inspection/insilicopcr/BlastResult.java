package ca.canada.inspection.insilicopcr;

/**
 * Immutable BLAST hit of a primer (subject) against a sample sequence (query).
 *
 * <p>Query coordinates ({@code qstart}, {@code qend}) are always ascending.
 * Subject coordinates are descending ({@code sstart > send}) when the primer
 * matches the reverse strand of the query. {@code primerSeq} is the full primer
 * sequence, used to assess the unaligned ends BLAST trimmed off.</p>
 *
 * <p>{@code fivePrimeUnpaired} is how many of the unaligned 5' bases do not pair
 * with the template, once they were compared with it ({@code -1}: not compared;
 * see {@code ca.canada.inspection.pcr.TrimmedEnds}).</p>
 */
public record BlastResult(
		String name,
		String queryID,
		String subjectID,
		int mismatch,
		int gaps,
		int qstart,
		int qend,
		int sstart,
		int send,
		int slen,
		int length,
		String qseq,
		String sseq,
		String primerSeq,
		int fivePrimeUnpaired
) {
	/** A hit whose unaligned 5' bases were not compared with the template. */
	public BlastResult(String name, String queryID, String subjectID, int mismatch, int gaps,
					   int qstart, int qend, int sstart, int send, int slen, int length,
					   String qseq, String sseq, String primerSeq) {
		this(name, queryID, subjectID, mismatch, gaps, qstart, qend, sstart, send, slen, length,
				qseq, sseq, primerSeq, -1);
	}

	public String getName() { return name; }
	public String getQueryID() { return queryID; }
	public String getSubjectID() { return subjectID; }
	public int getMismatch() { return mismatch; }
	public int getStart() { return qstart; }
	public int getEnd() { return qend; }
	public int getLength() { return length; }
	public String getSeq() { return sseq; }

	/**
	 * True when the primer matches the forward strand of the query, which means
	 * polymerase extension proceeds toward higher query coordinates.
	 */
	public boolean isPlusStrand() {
		return sstart <= send;
	}

	/**
	 * Number of primer bases missing from the alignment on the primer's 3' side.
	 * BLAST trims terminal mismatches from local alignments, so a mismatch on the
	 * very 3' base of a primer usually shows up here rather than as an aligned
	 * mismatch.
	 */
	public int threePrimeUncovered() {
		return slen - Math.max(sstart, send);
	}

	/** Number of primer bases missing from the alignment on the primer's 5' side. */
	public int fivePrimeUncovered() {
		return Math.min(sstart, send) - 1;
	}

	/**
	 * Number of 5' primer bases that do not pair with the template: the unaligned
	 * 5' bases that differ from it, or all of them when they were not compared.
	 * Bases beyond the end of the template count as unpaired.
	 */
	public int fivePrimeUnpairedBases() {
		return fivePrimeUnpaired >= 0 ? fivePrimeUnpaired : fivePrimeUncovered();
	}

	/**
	 * Counts imperfections (mismatches, indel columns, and unaligned primer
	 * bases) within the last {@code window} bases of the primer's 3' end.
	 */
	public int threePrimeMismatches(int window) {
		return scanThreePrimeWindow(window, false);
	}

	/**
	 * Weighted penalty of the imperfections within the last {@code window} bases
	 * of the primer's 3' end. Events closer to the 3' terminus weigh more, an
	 * event that breaks a G/C pair near the terminus weighs extra, and indels
	 * weigh more than substitutions:
	 *
	 * <pre>
	 * distance from 3' terminus:  0 -> 3   1-2 -> 2   3+ -> 1
	 * primer base is G or C at distance 0-2: +1
	 * insertion/deletion: +1
	 * </pre>
	 *
	 * <p>A perfect 3' window scores 0. A single mismatch on the terminal base of
	 * an A/T-ending primer scores 3; on a G/C-ending primer it scores 4.</p>
	 */
	public int threePrimePenalty(int window) {
		return scanThreePrimeWindow(window, true);
	}

	private int scanThreePrimeWindow(int window, boolean weighted) {
		if (window <= 0) {
			return 0;
		}
		int total = 0;

		// Primer bases BLAST left out of the alignment at the 3' side: the local
		// alignment stopped because they stop pairing, so treat them as mismatches.
		int uncovered = Math.min(threePrimeUncovered(), window);
		for (int i = 0; i < uncovered; i++) {
			total += weighted ? substitutionPenalty(i, primerBaseAtDistance(i)) : 1;
		}

		if (qseq == null || sseq == null || qseq.length() != sseq.length()) {
			return total;
		}

		int direction = isPlusStrand() ? 1 : -1;
		int subjectPos = sstart;
		for (int i = 0; i < sseq.length(); i++) {
			char subjectBase = Character.toUpperCase(sseq.charAt(i));
			char queryBase = Character.toUpperCase(qseq.charAt(i));
			if (subjectBase == '-') {
				// Insertion in the query, between primer positions: attribute it to
				// the neighbouring primer position closest to the 3' end.
				int distance = direction > 0 ? slen - subjectPos : slen - subjectPos - 1;
				if (distance >= 0 && distance < window) {
					total += weighted ? substitutionPenalty(distance, 'N') + 1 : 1;
				}
				continue;
			}
			int distance = slen - subjectPos;
			if (distance >= 0 && distance < window) {
				if (queryBase == '-') {
					total += weighted ? substitutionPenalty(distance, subjectBase) + 1 : 1;
				} else if (queryBase != subjectBase) {
					total += weighted ? substitutionPenalty(distance, subjectBase) : 1;
				}
			}
			subjectPos += direction;
		}
		return total;
	}

	private static int substitutionPenalty(int distanceFromThreePrime, char primerBase) {
		int positional = distanceFromThreePrime == 0 ? 3 : distanceFromThreePrime <= 2 ? 2 : 1;
		boolean strongPair = primerBase == 'G' || primerBase == 'C';
		return positional + (strongPair && distanceFromThreePrime <= 2 ? 1 : 0);
	}

	private char primerBaseAtDistance(int distanceFromThreePrime) {
		if (primerSeq == null || primerSeq.isEmpty()) {
			return 'N';
		}
		int index = primerSeq.length() - 1 - distanceFromThreePrime;
		return index >= 0 ? Character.toUpperCase(primerSeq.charAt(index)) : 'N';
	}
}
