package hu.devreport;

import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Package-private domain model shared by analysis and output components. */
final class CommandResult {
    final int exitCode; final String stdout; final String stderr;
    CommandResult(int exitCode, String stdout, String stderr) {
        this.exitCode = exitCode; this.stdout = stdout; this.stderr = stderr;
    }
}

final class Commit {
    final String hash, authorName, authorEmail, committerName, committerEmail, parents, message, subject;
    final Instant authorDate, committerDate;
    final long additions, deletions;
    final int files;
    final Map<String, FileDelta> fileTypes;
    final List<FileChange> fileChanges;

    Commit(String hash, String authorName, String authorEmail, Instant authorDate,
           String committerName, String committerEmail, Instant committerDate,
           String parents, String message, String subject, long additions, long deletions, int files,
           Map<String, FileDelta> fileTypes, List<FileChange> fileChanges) {
        this.hash = hash; this.authorName = authorName; this.authorEmail = authorEmail; this.authorDate = authorDate;
        this.committerName = committerName; this.committerEmail = committerEmail; this.committerDate = committerDate;
        this.parents = parents; this.message = message; this.subject = subject; this.additions = additions;
        this.deletions = deletions; this.files = files; this.fileTypes = fileTypes; this.fileChanges = fileChanges;
    }
}

final class BranchRef {
    final String fullName, shortName; final boolean remote;
    BranchRef(String fullName, String shortName, boolean remote) {
        this.fullName = fullName; this.shortName = shortName; this.remote = remote;
    }
    String shortName() { return shortName; }
}

final class FileChange {
    final String path; final long additions, deletions; final boolean binary;
    FileChange(String path, long additions, long deletions, boolean binary) {
        this.path = path; this.additions = additions; this.deletions = deletions; this.binary = binary;
    }
}

final class ChangedCode {
    final String path, added, deleted;
    ChangedCode(String path, String added, String deleted) {
        this.path = path; this.added = added; this.deleted = deleted;
    }
    String added() { return added; }
    String deleted() { return deleted; }
}

final class TreeEntry {
    final String mode, type, hash, path; final long size;
    TreeEntry(String mode, String type, String hash, long size, String path) {
        this.mode = mode; this.type = type; this.hash = hash; this.size = size; this.path = path;
    }
    String mode() { return mode; } String hash() { return hash; } long size() { return size; } String path() { return path; }
}

final class SourceBranch {
    final Path repository; final String repositoryName, fullRef, branchName, commit;
    SourceBranch(Path repository, String repositoryName, String fullRef, String branchName, String commit) {
        this.repository = repository; this.repositoryName = repositoryName; this.fullRef = fullRef;
        this.branchName = branchName; this.commit = commit;
    }
    Path repository() { return repository; } String repositoryName() { return repositoryName; }
    String fullRef() { return fullRef; } String branchName() { return branchName; } String commit() { return commit; }
}

final class SourceCodeStats {
    final int repositories; final long textFiles, binaryFiles, bytes;
    SourceCodeStats(int repositories, long textFiles, long binaryFiles, long bytes) {
        this.repositories = repositories; this.textFiles = textFiles; this.binaryFiles = binaryFiles; this.bytes = bytes;
    }
    long textFiles() { return textFiles; } long binaryFiles() { return binaryFiles; } long bytes() { return bytes; }
}

final class CommitSummary {
    final String hash, repository, subject, message, authorName, authorEmail, committerName, committerEmail;
    final Instant date;
    final int files;
    final long additions, deletions;
    final boolean merge;
    final List<FileChange> fileChanges;
    final PatchRef patch;

    CommitSummary(String hash, String repository, Instant date, String subject, String message,
                  String authorName, String authorEmail, String committerName, String committerEmail,
                  int files, long additions, long deletions, boolean merge,
                  List<FileChange> fileChanges, PatchRef patch) {
        this.hash = hash; this.repository = repository; this.date = date; this.subject = subject; this.message = message;
        this.authorName = authorName; this.authorEmail = authorEmail; this.committerName = committerName;
        this.committerEmail = committerEmail; this.files = files; this.additions = additions;
        this.deletions = deletions; this.merge = merge; this.fileChanges = fileChanges; this.patch = patch;
    }
    Instant date() { return date; }
}

final class PatchRef {
    final Path file;
    long offset;
    long length;

    PatchRef(Path file) { this.file = file; }
}

final class Analysis {
    final Path root;
    final Path patchRoot;
    final Map<String, PatchRef> patchRefs = new HashMap<>();
    final Map<String, Developer> developers = new TreeMap<>();
    final Map<String, Developer> developersByEmail = new HashMap<>();
    final Map<String, Developer> developersByName = new HashMap<>();
    final List<RepositoryStats> repositories = new ArrayList<>();
    final Set<String> seenCommits = new HashSet<>();
    final List<String> warnings = new ArrayList<>();

