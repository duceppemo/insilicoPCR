package ca.canada.inspection.pipeline;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * On Windows, the programs a run starts (BLAST+, BBTools) end with this program, however it ends.
 *
 * <p>Ending a program from Task Manager, a workflow manager stopping its job or an IDE's stop button
 * terminates it at once (TerminateProcess): no shutdown hook runs, and its programs were left running,
 * writing into the output folder and holding their memory. blastn, which prints nothing while it
 * searches, ran on to the end of its search. Each program is therefore put in a job object that the
 * system closes when this program ends, and closing it ends the programs in it
 * ({@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE}).</p>
 *
 * <p>Only the programs are put in the job, not this program: a web browser it opens (the HTML report)
 * must outlive it. Anything that fails here leaves the programs as they were, outside the job.</p>
 */
final class WindowsJob {
    /** {@code JobObjectExtendedLimitInformation}. */
    private static final int EXTENDED_LIMIT_INFORMATION = 9;
    /** {@code sizeof(JOBOBJECT_EXTENDED_LIMIT_INFORMATION)} on 64-bit Windows. */
    private static final int EXTENDED_LIMIT_INFORMATION_SIZE = 144;
    /** Offset of {@code BasicLimitInformation.LimitFlags} in it. */
    private static final int LIMIT_FLAGS_OFFSET = 16;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;

    private static final Job JOB = AppPaths.isWindows() ? Job.create() : null;

    private WindowsJob() {
    }

    /** Whether the programs started from now on end with this program (Windows only). */
    static boolean active() {
        return JOB != null;
    }

    /** Puts {@code process} in the job, on Windows; elsewhere, or if that fails, does nothing. */
    static void adopt(Process process) {
        if (JOB != null) {
            JOB.assign(process.pid());
        }
    }

    /** The job object, held open until this program ends; the system closes it then. */
    private record Job(MemorySegment handle, MethodHandle openProcess, MethodHandle assignProcess,
                       MethodHandle closeHandle) {

        static Job create() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                MethodHandle createJob = linker.downcallHandle(kernel32.findOrThrow("CreateJobObjectW"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MethodHandle setInformation = linker.downcallHandle(kernel32.findOrThrow("SetInformationJobObject"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                MethodHandle openProcess = linker.downcallHandle(kernel32.findOrThrow("OpenProcess"),
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT));
                MethodHandle assignProcess = linker.downcallHandle(kernel32.findOrThrow("AssignProcessToJobObject"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                MethodHandle closeHandle = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                MemorySegment job = (MemorySegment) createJob.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
                if (job.address() == 0) {
                    return null;
                }
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment limits = arena.allocate(EXTENDED_LIMIT_INFORMATION_SIZE, 8);
                    limits.fill((byte) 0);
                    limits.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
                    int set = (int) setInformation.invokeExact(job, EXTENDED_LIMIT_INFORMATION, limits,
                            EXTENDED_LIMIT_INFORMATION_SIZE);
                    if (set == 0) {
                        int closed = (int) closeHandle.invokeExact(job);
                        return null;
                    }
                }
                return new Job(job, openProcess, assignProcess, closeHandle);
            } catch (Throwable e) {
                // No native access, another system: the programs run as before.
                return null;
            }
        }

        void assign(long pid) {
            try {
                MemorySegment process = (MemorySegment) openProcess.invokeExact(
                        PROCESS_SET_QUOTA | PROCESS_TERMINATE, 0, (int) pid);
                if (process.address() == 0) {
                    return; // already ended
                }
                try {
                    int assigned = (int) assignProcess.invokeExact(handle, process);
                } finally {
                    int closed = (int) closeHandle.invokeExact(process);
                }
            } catch (Throwable e) {
                // The program runs on outside the job, as before.
            }
        }
    }
}
