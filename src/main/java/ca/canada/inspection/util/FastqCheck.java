package ca.canada.inspection.util;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * Reads a FASTQ file from its first byte to its last and says whether every record is
 * well formed.
 *
 * <p>A record is four lines: a header starting with {@code @}, the bases, a separator
 * starting with {@code +}, and as many quality characters as there are bases. The whole
 * file is read, compressed or not, because the faults that matter are often at the end:
 * a download or a copy that stopped early leaves a gzip stream or a record cut short,
 * and the first reads look fine.</p>
 */
public final class FastqCheck {

    /** What is wrong with a file, or {@link #NONE}. */
    public enum Fault {
        NONE,
        /** No record at all: an empty file, an empty gzip stream, or blank lines only. */
        EMPTY,
        /** The file cannot be read to its end: not gzip, or a gzip stream that is damaged or cut short. */
        UNREADABLE,
        /** The first line does not start with {@code @}: not a FASTQ file. */
        NOT_FASTQ,
        /** A record is not four lines of the expected kind. */
        MALFORMED,
        /** The file ends inside a record. */
        TRUNCATED
    }

    /**
     * @param fault   what is wrong, or {@link Fault#NONE}
     * @param records complete records read (all of them when there is no fault)
     * @param detail  where and what, for the user; empty when there is no fault
     */
    public record Result(Fault fault, long records, String detail) {
        public boolean valid() {
            return fault == Fault.NONE;
        }
    }

    private static final boolean[] BASE = new boolean[256];
    private static final boolean[] QUALITY = new boolean[256];
    /** Every byte but the line ends: header and separator lines are not looked into. */
    private static final boolean[] ANY = new boolean[256];

    static {
        for (int c = 'A'; c <= 'Z'; c++) {
            BASE[c] = true;
            BASE[c + 32] = true;
        }
        BASE['.'] = true;
        BASE['-'] = true;
        BASE['*'] = true;
        for (int c = '!'; c <= '~'; c++) {
            QUALITY[c] = true;
        }
        java.util.Arrays.fill(ANY, true);
        ANY['\n'] = false;
        ANY['\r'] = false;
    }

    private FastqCheck() {
    }

    /** Checks every record of {@code file}, read through gzip when its name ends with {@code .gz}. */
    public static Result check(Path file) {
        return check(file, Long.MAX_VALUE);
    }

    /**
     * Checks the first {@code maxRecords} records of {@code file} only. A file that is
     * damaged or cut short further on passes: this is the quick check, for runs that
     * must not read their input twice.
     */
    public static Result check(Path file, long maxRecords) {
        boolean gzipped = file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz");
        Scan scan = new Scan(maxRecords);
        try {
            if (Files.size(file) == 0) {
                return new Result(Fault.EMPTY, 0, "the file is empty");
            }
        } catch (IOException e) {
            return new Result(Fault.UNREADABLE, 0, "cannot be read: " + e.getMessage());
        }
        try (InputStream raw = Files.newInputStream(file);
             InputStream in = gzipped ? new GZIPInputStream(raw, 1 << 16) : raw) {
            byte[] block = new byte[1 << 16];
            int read;
            while ((read = in.read(block)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    // The run was cancelled; the verdict will not be used.
                    return new Result(Fault.UNREADABLE, scan.records, "the check was interrupted");
                }
                String problem = scan.accept(block, read);
                if (problem != null) {
                    return scan.fault(problem);
                }
                if (scan.records >= maxRecords) {
                    return new Result(Fault.NONE, scan.records, "");
                }
            }
        } catch (EOFException e) {
            // GZIPInputStream: the compressed stream stops before its end marker.
            return new Result(Fault.UNREADABLE, scan.records,
                    "the gzip stream ends early, after " + reads(scan.records) + ": the file is incomplete");
        } catch (ZipException e) {
            return new Result(Fault.UNREADABLE, scan.records, scan.records == 0 && scan.line == 1 && !scan.lineOpen
                    ? "not a gzip file (" + e.getMessage() + ")"
                    : "the gzip stream is damaged after " + reads(scan.records) + " (" + e.getMessage() + ")");
        } catch (IOException e) {
            return new Result(Fault.UNREADABLE, scan.records, "cannot be read: " + e.getMessage());
        }
        return scan.end();
    }

    private static String reads(long count) {
        return String.format(Locale.ROOT, "%,d complete read%s", count, count == 1 ? "" : "s");
    }