    Analysis(Path root, Path patchRoot) {
        this.root = root;
        this.patchRoot = patchRoot;
    }

    Path patchPack(String repository) {
        String repoId = Integer.toUnsignedString(repository.hashCode(), 36);
        return patchRoot.resolve(repoId + ".pack");
    }

    PatchRef patchRef(String repository, String hash) {
        return patchRefs.computeIfAbsent(repository + "|" + hash,
                ignored -> new PatchRef(patchPack(repository)));
    }

    Developer developer(String name, String email) {
        String cleanEmail = normalizeEmail(email);
        String cleanName = cleanName(name);
        String normalizedName = normalizeName(cleanName);
        Developer emailMatch = cleanEmail.isBlank() ? null : developersByEmail.get(cleanEmail);
        Developer nameMatch = developersByName.get(normalizedName);
        Developer developer;
        if (emailMatch != null && nameMatch != null && emailMatch != nameMatch) {
            developer = mergeDevelopers(emailMatch, nameMatch);
        } else {
            developer = emailMatch != null ? emailMatch : nameMatch;
        }
        if (developer == null) {
            String key = cleanEmail.isBlank() ? "name:" + normalizedName : "mail:" + cleanEmail;
            developer = new Developer(key, cleanName);
            developers.put(key, developer);
        }
        developer.preferDisplayName(cleanName);
        developer.names.add(cleanName);
        developersByName.put(normalizedName, developer);
        if (!cleanEmail.isBlank()) {
            developer.emails.add(cleanEmail);
            developersByEmail.put(cleanEmail, developer);
        }
        return developer;
    }

    private Developer mergeDevelopers(Developer target, Developer duplicate) {
        target.absorb(duplicate);
        developers.remove(duplicate.key);
        developersByEmail.replaceAll((alias, developer) -> developer == duplicate ? target : developer);
        developersByName.replaceAll((alias, developer) -> developer == duplicate ? target : developer);
        for (RepositoryStats repository : repositories) {
            boolean authored = repository.authors.remove(duplicate.key);
            if (authored) repository.authors.add(target.key);
            boolean participated = repository.roleParticipants.remove(duplicate.key);
            if (participated) repository.roleParticipants.add(target.key);
            ContributionStats duplicateStats = repository.byDeveloper.remove(duplicate.key);
            if (duplicateStats != null || repository.byDeveloper.containsKey(target.key)) {
                ContributionStats canonical = target.byRepository.get(repository.name);
                if (canonical != null) repository.byDeveloper.put(target.key, canonical);
            }
            for (BranchStats branch : repository.branchStats) {
                if (branch.authors.remove(duplicate.key)) branch.authors.add(target.key);
            }
        }
        return target;
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank()) return "Ismeretlen";
        return Normalizer.normalize(name, Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }

    static String normalizeName(String name) {
        return Normalizer.normalize(cleanName(name), Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : Normalizer.normalize(email, Normalizer.Form.NFKC)
                .trim().toLowerCase(Locale.ROOT);
    }
}

final class Developer {
    final String key;
    String displayName;
    final Set<String> names = new HashSet<>();
    final Set<String> emails = new HashSet<>();
    final Set<String> repositories = new HashSet<>();
    final Set<LocalDate> activeDays = new HashSet<>();
    final Set<LocalDate> participationDays = new HashSet<>();
    final Map<String, Long> roles = new TreeMap<>();
    final Map<String, ContributionStats> byRepository = new TreeMap<>();
    final Map<YearMonth, ContributionStats> monthly = new TreeMap<>();
    final Map<String, FileDelta> fileTypes = new TreeMap<>();
    final Map<String, Long> commitTypes = new TreeMap<>();
    final Set<String> branchRefs = new HashSet<>();
    final List<CommitSummary> commits = new ArrayList<>();
    final long[] weekdays = new long[7];
    final long[] hours = new long[24];
    long authoredCommits;
    long committedCommits;
    long mergeCommits;
    long nonMergeCommits;
    long additions;
    long deletions;
    long filesChanged;
    long issueLinkedCommits;
    Instant firstActivity;
    Instant lastActivity;

