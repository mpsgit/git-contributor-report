package hu.devreport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Splits generated Markdown files without breaking UTF-8 characters or fenced code blocks. */
final class MarkdownSplitter {
    private static final int FENCE_RESERVE_BYTES = 1024;
    private static final Pattern OLD_PART = Pattern.compile("^(.*)\\.part-\\d+\\.md$", Pattern.CASE_INSENSITIVE);

    private MarkdownSplitter() { }

    static int enforce(Collection<Path> markdownFiles, long maximumBytes) throws IOException {
        return enforce(markdownFiles, maximumBytes, (current, total, detail) -> { });
    }

    static int enforce(Collection<Path> markdownFiles, long maximumBytes, SplitProgress progress) throws IOException {
        List<Path> files = List.copyOf(markdownFiles);
        int splitFiles = 0;
        progress.update(0, files.size(), "Korábbi darabfájlok felderítése");
        deleteOldParts(files);
        for (int index = 0; index < files.size(); index++) {
            Path file = files.get(index);
            progress.update(index, files.size(), "Markdown " + (index + 1) + "/" + files.size()
                    + " | " + file.getFileName());
            if (maximumBytes <= 0) continue;
            if (!Files.isRegularFile(file) || Files.size(file) <= maximumBytes) continue;
            List<Path> parts = split(file, maximumBytes);
            splitFiles++;
            ConsoleOutput.printf("Markdown darabolva: %s → %d rész (max. %s)%n",
                    file, parts.size(), CliOptions.formatByteSize(maximumBytes));
        }
        progress.update(files.size(), files.size(), "Markdown-ellenőrzés kész");
        return splitFiles;
    }

