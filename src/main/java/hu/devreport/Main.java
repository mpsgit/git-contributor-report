package hu.devreport;

import org.jline.jansi.AnsiConsole;
import org.jline.nativ.Kernel32;

import java.util.Arrays;
import java.util.Locale;

/** Minimal executable entry point; delegates configuration and application work. */
public final class Main {
    private Main() { }

    public static void main(String[] rawArgs) {
        boolean interactive = Arrays.stream(rawArgs)
                .anyMatch(argument -> argument.equals("-i") || argument.equals("--interactive"));
        boolean informational = Arrays.stream(rawArgs)
                .anyMatch(argument -> argument.equals("-h") || argument.equals("--help")
                        || argument.equals("-V") || argument.equals("--version"));
        if (interactive && !informational) {
            enableUtf8WindowsConsole();
            ConsoleOutput.initialize();
            int exitCode = CliOptions.execute(rawArgs);
            if (exitCode != 0) System.exit(exitCode);
            return;
        }

        AnsiConsole.systemInstall();
        enableUtf8WindowsConsole();
        ConsoleOutput.initialize();
        int exitCode;
        try {
            exitCode = CliOptions.execute(rawArgs);
        } finally {
            AnsiConsole.systemUninstall();
        }
        if (exitCode != 0) System.exit(exitCode);
    }

    private static void enableUtf8WindowsConsole() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        try {
            Kernel32.SetConsoleOutputCP(65001);
        } catch (LinkageError ignored) {
            // Jansi remains usable in graceful fallback mode when no native console is attached.
        }
    }
}
