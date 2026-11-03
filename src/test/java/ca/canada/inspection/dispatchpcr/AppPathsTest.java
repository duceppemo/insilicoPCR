package ca.canada.inspection.dispatchpcr;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsTest {

    @Test
    void executableNameFollowsThePlatform() {
        String expected = System.getProperty("os.name").toLowerCase().contains("windows")
                ? "blastn.exe" : "blastn";
        assertEquals(expected, AppPaths.executableName("blastn"));
        assertEquals(Path.of("bin").resolve(expected), AppPaths.executable(Path.of("bin"), "blastn"));
    }

    @Test
    void platformNameMatchesIsWindows() {
        assertEquals(AppPaths.isWindows() ? "windows" : "linux", AppPaths.platformName());
    }
}