    /** The state between blocks: which line of which record is being read. */
    private static final class Scan {
        private final long limit;
        long records;
        /** 1-based number of the line being read. */
        long line = 1;
        /** 0 header, 1 bases, 2 separator, 3 qualities. */
        int part;
        /** Characters of the current line so far. */
        long length;
        long bases;
        /** Whether the current line has any character, a carriage return included. */
        boolean lineOpen;
        /** Blank lines where a header was expected; fine at the end of the file only. */
        long blankLines;
        boolean sawAnyLine;

        Scan(long limit) {
            this.limit = limit;
        }

        /** @return the problem met in this block, or {@code null} */
        String accept(byte[] block, int count) {
            int i = 0;
            while (i < count && records < limit) {
                int b = block[i] & 0xFF;
                if (b == '\n') {
                    String problem = endLine();
                    if (problem != null) {
                        return problem;
                    }
                    line++;
                    length = 0;
                    lineOpen = false;
                    i++;
                    continue;
                }
                lineOpen = true;
                if (b == '\r') {
                    i++;
                    continue;
                }
                if (length == 0) {
                    String problem = firstCharacter(b);
                    if (problem != null) {
                        return problem;
                    }
                    length = 1;
                    i++;
                }
                // The rest of the line, or of the block: nearly every byte of the file goes through here.
                boolean[] allowed = part == 1 ? BASE : part == 3 ? QUALITY : ANY;
                int from = i;
                while (i < count && allowed[block[i] & 0xFF]) {
                    i++;
                }
                length += i - from;
                if (i < count) {
                    int stopped = block[i] & 0xFF;
                    if (stopped != '\n' && stopped != '\r') {
                        return part == 1
                                ? "the sequence line holds a character that is not a base (" + printable(stopped) + ")"
                                : "the quality line holds a character that is not a quality score ("
                                + printable(stopped) + ")";
                    }
                }
            }
            return null;
        }

        private String firstCharacter(int b) {
            switch (part) {
                case 0:
                    if (blankLines > 0) {
                        return "blank line before this line";
                    }
                    if (b != '@') {
                        return "the header line does not start with @";
                    }
                    break;
                case 1:
                    if (!BASE[b]) {
                        return "the sequence line holds a character that is not a base (" + printable(b) + ")";
                    }
                    break;
                case 2:
                    if (b != '+') {
                        return "the third line of the record does not start with +";
                    }
                    break;
                default:
                    if (!QUALITY[b]) {
                        return "the quality line holds a character that is not a quality score (" + printable(b) + ")";
                    }
            }
            return null;
        }

        private String endLine() {
            sawAnyLine = true;
            switch (part) {
                case 0:
                    if (length == 0) {
                        blankLines++;
                        return null;
                    }
                    part = 1;
                    return null;
                case 1:
                    bases = length;
                    part = 2;
                    return null;
                case 2:
                    if (length == 0) {
                        return "the third line of the record is empty, a line starting with + is expected";
                    }
                    part = 3;
                    return null;
                default:
                    if (length != bases) {
                        return "the quality line has " + length + " characters for " + bases + " bases";
                    }
                    records++;
                    part = 0;
                    return null;
            }
        }

        Result fault(String problem) {
            boolean firstLine = records == 0 && line == 1;
            Fault fault = firstLine && part == 0 ? Fault.NOT_FASTQ : Fault.MALFORMED;
            return new Result(fault, records, String.format(Locale.ROOT, "read %,d (line %,d): %s",
                    records + 1, line, problem));
        }

        /** The verdict once the last byte was read. */
        Result end() {
            if (lineOpen && part == 3 && length < bases) {
                // The last line stops short of its read's length, without a line end: cut off.
                return new Result(Fault.TRUNCATED, records, String.format(Locale.ROOT,
                        "it ends inside read %,d, after %s", records + 1, reads(records)));
            }
            if (lineOpen) {
                // Last line without a line end.
                String problem = endLine();
                if (problem != null) {
                    return fault(problem);
                }
            } else if (part == 3 && bases == 0) {
                // A read without bases as the last record: its empty quality line is the end of the file.
                records++;
                part = 0;
            }
            if (part != 0) {
                return new Result(Fault.TRUNCATED, records, String.format(Locale.ROOT,
                        "it ends inside read %,d, after %s", records + 1, reads(records)));
            }
            if (records == 0) {
                return new Result(Fault.EMPTY, 0, sawAnyLine || lineOpen ? "the file holds blank lines only" : "the file is empty");
            }
            return new Result(Fault.NONE, records, "");
        }

        private static String printable(int b) {
            return b >= 33 && b <= 126 ? "'" + (char) b + "'" : String.format(Locale.ROOT, "byte 0x%02X", b);
        }
    }
}
