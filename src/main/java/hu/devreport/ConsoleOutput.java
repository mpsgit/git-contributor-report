package hu.devreport;

import org.jline.nativ.Kernel32;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/** Unicode-safe console writers backed by the Windows wide-character API when available. */
final class ConsoleOutput {
    private static PrintWriter output = new PrintWriter(System.out, true);
    private static PrintWriter error = new PrintWriter(System.err, true);
    private static boolean terminalOutput = System.console() != null;

    private ConsoleOutput() { }

    static void initialize() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) return;
        output = windowsWriter(Kernel32.STD_OUTPUT_HANDLE, FileDescriptor.out, true);
        error = windowsWriter(Kernel32.STD_ERROR_HANDLE, FileDescriptor.err, false);
    }

    static PrintWriter out() {
        return output;
    }

    static PrintWriter err() {
        return error;
    }

    static boolean supportsAnsi() {
        return terminalOutput;
    }

    static void println(String value) {
        output.println(value);
    }

    static void printf(String format, Object... values) {
        output.printf(format, values);
        output.flush();
    }

    static synchronized Redirection redirect(PrintWriter newOutput, PrintWriter newError) {
        PrintWriter previousOutput = output;
        PrintWriter previousError = error;
        output = newOutput;
        error = newError;
        return new Redirection(previousOutput, previousError);
    }

    static final class Redirection implements AutoCloseable {
        private final PrintWriter previousOutput;
        private final PrintWriter previousError;
        private boolean closed;

        private Redirection(PrintWriter previousOutput, PrintWriter previousError) {
            this.previousOutput = previousOutput;
            this.previousError = previousError;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            output.flush();
            error.flush();
            output = previousOutput;
            error = previousError;
            closed = true;
        }
    }

    private static PrintWriter windowsWriter(int standardHandle, FileDescriptor descriptor, boolean standardOutput) {
        try {
            long handle = Kernel32.GetStdHandle(standardHandle);
            int[] mode = new int[1];
            if (handle == Kernel32.INVALID_HANDLE_VALUE || handle == 0 || Kernel32.GetConsoleMode(handle, mode) == 0) {
                if (standardOutput) terminalOutput = false;
                return utf8Writer(descriptor);
            }
            if (standardOutput) terminalOutput = true;
            return new PrintWriter(new WideConsoleWriter(handle), true);
        } catch (LinkageError error) {
            if (standardOutput) terminalOutput = false;
            return utf8Writer(descriptor);
        }
    }

    private static PrintWriter utf8Writer(FileDescriptor descriptor) {
        return new PrintWriter(new FileOutputStream(descriptor), true, StandardCharsets.UTF_8);
    }

    private static final class WideConsoleWriter extends Writer {
        private static final int MAX_CHUNK = 16_384;
        private final long handle;

        private WideConsoleWriter(long handle) {
            this.handle = handle;
        }

        @Override
        public synchronized void write(char[] characters, int offset, int length) throws IOException {
            while (length > 0) {
                int chunkLength = Math.min(length, MAX_CHUNK);
                char[] chunk = offset == 0 && chunkLength == characters.length
                        ? characters
                        : Arrays.copyOfRange(characters, offset, offset + chunkLength);
                int[] written = new int[1];
                if (Kernel32.WriteConsoleW(handle, chunk, chunkLength, written, 0) == 0) {
                    throw new IOException("A Windows konzol Unicode-kimenete sikertelen: "
                            + Kernel32.getLastErrorMessage());
                }
                offset += written[0];
                length -= written[0];
            }
        }

        @Override
        public void flush() { }

        @Override
        public void close() { }
    }
}
