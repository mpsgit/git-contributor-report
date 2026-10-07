package hu.devreport;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.terminal.ansi.ANSITerminal;
import org.apache.commons.io.output.WriterOutputStream;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Uses JLine's native Windows terminal support underneath Lanterna's GUI toolkit. */
final class JLineLanternaTerminal extends ANSITerminal {
    private static final long ESCAPE_SEQUENCE_TIMEOUT_MILLIS = 250L;

    private final Terminal terminal;
    private final PrintWriter displayWriter;
    private Attributes previousAttributes;

    static JLineLanternaTerminal create() throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .name("git-contributor-report")
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .dumb(false)
                .build();
        return new JLineLanternaTerminal(terminal);
    }

    private JLineLanternaTerminal(Terminal terminal) throws IOException {
        super(InputStream.nullInputStream(), unicodeOutput(ConsoleOutput.out()), StandardCharsets.UTF_8);
        this.terminal = terminal;
        this.displayWriter = ConsoleOutput.out();
        terminal.handle(Terminal.Signal.WINCH, signal -> onResized(currentSize()));
    }

    /**
     * Lanterna produces UTF-8 bytes, while JLine's Windows terminal writer expects Unicode
     * characters. Decoding here avoids sending UTF-8 bytes through the active OEM code page.
     */
    private static OutputStream unicodeOutput(Writer writer) throws IOException {
        return WriterOutputStream.builder()
                .setWriter(writer)
                .setCharset(StandardCharsets.UTF_8)
                .setWriteImmediately(true)
                .get();
    }

    @Override
    public synchronized void enterPrivateMode() throws IOException {
        if (previousAttributes == null) previousAttributes = terminal.enterRawMode();
        super.enterPrivateMode();
    }

    @Override
    public synchronized void exitPrivateMode() throws IOException {
        try {
            super.exitPrivateMode();
        } finally {
            if (previousAttributes != null) {
                terminal.setAttributes(previousAttributes);
                previousAttributes = null;
            }
            terminal.flush();
        }
    }

    @Override
    protected TerminalSize findTerminalSize() {
        return currentSize();
    }

    @Override
    public void putCharacter(char character) {
        if (TerminalTextUtils.isPrintableCharacter(character)) displayWriter.write(character);
    }

    @Override
    public void putString(String string) {
        displayWriter.write(string);
    }

    @Override
    public KeyStroke readInput() throws IOException {
        return decode(terminal.reader().read());
    }

    @Override
    public KeyStroke pollInput() throws IOException {
        int character = terminal.reader().read(1L);
        return character == NonBlockingReader.READ_EXPIRED ? null : decode(character);
    }

    @Override
    public void flush() throws IOException {
        super.flush();
        displayWriter.flush();
        terminal.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            super.close();
        } finally {
            terminal.close();
        }
    }

    private TerminalSize currentSize() {
        int columns = terminal.getSize().getColumns() > 0 ? terminal.getSize().getColumns() : 80;
        int rows = terminal.getSize().getRows() > 0 ? terminal.getSize().getRows() : 24;
        return new TerminalSize(columns, rows);
    }

    private KeyStroke decode(int character) throws IOException {
        KeyStroke simple = decodeSimple(character);
        if (simple != null) return simple;
        if (character == 27) return decodeEscapeSequence();
        return character < 0 ? new KeyStroke(KeyType.EOF) : new KeyStroke(KeyType.Unknown);
    }

    /** Maps keys which consist of one terminal character; kept separate for regression tests. */
    static KeyStroke decodeSimple(int character) {
        return switch (character) {
            case 9 -> new KeyStroke(KeyType.Tab);
            case 10, 13 -> new KeyStroke(KeyType.Enter);
            case 8, 127 -> new KeyStroke(KeyType.Backspace);
            case 27 -> null;
            case -1 -> new KeyStroke(KeyType.EOF);
            default -> {
                if (character >= 1 && character <= 26) {
                    yield new KeyStroke((char) ('a' + character - 1), true, false);
                }
                if (Character.isValidCodePoint(character) && character <= Character.MAX_VALUE) {
                    yield new KeyStroke((char) character, false, false);
                }
                yield new KeyStroke(KeyType.Unknown);
            }
        };
    }

    private KeyStroke decodeEscapeSequence() throws IOException {
        int first = terminal.reader().read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS);
        if (first == NonBlockingReader.READ_EXPIRED) return new KeyStroke(KeyType.Escape);
        if (first == '[') return decodeControlSequence();
        if (first == 'O') return decodeSs3Sequence();
        if (first < 0) return new KeyStroke(KeyType.Escape);
        return new KeyStroke((char) first, false, true);
    }

    private KeyStroke decodeControlSequence() throws IOException {
        StringBuilder sequence = new StringBuilder();
        while (sequence.length() < 12) {
            int next = terminal.reader().read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS);
            if (next == NonBlockingReader.READ_EXPIRED || next < 0) break;
            sequence.append((char) next);
            if (next >= 0x40 && next <= 0x7e) break;
        }
        return switch (sequence.toString()) {
            case "A" -> key(KeyType.ArrowUp);
            case "B" -> key(KeyType.ArrowDown);
            case "C" -> key(KeyType.ArrowRight);
            case "D" -> key(KeyType.ArrowLeft);
            case "H", "1~", "7~" -> key(KeyType.Home);
            case "F", "4~", "8~" -> key(KeyType.End);
            case "Z" -> key(KeyType.ReverseTab);
            case "2~" -> key(KeyType.Insert);
            case "3~" -> key(KeyType.Delete);
            case "5~" -> key(KeyType.PageUp);
            case "6~" -> key(KeyType.PageDown);
            case "11~" -> key(KeyType.F1);
            case "12~" -> key(KeyType.F2);
            case "13~" -> key(KeyType.F3);
            case "14~" -> key(KeyType.F4);
            case "15~" -> key(KeyType.F5);
            case "17~" -> key(KeyType.F6);
            case "18~" -> key(KeyType.F7);
            case "19~" -> key(KeyType.F8);
            case "20~" -> key(KeyType.F9);
            case "21~" -> key(KeyType.F10);
            case "23~" -> key(KeyType.F11);
            case "24~" -> key(KeyType.F12);
            default -> key(KeyType.Unknown);
        };
    }

    private KeyStroke decodeSs3Sequence() throws IOException {
        int next = terminal.reader().read(ESCAPE_SEQUENCE_TIMEOUT_MILLIS);
        return switch (next) {
            case 'A' -> key(KeyType.ArrowUp);
            case 'B' -> key(KeyType.ArrowDown);
            case 'C' -> key(KeyType.ArrowRight);
            case 'D' -> key(KeyType.ArrowLeft);
            case 'H' -> key(KeyType.Home);
            case 'F' -> key(KeyType.End);
            case 'P' -> key(KeyType.F1);
            case 'Q' -> key(KeyType.F2);
            case 'R' -> key(KeyType.F3);
            case 'S' -> key(KeyType.F4);
            default -> key(KeyType.Unknown);
        };
    }

    private static KeyStroke key(KeyType type) {
        return new KeyStroke(type);
    }
}
