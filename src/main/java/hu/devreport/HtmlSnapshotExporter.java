package hu.devreport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static hu.devreport.GitReportApplication.execute;

/** Writes a deduplicated, completely offline source browser for every analyzed commit. */
final class HtmlSnapshotExporter {
    private HtmlSnapshotExporter() { }

    static SnapshotStats write(Path output, Analysis analysis, SnapshotProgress progress)
            throws IOException, InterruptedException {
        Path snapshotRoot = output.resolve("snapshots");
        deleteTree(snapshotRoot);
        Files.createDirectories(snapshotRoot.resolve("blobs"));
        Path partRoot = snapshotRoot.resolve(".parts");
        Files.createDirectories(partRoot);
        Path dataFile = output.resolve("snapshot-data.js");
        Path viewerFile = output.resolve("snapshot.html");
        Files.deleteIfExists(dataFile);
        Files.deleteIfExists(viewerFile);

        long totalCommits = analysis.repositories.stream().mapToLong(repository -> repository.commits.size()).sum();
        AtomicLong completed = new AtomicLong();
        AtomicLong blobBytes = new AtomicLong();
        Set<String> writtenBlobs = ConcurrentHashMap.newKeySet();
        List<RepositoryStats> repositories = analysis.repositories.stream()
                .sorted(Comparator.comparing(candidate -> candidate.name)).toList();
        ParallelSupport.Activity activity = ParallelSupport.activity(repositories.size(), 4);
        ExecutorService executor = ParallelSupport.executor("html-snapshot-", repositories.size(), 4);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int repositoryIndex = 0; repositoryIndex < repositories.size(); repositoryIndex++) {
                RepositoryStats repository = repositories.get(repositoryIndex);
                Path part = partRoot.resolve(String.format("%05d.part", repositoryIndex));
                futures.add(executor.submit(() -> {
                    try (ParallelSupport.Scope ignored = activity.start()) {
                        synchronized (progress) {
                            progress.update(completed.get(), totalCommits, repository.name,
                                    "indul", 0, writtenBlobs.size(), activity.label());
                        }
                        writeRepositoryPart(part, snapshotRoot, repository, writtenBlobs, blobBytes,
                                completed, totalCommits, activity, progress);
                    }
                    return null;
                }));
            }
            ParallelSupport.await(futures);
        } finally {
            executor.shutdownNow();
        }
        try (BufferedOutputStream data = new BufferedOutputStream(Files.newOutputStream(dataFile))) {
            data.write("window.GIT_SNAPSHOT_DATA={\"repositories\":{".getBytes(StandardCharsets.UTF_8));
            for (int repositoryIndex = 0; repositoryIndex < repositories.size(); repositoryIndex++) {
                if (repositoryIndex > 0) data.write(',');
                Files.copy(partRoot.resolve(String.format("%05d.part", repositoryIndex)), data);
            }
            data.write("}};".getBytes(StandardCharsets.UTF_8));
        }
        deleteTree(partRoot);
        Files.writeString(viewerFile, viewerHtml(), StandardCharsets.UTF_8);
        return new SnapshotStats(Files.size(dataFile) + Files.size(viewerFile) + blobBytes.get(),
                writtenBlobs.size(), totalCommits);
    }

    private static void writeRepositoryPart(Path part, Path snapshotRoot, RepositoryStats repository,
                                            Set<String> writtenBlobs, AtomicLong blobBytes,
                                            AtomicLong completed, long totalCommits,
                                            ParallelSupport.Activity activity,
                                            SnapshotProgress progress) throws IOException, InterruptedException {
        try (BufferedWriter data = Files.newBufferedWriter(part, StandardCharsets.UTF_8)) {
            data.write(json(repository.name));
            data.write(":{\"commits\":{");
            Set<String> knownCommits = repository.commits.stream().map(commit -> commit.hash)
                    .collect(java.util.stream.Collectors.toSet());
            boolean firstCommit = true;
            try (GitBlobBatch blobs = new GitBlobBatch(repository.path)) {
                for (CommitSummary commit : repository.commits.stream()
                        .sorted(Comparator.comparing(CommitSummary::date)).toList()) {
                    String firstParent = firstParent(commit.parents);
                    boolean delta = !firstParent.isBlank() && knownCommits.contains(firstParent);
                    Map<String, String> changes = delta
                            ? changedTree(repository.path, firstParent, commit.hash)
                            : completeTree(repository.path, commit.hash);
                    if (!firstCommit) data.write(',');
                    firstCommit = false;
                    data.write(json(commit.hash));
                    data.write(":{\"p\":");
                    data.write(json(delta ? firstParent : ""));
                    data.write(",\"c\":[");
                    boolean firstChange = true;
                    for (Map.Entry<String, String> change : changes.entrySet()) {
                        if (!firstChange) data.write(',');
                        firstChange = false;
                        data.write('[');
                        data.write(json(change.getKey()));
                        data.write(',');
                        if (change.getValue() == null) data.write("null");
                        else {
                            data.write(json(change.getValue()));
                            if (writtenBlobs.add(change.getValue())) {
                                byte[] content = blobs.read(change.getValue());
                                if (content != null) blobBytes.addAndGet(writeBlobPage(
                                        snapshotRoot.resolve("blobs"), change.getValue(), change.getKey(), content));
                            }
                        }
                        data.write(']');
                    }
                    data.write("]}");
                    long done = completed.incrementAndGet();
                    synchronized (progress) {
                        progress.update(done, totalCommits, repository.name,
                                commit.hash, changes.size(), writtenBlobs.size(), activity.label());
                    }
                }
            }
            data.write("}}");
        }
    }

    private static Map<String, String> completeTree(Path repository, String commit)
            throws IOException, InterruptedException {
        CommandResult result = execute(repository, List.of("git", "-c", "core.quotepath=false", "ls-tree",
                "-r", "-z", "--full-tree", commit));
        if (result.exitCode != 0) throw new IOException("Git snapshot-fa nem olvasható: " + result.stderr.strip());
        Map<String, String> tree = new LinkedHashMap<>();
        for (String record : result.stdout.split("\u0000", -1)) {
            if (record.isEmpty()) continue;
            int tab = record.indexOf('\t');
            if (tab < 0) continue;
            String[] metadata = record.substring(0, tab).trim().split("\\s+");
            if (metadata.length >= 3 && "blob".equals(metadata[1])) tree.put(record.substring(tab + 1), metadata[2]);
        }
        return tree;
    }

    private static Map<String, String> changedTree(Path repository, String parent, String commit)
            throws IOException, InterruptedException {
        CommandResult result = execute(repository, List.of("git", "-c", "core.quotepath=false", "diff-tree",
                "--no-commit-id", "-r", "--raw", "-z", "--no-renames", parent, commit));
        if (result.exitCode != 0) throw new IOException("Git snapshot-delta nem olvasható: " + result.stderr.strip());
        String[] records = result.stdout.split("\u0000", -1);
        Map<String, String> changes = new LinkedHashMap<>();
        for (int index = 0; index + 1 < records.length; index += 2) {
            String header = records[index].trim();
            String path = records[index + 1];
            if (header.isBlank() || path.isBlank()) continue;
            String[] metadata = header.split("\\s+");
            if (metadata.length < 5) continue;
            String status = metadata[4];
            String hash = metadata[3];
            changes.put(path, status.startsWith("D") || hash.chars().allMatch(character -> character == '0') ? null : hash);
        }
        return changes;
    }

    private static long writeBlobPage(Path directory, String hash, String originalPath, byte[] content)
            throws IOException {
        boolean binary = isBinary(content);
        String body = binary
                ? "<p class=\"empty-chart\">Bináris Git-blob; szöveges forráskódként nem jeleníthető meg.</p>"
                : "<pre class=\"source snapshot-source\"><code>" + ReportWriter.html(new String(content, StandardCharsets.UTF_8))
                + "</code></pre>";
        String html = """
                <!doctype html><html lang="hu"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Git blob %s</title><link rel="stylesheet" href="../../style.css"></head><body><main class="snapshot-blob">
                <header><p class="eyebrow">OFFLINE GIT SNAPSHOT</p><h1><code>%s</code></h1><p class="muted">Git blob: <code>%s</code> · %s bájt</p></header>%s
                </main></body></html>
                """.formatted(ReportWriter.attr(shortHash(hash)), ReportWriter.html(originalPath),
                ReportWriter.html(hash), content.length, body);
        Path target = directory.resolve(hash + ".html");
        Files.writeString(target, html, StandardCharsets.UTF_8);
        return Files.size(target);
    }

    private static String viewerHtml() {
        return """
                <!doctype html><html lang="hu"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>Offline Git forráskód-snapshot</title><link rel="stylesheet" href="style.css"></head><body><main>
                <nav><a href="dashboard.html">← Dashboard</a> · <a href="index.html">Összesítő</a></nav>
                <header><p class="eyebrow">OFFLINE GIT SNAPSHOT</p><h1 id="snapshot-title">Forráskód-pillanatkép</h1><p id="snapshot-meta" class="muted"></p></header>
                <p id="snapshot-error" class="empty-chart" hidden></p>
                <section id="snapshot-browser" class="snapshot-browser" hidden>
                  <aside class="snapshot-files"><label>Fájl keresése<input id="snapshot-search" type="search" placeholder="pl. src/Main.java"></label><p id="snapshot-count" class="muted"></p><div id="snapshot-list"></div></aside>
                  <article class="snapshot-preview"><h2 id="snapshot-path">Válassz fájlt</h2><iframe id="snapshot-frame" title="Snapshot forrásfájl"></iframe></article>
                </section></main><script src="snapshot-data.js"></script><script>
                (()=>{
                  const params=new URLSearchParams(location.search), repository=params.get('repository'), hash=params.get('commit');
                  const byId=id=>document.getElementById(id), all=window.GIT_SNAPSHOT_DATA && window.GIT_SNAPSHOT_DATA.repositories;
                  const repo=all && all[repository], fail=message=>{byId('snapshot-error').textContent=message;byId('snapshot-error').hidden=false;};
                  if(!repo||!hash||!repo.commits[hash]){fail('A kért offline snapshot nem található.');return;}
                  const chain=[];let current=hash;const visited=new Set();
                  while(current){if(visited.has(current)||!repo.commits[current]){fail('A snapshot előzménylánca hiányos.');return;}visited.add(current);const entry=repo.commits[current];chain.push(entry);current=entry.p;}
                  const tree=new Map();chain.reverse().forEach(entry=>entry.c.forEach(change=>change[1]==null?tree.delete(change[0]):tree.set(change[0],change[1])));
                  const files=[...tree.entries()].sort((a,b)=>a[0].localeCompare(b[0],'hu'));
                  byId('snapshot-title').textContent=repository+' · '+hash.slice(0,12);byId('snapshot-meta').textContent=hash+' · '+files.length+' követett forrásfájl/blob';byId('snapshot-browser').hidden=false;
                  const list=byId('snapshot-list'),search=byId('snapshot-search');
                  function openFile(path,blob,button){document.querySelectorAll('.snapshot-file.active').forEach(item=>item.classList.remove('active'));button.classList.add('active');byId('snapshot-path').textContent=path;byId('snapshot-frame').src='snapshots/blobs/'+blob+'.html';}
                  function render(){const query=search.value.trim().toLocaleLowerCase('hu'),visible=files.filter(file=>!query||file[0].toLocaleLowerCase('hu').includes(query));list.replaceChildren();visible.forEach(file=>{const button=document.createElement('button');button.type='button';button.className='snapshot-file';button.textContent=file[0];button.addEventListener('click',()=>openFile(file[0],file[1],button));list.appendChild(button);});byId('snapshot-count').textContent=visible.length+' / '+files.length+' fájl';if(visible.length&&list.firstChild)openFile(visible[0][0],visible[0][1],list.firstChild);}
                  search.addEventListener('input',render);render();
                })();
                </script></body></html>
                """;
    }

    private static String firstParent(String parents) {
        if (parents == null || parents.isBlank()) return "";
        int separator = parents.indexOf(' ');
        return separator < 0 ? parents.trim() : parents.substring(0, separator).trim();
    }

    private static String shortHash(String hash) {
        return hash.substring(0, Math.min(12, hash.length()));
    }

    private static boolean isBinary(byte[] content) {
        int sample = Math.min(content.length, 8192);
        for (int index = 0; index < sample; index++) if (content[index] == 0) return true;
        return false;
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b"); case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (character < 0x20) out.append("\\u%04x".formatted((int) character)); else out.append(character); }
            }
        }
        return out.append('"').toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    record SnapshotStats(long bytes, int blobs, long commits) { }

    @FunctionalInterface
    interface SnapshotProgress {
        void update(long completed, long total, String repository, String commit, int changes, int blobs,
                    String activity);
    }

    private static final class GitBlobBatch implements AutoCloseable {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedInputStream output;
        private final ByteArrayOutputStream error = new ByteArrayOutputStream();
        private final Thread errorReader;

        GitBlobBatch(Path repository) throws IOException {
            process = new ProcessBuilder("git", "-c", "core.quotepath=false", "-C", repository.toString(),
                    "cat-file", "--batch").start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedInputStream(process.getInputStream(), 1024 * 1024);
            errorReader = Thread.ofVirtual().start(() -> {
                try { process.getErrorStream().transferTo(error); } catch (IOException ignored) { }
            });
        }

        byte[] read(String object) throws IOException {
            input.write(object); input.newLine(); input.flush();
            String header = readLine(output);
            if (header == null || header.endsWith(" missing")) return null;
            int separator = header.lastIndexOf(' ');
            if (separator < 0) throw new IOException("Ismeretlen git cat-file válasz: " + header);
            long size = Long.parseLong(header.substring(separator + 1));
            if (size > Integer.MAX_VALUE) throw new IOException("Túl nagy Git-blob: " + object);
            byte[] bytes = output.readNBytes((int) size);
            if (bytes.length != size || output.read() != '\n') throw new IOException("Csonka Git-blob: " + object);
            return bytes;
        }

        private static String readLine(BufferedInputStream input) throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream(128);
            int value;
            while ((value = input.read()) != -1 && value != '\n') if (value != '\r') line.write(value);
            return value == -1 && line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() throws IOException, InterruptedException {
            try { input.close(); output.close(); }
            finally { process.destroy(); process.waitFor(); errorReader.join(); }
            if (process.exitValue() != 0 && error.size() > 0)
                throw new IOException("git cat-file: " + error.toString(StandardCharsets.UTF_8).trim());
        }
    }
}