    private static List<Path> split(Path source, long maximumBytes) throws IOException {
        List<Path> parts = new ArrayList<>();
        PartWriter output = new PartWriter(source, maximumBytes, parts);
        Fence fence = null;
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Fence nextFence = transition(fence, line);
                output.write(line + "\n", fence);
                fence = nextFence;
            }
            output.finish(fence);
        } catch (IOException exception) {
            output.abort();
            throw exception;
        }

        String manifest = manifest(source, parts, maximumBytes);
        if (utf8Length(manifest) > maximumBytes) manifest = compactManifest(source, parts, maximumBytes);
        Path temporary = source.resolveSibling(source.getFileName() + ".split.tmp");
        Files.writeString(temporary, manifest, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(temporary, source, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return parts;
    }

    private static String manifest(Path source, List<Path> parts, long maximumBytes) {
        StringBuilder out = new StringBuilder("# Darabolt Markdown-dokumentum\n\n")
                .append("Az eredeti dokumentum meghaladta a beállított **")
                .append(CliOptions.formatByteSize(maximumBytes)).append("** méretkorlátot.\n\n")
                .append("Részek: **").append(parts.size()).append("**\n\n");
        for (int index = 0; index < parts.size(); index++) {
            String name = parts.get(index).getFileName().toString();
            out.append("- [").append(index + 1).append(". rész](").append(name).append(") — `")
                    .append(name).append("`\n");
        }
        return out.toString();
    }

    private static String compactManifest(Path source, List<Path> parts, long maximumBytes) {
        String first = parts.getFirst().getFileName().toString();
        String last = parts.getLast().getFileName().toString();
        return "# Darabolt Markdown-dokumentum\n\n"
                + "Az eredeti dokumentum " + parts.size() + " részre lett bontva (max. "
                + CliOptions.formatByteSize(maximumBytes) + ").\n\n"
                + "- [Első rész](" + first + ")\n"
                + "- [Utolsó rész](" + last + ")\n\n"
                + "Fájlnévminta: `" + stem(source) + ".part-NNN.md`\n";
    }

    private static void deleteOldParts(Collection<Path> sources) throws IOException {
        Map<Path, Set<String>> stemsByDirectory = new HashMap<>();
        for (Path source : sources) {
            Path normalized = source.toAbsolutePath().normalize();
            Path directory = normalized.getParent();
            if (directory != null) stemsByDirectory.computeIfAbsent(directory, ignored -> new HashSet<>())
                    .add(stem(normalized).toLowerCase(Locale.ROOT));
        }
        for (Map.Entry<Path, Set<String>> entry : stemsByDirectory.entrySet()) {
            if (!Files.isDirectory(entry.getKey())) continue;
            try (var files = Files.list(entry.getKey())) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Matcher matcher = OLD_PART.matcher(file.getFileName().toString());
                    if (matcher.matches() && entry.getValue().contains(matcher.group(1).toLowerCase(Locale.ROOT))) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    interface SplitProgress {
        void update(int current, int total, String detail);
    }

    private static String stem(Path source) {
        String name = source.getFileName().toString();
        return name.toLowerCase().endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    }

    private static Fence transition(Fence current, String line) {
        String stripped = line.stripLeading();
        if (current == null) {
            if (stripped.length() < 3) return null;
            char marker = stripped.charAt(0);
            if (marker != '`' && marker != '~') return null;
            int length = markerCount(stripped, marker);
            return length >= 3 ? new Fence(marker, length, line) : null;
        }
        int length = markerCount(stripped, current.marker);
        if (length >= current.length && stripped.substring(length).isBlank()) return null;
        return current;
    }

    private static int markerCount(String value, char marker) {
        int count = 0;
        while (count < value.length() && value.charAt(count) == marker) count++;
        return count;
    }

    private static long utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private record Fence(char marker, int length, String openingLine) {
        String closingLine() {
            return String.valueOf(marker).repeat(length);
        }
    }

    private static final class PartWriter {
        private final Path source;
        private final long maximumBytes;
        private final List<Path> parts;
        private BufferedWriter writer;
        private long bytes;

        private PartWriter(Path source, long maximumBytes, List<Path> parts) {
            this.source = source;
            this.maximumBytes = maximumBytes;
            this.parts = parts;
        }

        void write(String text, Fence activeFence) throws IOException {
            String remaining = text;
            while (!remaining.isEmpty()) {
                ensureOpen(activeFence);
                long available = maximumBytes - FENCE_RESERVE_BYTES - bytes;
                if (available <= 0) {
                    rotate(activeFence);
                    continue;
                }
                int characterCount = utf8PrefixCharacterCount(remaining, available);
                if (characterCount == 0) {
                    rotate(activeFence);
                    continue;
                }
                String fragment = remaining.substring(0, characterCount);
                writer.write(fragment);
                bytes += utf8Length(fragment);
                remaining = remaining.substring(characterCount);
                if (!remaining.isEmpty()) rotate(activeFence);
            }
        }

        void finish(Fence activeFence) throws IOException {
            if (writer == null) ensureOpen(activeFence);
            closeCurrent(activeFence);
        }

        void abort() {
            try {
                if (writer != null) writer.close();
            } catch (IOException ignored) { }
            for (Path part : parts) {
                try {
                    Files.deleteIfExists(part);
                } catch (IOException ignored) { }
            }
        }

        private void ensureOpen(Fence activeFence) throws IOException {
            if (writer != null) return;
            Path part = source.resolveSibling(stem(source) + ".part-%03d.md".formatted(parts.size() + 1));
            parts.add(part);
            writer = Files.newBufferedWriter(part, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            bytes = 0;
            if (activeFence != null) {
                String reopening = activeFence.openingLine + "\n";
                if (utf8Length(reopening) >= maximumBytes - FENCE_RESERVE_BYTES) {
                    reopening = activeFence.closingLine() + "\n";
                }
                writer.write(reopening);
                bytes += utf8Length(reopening);
            }
        }

        private void rotate(Fence activeFence) throws IOException {
            closeCurrent(activeFence);
            ensureOpen(activeFence);
        }

        private void closeCurrent(Fence activeFence) throws IOException {
            if (writer == null) return;
            if (activeFence != null) {
                String closing = "\n" + activeFence.closingLine() + "\n";
                writer.write(closing);
                bytes += utf8Length(closing);
            }
            writer.close();
            writer = null;
            if (bytes > maximumBytes) {
                throw new IOException("A Markdown-rész túllépte a méretkorlátot: " + parts.getLast());
            }
        }

        private static int utf8PrefixCharacterCount(String value, long maximumBytes) {
            long bytes = 0;
            int offset = 0;
            while (offset < value.length()) {
                int codePoint = value.codePointAt(offset);
                int codePointBytes = utf8Bytes(codePoint);
                if (bytes + codePointBytes > maximumBytes) break;
                bytes += codePointBytes;
                offset += Character.charCount(codePoint);
            }
            return offset;
        }

        private static int utf8Bytes(int codePoint) {
            if (codePoint <= 0x7f) return 1;
            if (codePoint <= 0x7ff) return 2;
            if (codePoint <= 0xffff) return 3;
            return 4;
        }
    }
}
