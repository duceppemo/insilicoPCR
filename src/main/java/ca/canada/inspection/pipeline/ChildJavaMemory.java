package ca.canada.inspection.pipeline;

import java.lang.management.ManagementFactory;

/**
 * Heap of the BBTools child processes. The children of a run share the memory this program
 * leaves free on the computer (three quarters of it, the rest for the system and the
 * children's own needs beyond their heap), and at least half of this program's heap: one child
 * alone gets all of it (up to {@value #MAX_CHILD_GIB} GiB), and samples processed at once each
 * get their share.
 */
public final class ChildJavaMemory {
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;
    private static final int DEFAULT_BUDGET_GIB = 4;
    /** Share of the memory this program leaves free that its children may take. */
    static final double FREE_MEMORY_SHARE = 0.75;
    /** More heap than this does not help one BBTools process. */
    static final int MAX_CHILD_GIB = 64;
    /**
     * Fewer samples run at once rather than any getting less than this: BBTools' own
     * default heap, which the baiting and mini-assembly of a typical sample fit in.
     */
    static final int MIN_SHARED_CHILD_GIB = 4;

    private ChildJavaMemory() {
    }

    /**
     * Heap, in GiB, that the child processes running at once may use between them: {@code requested}
     * ({@code --bbtools-memory}) when given, otherwise worked out from the computer ({@link #budgetGiB()}).
     */
    public static int budgetGiB(int requested) {
        return requested > 0 ? requested : budgetGiB();
    }

    /** Heap, in GiB, that the child processes running at once may use between them, by the computer. */
    public static int budgetGiB() {
        return budgetGiB(physicalMemoryBytes(), Runtime.getRuntime().maxMemory());
    }

    /**
     * The budget on a computer of {@code physicalBytes} (0 when unknown), this program having a
     * heap of {@code ownHeapBytes}: three quarters of what it leaves free, and at least half of its
     * heap, rounded to a GiB. Before 0.15.2, half of its heap only, rounded down: with Java's default
     * heap (a quarter of the computer's memory), BBTools got 2 GB of a 16 GB computer.
     */
    static int budgetGiB(long physicalBytes, long ownHeapBytes) {
        long ownHeap = ownHeapBytes <= 0 || ownHeapBytes == Long.MAX_VALUE ? 0 : ownHeapBytes;
        double halfHeap = ownHeap / 2.0 / BYTES_PER_GIB;
        double free = physicalBytes > ownHeap ? (physicalBytes - ownHeap) * FREE_MEMORY_SHARE / BYTES_PER_GIB : 0;
        double budget = Math.max(halfHeap, free);
        if (budget <= 0) {
            return DEFAULT_BUDGET_GIB;
        }
        return Math.toIntExact(Math.clamp(Math.round(budget), 1L, Integer.MAX_VALUE));
    }

    /** The computer's memory (a container's limit inside one), or 0 when the system does not say. */
    private static long physicalMemoryBytes() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            return os.getTotalMemorySize();
        }
        return 0;
    }

    /**
     * Memory a blastn search may take, in GiB: it holds a batch's hits, 1.7 GB for a 40-assay panel of 617
     * oligos on a 4.8 Mb genome (3.1 GB with 8 threads); 2 GB for a 48 Mb contig or 20,000 reads.
     */
    static final int BLASTN_GIB = 2;

    /**
     * How many blastn searches run at once within {@code budgetGiB}: as many as {@code threads} or
     * {@code items}, but no more than the budget holds at {@link #BLASTN_GIB} each, and at least one.
     * Before 0.15.3, one per thread: {@code -t 32} took 55 to 70 GB, besides this program's own heap.
     */
    static int blastnAtOnce(int budgetGiB, int threads, int items) {
        return Math.max(1, Math.min(Math.min(threads, Math.max(1, items)), budgetGiB / BLASTN_GIB));
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
        int each = Math.max(1, threads / atOnce);
        return new Share(atOnce, each, Math.max(0, threads - each * atOnce), forOneChild(budgetGiB / atOnce));
    }

    /**
     * @param atOnce       children running at the same time
     * @param threads      threads of each child
     * @param spareThreads threads left over when {@code atOnce} does not divide them: as many
     *                     children at a time run with one more
     * @param memoryGiB    heap of each child
     */
    record Share(int atOnce, int threads, int spareThreads, int memoryGiB) {
    }
}
