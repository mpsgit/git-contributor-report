package hu.devreport;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutableJarIT {
    private static final Path JAR = Path.of("target", "git-contributor-report-1.0.0-SNAPSHOT.jar");

    @Test
    void shadedJarContainsInteractiveConsoleRuntimeClasses() throws Exception {
        assertTrue(Files.isRegularFile(JAR), "A futtatható JAR nem készült el: " + JAR);
        try (ZipFile jar = new ZipFile(JAR.toFile())) {
            assertEntry(jar, "hu/devreport/Main.class");
            assertEntry(jar, "hu/devreport/InteractiveConsole.class");
            assertEntry(jar, "com/googlecode/lanterna/gui2/dialogs/MessageDialog.class");
            assertEntry(jar, "com/googlecode/lanterna/gui2/dialogs/MessageDialogButton.class");
            assertEntry(jar, "com/googlecode/lanterna/gui2/dialogs/DirectoryDialogBuilder.class");
            assertEntry(jar, "org/jline/terminal/Terminal.class");
        }
    }

    @Test
    void packagedJarStartsWithoutAnExternalClasspath() throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path outputFile = Files.createTempFile("git-contributor-report-help-", ".txt");
        try {
            Process process = new ProcessBuilder(java.toString(), "-jar", JAR.toString(), "--help")
                    .redirectErrorStream(true)
                    .redirectOutput(outputFile.toFile())
                    .start();
            boolean completed = process.waitFor(30, TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            assertTrue(completed, "A csomagolt alkalmazás 30 másodpercen belül nem indult el.");
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("git-contributor-report"), output);
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static void assertEntry(ZipFile jar, String name) {
        assertNotNull(jar.getEntry(name), "Hiányzik a futtatható JAR-ból: " + name);
    }
}