    Developer(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    void observe(Instant instant) {
        if (instant == null || instant.equals(Instant.EPOCH)) return;
        if (firstActivity == null || instant.isBefore(firstActivity)) firstActivity = instant;
        if (lastActivity == null || instant.isAfter(lastActivity)) lastActivity = instant;
    }

    void preferDisplayName(String candidate) {
        if (candidate == null || candidate.isBlank()) return;
        if (displayName.equals(displayName.toUpperCase(Locale.ROOT))
                || displayName.equals(displayName.toLowerCase(Locale.ROOT))) {
            boolean candidateHasUpper = !candidate.equals(candidate.toLowerCase(Locale.ROOT));
            boolean candidateHasLower = !candidate.equals(candidate.toUpperCase(Locale.ROOT));
            if (candidateHasUpper && candidateHasLower) displayName = candidate;
        }
    }

    void absorb(Developer other) {
        other.names.forEach(this::preferDisplayName);
        names.addAll(other.names);
        emails.addAll(other.emails);
        repositories.addAll(other.repositories);
        activeDays.addAll(other.activeDays);
        participationDays.addAll(other.participationDays);
        branchRefs.addAll(other.branchRefs);
        mergeLongMap(roles, other.roles);
        mergeLongMap(commitTypes, other.commitTypes);
        other.byRepository.forEach((name, stats) -> byRepository.merge(name, stats, (current, incoming) -> {
            current.merge(incoming);
            return current;
        }));
        other.monthly.forEach((month, stats) -> monthly.merge(month, stats, (current, incoming) -> {
            current.merge(incoming);
            return current;
        }));
        other.fileTypes.forEach((type, delta) -> fileTypes.merge(type, delta, (current, incoming) -> {
            current.merge(incoming);
            return current;
        }));
        Set<String> knownCommits = new HashSet<>();
        commits.forEach(commit -> knownCommits.add(commit.repository + "|" + commit.hash));
        for (CommitSummary commit : other.commits) {
            if (knownCommits.add(commit.repository + "|" + commit.hash)) commits.add(commit);
        }
        for (int index = 0; index < weekdays.length; index++) weekdays[index] += other.weekdays[index];
        for (int index = 0; index < hours.length; index++) hours[index] += other.hours[index];
        authoredCommits += other.authoredCommits;
        committedCommits += other.committedCommits;
        mergeCommits += other.mergeCommits;
        nonMergeCommits += other.nonMergeCommits;
        additions += other.additions;
        deletions += other.deletions;
        filesChanged += other.filesChanged;
        issueLinkedCommits += other.issueLinkedCommits;
        observe(other.firstActivity);
        observe(other.lastActivity);
    }

    private static void mergeLongMap(Map<String, Long> target, Map<String, Long> source) {
        source.forEach((key, value) -> target.merge(key, value, Long::sum));
    }

    long trailerCount(String role) { return roles.getOrDefault(role, 0L); }
}

final class RepositoryStats {
    final String name;
    final Path path;
    final String originUrl;
    final int branches;
    final Set<String> authors = new HashSet<>();
    final Set<String> roleParticipants = new HashSet<>();
    final List<BranchStats> branchStats = new ArrayList<>();
    final Map<String, ContributionStats> byDeveloper = new TreeMap<>();
    final Map<String, FileDelta> fileTypes = new TreeMap<>();
    final List<CommitSummary> commits = new ArrayList<>();
    final ContributionStats total = new ContributionStats();
    long reachableCommits;
    long uniqueCommits;

    RepositoryStats(String name, Path path, String originUrl, int branches) {
        this.name = name;
        this.path = path;
        this.originUrl = originUrl;
        this.branches = branches;
    }
}

final class ContributionStats {
    long commits;
    long committed;
    long merges;
    long files;
    long additions;
    long deletions;
    final Set<LocalDate> activeDays = new HashSet<>();
    final Set<String> branches = new HashSet<>();
    final Map<String, Long> roles = new TreeMap<>();
    Instant first;
    Instant last;

    void observe(Instant instant) {
        if (instant == null || instant.equals(Instant.EPOCH)) return;
        if (first == null || instant.isBefore(first)) first = instant;
        if (last == null || instant.isAfter(last)) last = instant;
    }

    void merge(ContributionStats other) {
        commits += other.commits;
        committed += other.committed;
        merges += other.merges;
        files += other.files;
        additions += other.additions;
        deletions += other.deletions;
        activeDays.addAll(other.activeDays);
        branches.addAll(other.branches);
        other.roles.forEach((role, count) -> roles.merge(role, count, Long::sum));
        observe(other.first);
        observe(other.last);
    }
}

final class FileDelta {
    long files;
    long additions;
    long deletions;

    void add(long additions, long deletions) {
        this.files++;
        this.additions += additions;
        this.deletions += deletions;
    }

    void merge(FileDelta other) {
        this.files += other.files;
        this.additions += other.additions;
        this.deletions += other.deletions;
    }
}

final class BranchStats {
    final String name;
    final boolean remote;
    final Set<String> authors = new HashSet<>();
    long commits;

    BranchStats(String name, boolean remote) {
        this.name = name;
        this.remote = remote;
    }
}
