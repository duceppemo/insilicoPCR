package ca.canada.inspection.pipeline;

/**
 * Heap of the BBTools child processes. The children of a run share half of this
 * program's heap: one child alone gets all of it (up to {@value #MAX_CHILD_GIB} GiB),
 * and samples processed at once each get their share.
 */
public final class ChildJavaMemory {
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;
    private static final int DEFAULT_BUDGET_GIB = 4;
    /** More heap than this does not help one BBTools process. */
    static final int MAX_CHILD_GIB = 64;
    /**
     * Fewer samples run at once rather than any getting less than this: BBTools' own
     * default heap, which the baiting and mini-assembly of a typical sample fit in.
     */
    static final int MIN_SHARED_CHILD_GIB = 4;

    private ChildJavaMemory() {
    }

    /** Heap, in GiB, that the child processes running at once may use between them. */
    public static int budgetGiB() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory <= 0 || maxMemory == Long.MAX_VALUE) {
            return DEFAULT_BUDGET_GIB;
        }
        return Math.toIntExact(Math.clamp(maxMemory / 2 / BYTES_PER_GIB, 1L, Integer.MAX_VALUE));
    }

    /** Heap of a child that runs alone. */
    static int forOneChild(int budgetGiB) {
        return Math.clamp(budgetGiB, 1, MAX_CHILD_GIB);
    }

    /**
     * How to run {@code items} children, each on its own sample, with {@code threads}
     * threads and {@code budgetGiB} of heap in all.
     */
    static Share share(int budgetGiB, int threads, int items) {
        int atOnce = Math.clamp(Math.min(threads, items), 1, Math.max(1, budgetGiB / MIN_SHARED_CHILD_GIB));
        return new Share(atOnce, Math.max(1, threads / atOnce), forOneChild(budgetGiB / atOnce));
    }

    /**
     * @param atOnce    children running at the same time
     * @param threads   threads of each child
     * @param memoryGiB heap of each child
     */
    record Share(int atOnce, int threads, int memoryGiB) {
    }
}
