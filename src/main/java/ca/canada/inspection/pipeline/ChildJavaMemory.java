package ca.canada.inspection.pipeline;

public final class ChildJavaMemory {
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;
    private static final int DEFAULT_GIB = 4;

    private ChildJavaMemory() {
    }

    public static int recommendedGiB() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        if (maxMemory <= 0 || maxMemory == Long.MAX_VALUE) {
            return DEFAULT_GIB;
        }
        long halfAvailableHeap = Math.max(BYTES_PER_GIB, maxMemory / 2);
        return Math.toIntExact(Math.clamp(halfAvailableHeap / BYTES_PER_GIB, 1L, 64L));
    }
}
