package ca.canada.inspection.pipeline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A conda environment: BLAST+ in bin, bbduk.sh linked into bin from opt/bbmap-x, its classes in current. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "conda links bbduk.sh into bin; Windows makes no link here")
    void anInstallationWithoutARuntimeFolderRunsTheProgramsOnPath(@TempDir Path prefix) throws IOException {
        Path bin = Files.createDirectories(prefix.resolve("bin"));
        Path bbmap = Files.createDirectories(prefix.resolve("opt").resolve("bbmap-40.04-0"));
        Files.createDirectories(bbmap.resolve("current"));
        Files.writeString(bbmap.resolve("bbduk.sh"), "");
        Files.createSymbolicLink(bin.resolve("bbduk.sh"), bin.relativize(bbmap.resolve("bbduk.sh")));
        Files.writeString(bin.resolve("blastn"), "");
        Files.writeString(bin.resolve("makeblastdb"), "");
        Path elsewhere = Files.createDirectories(prefix.resolve("usr").resolve("bin"));
        Path app = prefix.resolve("share").resolve("insilicopcr");
        Path javaHome = prefix.resolve("lib").resolve("jvm");

        AppPaths.RuntimeLayout layout = AppPaths.installed(app,
                elsewhere + File.pathSeparator + File.pathSeparator + bin, javaHome);

        assertEquals(app, layout.appRoot());
        assertEquals(bin, layout.blastBinDirectory());
        assertEquals(bbmap.toRealPath(), layout.bbmapDirectory(), "the folder the link leads to");
        assertEquals(javaHome.resolve("bin").resolve("java"), layout.javaExecutable(), "the Java running insilicoPCR");
        assertEquals(bbmap.toRealPath().resolve("current"), new RuntimeDependencies(layout).bbtoolsClassPath());
    }

    @Test
    void anInstalledBbtoolsWithItsJarRunsFromTheJar(@TempDir Path dir) throws IOException {
        Path blast = Files.createDirectories(dir.resolve("blast"));
        Files.writeString(AppPaths.executable(blast, "blastn"), "");
        Files.writeString(AppPaths.executable(blast, "makeblastdb"), "");
        Path bbmap = Files.createDirectories(dir.resolve("bbmap"));
        Files.writeString(bbmap.resolve("bbduk.sh"), "");
        Files.writeString(bbmap.resolve("bbtools.jar"), "");
        Files.createDirectories(bbmap.resolve("current"));

        AppPaths.RuntimeLayout layout = AppPaths.installed(dir, bbmap + File.pathSeparator + blast, dir);

        assertEquals(blast, layout.blastBinDirectory());
        assertEquals(bbmap.resolve("bbtools.jar").toRealPath(), new RuntimeDependencies(layout).bbtoolsClassPath()
                .toRealPath(), "the jar, as in the portable releases, before a current folder");
    }

    @Test
    void aProgramMissingFromPathIsNamedWithWhereToGetIt(@TempDir Path dir) throws IOException {
        Path half = Files.createDirectories(dir.resolve("half"));
        Files.writeString(AppPaths.executable(half, "blastn"), "");

        PipelineException noBlast = assertThrows(PipelineException.class,
                () -> AppPaths.installed(dir, half.toString(), dir));
        assertTrue(noBlast.getMessage().startsWith("Unable to find NCBI BLAST+ (blastn and makeblastdb)."),
                noBlast.getMessage());
        assertTrue(noBlast.getMessage().contains("conda install -c conda-forge -c bioconda blast"),
                noBlast.getMessage());
        assertTrue(RunFailure.isForTheUser(noBlast));

        Files.writeString(AppPaths.executable(half, "makeblastdb"), "");
        PipelineException noBbtools = assertThrows(PipelineException.class,
                () -> AppPaths.installed(dir, half.toString(), dir));
        assertTrue(noBbtools.getMessage().startsWith("Unable to find BBTools (bbduk.sh)."), noBbtools.getMessage());

        assertThrows(PipelineException.class, () -> AppPaths.installed(dir, null, dir), "no PATH at all");
    }

    @Test
    void aBbdukShWithoutTheClassesOfBbtoolsIsRefused(@TempDir Path dir) throws IOException {
        Files.writeString(AppPaths.executable(dir, "blastn"), "");
        Files.writeString(AppPaths.executable(dir, "makeblastdb"), "");
        Files.writeString(dir.resolve("bbduk.sh"), "");

        PipelineException error = assertThrows(PipelineException.class,
                () -> AppPaths.installed(dir, dir.toString(), dir));

        assertTrue(error.getMessage().startsWith("Unable to find the classes of BBTools beside "), error.getMessage());
        assertTrue(error.getMessage().endsWith("neither bbtools.jar nor a current folder is there."),
                error.getMessage());
    }
}
