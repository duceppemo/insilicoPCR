package ca.canada.inspection.pipeline;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;

/** What to tell the user when a file of an earlier run cannot be replaced. */
final class OpenElsewhere {
    private OpenElsewhere() {
    }

    /**
     * {@code error}, from deleting or replacing a file, in words: on Windows a file another program has
     * open (Excel showing a report, an antivirus scanning it) cannot be deleted, and Java says only
     * "The process cannot access the file because it is being used by another process".
     */
    static String describe(IOException error) {
        String reason = error instanceof FileSystemException system && system.getReason() != null
                ? system.getReason() : String.valueOf(error.getMessage());
        if (error instanceof AccessDeniedException || reason.contains("used by another process")) {
            return "it is open in another program (Excel, a text editor, an antivirus scan) or read-only: close it"
                    + " there and run again (" + reason + ")";
        }
        return reason;
    }
}
