package ca.canada.inspection.model;

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
		int fivePrimeUnpaired,
		int fivePrimeFree
) {
	/** A hit whose unaligned 5' bases were not compared with the template. */
	public BlastResult(String queryID, String subjectID, int mismatch, int gaps,
					   int qstart, int qend, int sstart, int send, int slen, int length,
					   String qseq, String sseq, String primerSeq) {
		this(queryID, subjectID, mismatch, gaps, qstart, qend, sstart, send, slen, length,
				qseq, sseq, primerSeq, -1);
	}

	/** A hit whose aligned 5' mismatches all count ({@link #withFivePrimeFree}). */
	public BlastResult(String queryID, String subjectID, int mismatch, int gaps,
					   int qstart, int qend, int sstart, int send, int slen, int length,
					   String qseq, String sseq, String primerSeq, int fivePrimeUnpaired) {
		this(queryID, subjectID, mismatch, gaps, qstart, qend, sstart, send, slen, length,
				qseq, sseq, primerSeq, fivePrimeUnpaired, 0);
	}

	/**
	 * Mismatches and indels of the alignment at the oligo's positions past its first {@code n} (from its
	 * 5' end): those a primer's budget counts when {@code n} is {@code --5p-truncation}. All of them when
	 * the aligned bases are not known, or do not hold as many as the hit's counts say.
	 */
	public int alignedMismatchesPast(int n) {
		if (n <= 0 || qseq == null || sseq == null || qseq.length() != sseq.length()) {
			return alignedMismatches();
		}
		int direction = isPlusStrand() ? 1 : -1;
		int position = sstart; // the oligo position of the next oligo base in the alignment
		int past = 0;
		int all = 0;
		for (int i = 0; i < sseq.length(); i++) {
			char q = Character.toUpperCase(qseq.charAt(i));
			char s = Character.toUpperCase(sseq.charAt(i));
			boolean oligoBase = s != '-';
			// A gap in the oligo (a template base it lacks) sits between two oligo bases: it is counted at the one
			// on its 3' side, on either strand (the plus strand counted it at the 3' side, the minus at the 5').
			int at = oligoBase ? position : Math.max(position, position - direction);
			if (q != s) {
				all++;
				past += at > n ? 1 : 0;
			}
			if (oligoBase) {
				position += direction;
			}
		}
		return all == alignedMismatches() ? past : alignedMismatches();
	}

	/**
	 * This primer hit with its mismatches and indels in its first {@code n} 5' bases ({@code --5p-truncation})
	 * counted as 5' bases that do not pair, not as mismatches: as if BLAST had left them out of the
	 * alignment, as it does when two are near the 5' end, not one (before 0.15.3 one there was rejected at
	 * {@code -m 0}, two were not).
	 */
	public BlastResult withFivePrimeFree(int n) {
		int free = alignedMismatches() - alignedMismatchesPast(n);
		return free == fivePrimeFree ? this : new BlastResult(queryID, subjectID, mismatch, gaps, qstart, qend,
				sstart, send, slen, length, qseq, sseq, primerSeq, fivePrimeUnpaired, free);
	}

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

	/** Mismatches and indels in the alignment: what a probe's budget ({@code --probe-mismatches}) counts. */
	public int alignedMismatches() {
		return mismatch + gaps;
	}

	/**
	 * Mismatches as a primer's budget ({@code -m}) counts them, and as the report gives them: those
	 * in the alignment past its first {@code --5p-truncation} 5' bases ({@link #withFivePrimeFree}), and
	 * the 3' bases left out of it, which do not pair with the template (or lie beyond its end). The one
	 * place this count is written.
	 */
	public int primerMismatches() {
		return alignedMismatches() - fivePrimeFree + threePrimeUncovered();
	}

	/**
	 * Query position of the full primer's 5'-terminal base, unaligned 5' bases included: where a
	 * product made with it starts (plus strand) or ends (minus strand). May lie beyond the query
	 * (before 1, or past its end) when the primer hangs off it.
	 */
	public int fivePrimeTerminus() {
		return isPlusStrand() ? qstart - fivePrimeUncovered() : qend + fivePrimeUncovered();
	}

	/**
	 * Query position next to the mismatch or indel of the alignment nearest the oligo's 3' end, on that side: from
	 * there to the end of the alignment, the oligo pairs without a break (plus strand: right of it; minus strand: left
	 * of it). {@link Integer#MIN_VALUE} when the alignment has none, or when its bases are not known
	 * ({@link #hasAlignedBases}).
	 */
	public int pairsAgainAfterLastMismatch() {
		if (!hasAlignedBases()) {
			return Integer.MIN_VALUE;
		}
		boolean plus = isPlusStrand();
		// Walked from the oligo's 3' end: right to left on the plus strand, left to right on the minus strand.
		int position = plus ? qend + 1 : qstart - 1;
		for (int k = 0; k < qseq.length(); k++) {
			int i = plus ? qseq.length() - 1 - k : k;
			char q = Character.toUpperCase(qseq.charAt(i));
			char s = Character.toUpperCase(sseq.charAt(i));
			if (q != '-') {
				position += plus ? -1 : 1;
			}
			if (q != s) {
				// A gap in the query lies between two query bases: pairing resumes at the one on the 3' side, the last
				// walked. Otherwise at the base past this one.
				return q == '-' ? position : plus ? position + 1 : position - 1;
			}
		}
		return Integer.MIN_VALUE;
	}

	/** Whether the hit's aligned bases are known, column for column. */
	public boolean hasAlignedBases() {
		return qseq != null && sseq != null && qseq.length() == sseq.length();
	}

	/** Query position of the full primer's leftmost base, unaligned bases included. */
	public int fullStart() {
		return qstart - (isPlusStrand() ? fivePrimeUncovered() : threePrimeUncovered());
	}

	/** Query position of the full primer's rightmost base, unaligned bases included. */
	public int fullEnd() {
		return qend + (isPlusStrand() ? threePrimeUncovered() : fivePrimeUncovered());
	}

	/**
	 * Bases from the full primer's 5' end to the end of the query it extends towards, the primer
	 * included: the longest product the query (a read, a contig) could hold with it.
	 *
	 * @param queryLength length of the query
	 */
	public int toQueryEnd(int queryLength) {
		return isPlusStrand() ? queryLength - fivePrimeTerminus() + 1 : fivePrimeTerminus();
	}

	/** Number of primer bases missing from the alignment on the primer's 5' side. */
	public int fivePrimeUncovered() {
		return Math.min(sstart, send) - 1;
	}

	/**
	 * Number of 5' primer bases that do not pair with the template: the unaligned
	 * 5' bases that differ from it, or all of them when they were not compared, and the
	 * aligned mismatches and indels within {@code --5p-truncation} of the 5' end
	 * ({@link #withFivePrimeFree}). Bases beyond the end of the template count as unpaired.
	 */
	public int fivePrimeUnpairedBases() {
		return (fivePrimeUnpaired >= 0 ? fivePrimeUnpaired : fivePrimeUncovered()) + fivePrimeFree;
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
	 * insertion/deletion: as a mismatch at its place (a template base with no primer base: no G/C point), +1
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
