package hu.devreport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class IdentityResolutionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void combinesEqualNormalizedNamesWithDifferentEmails() {
        Analysis analysis = new Analysis(temporaryDirectory, temporaryDirectory.resolve("patches"));

        Developer first = analysis.developer("Teszt Elek", "first@test.invalid");
        Developer second = analysis.developer("  TESZT   ELEK ", "second@test.invalid");

        assertSame(first, second);
        assertEquals(1, analysis.developers.size());
        assertEquals(2, first.emails.size());
        assertEquals(2, first.names.size());
        assertEquals("Teszt Elek", first.displayName);
    }

    @Test
    void ignoresCaseWhitespaceAndAccentDifferencesInNames() {
        Analysis analysis = new Analysis(temporaryDirectory, temporaryDirectory.resolve("patches"));

        Developer accented = analysis.developer("Árvíz Tűrő", "accented@test.invalid");
        Developer compact = analysis.developer("arvizturo", "compact@test.invalid");
        Developer spacedUppercase = analysis.developer("  ARVIZ   TURO  ", "spaced@test.invalid");

        assertSame(accented, compact);
        assertSame(accented, spacedUppercase);
        assertEquals(1, analysis.developers.size());
        assertEquals(3, accented.names.size());
        assertEquals(3, accented.emails.size());
        assertEquals("Árvíz Tűrő", accented.displayName);
        assertEquals("arvizturo", Analysis.normalizeName("ÁRVÍZ  TŰRŐ"));
    }

    @Test
    void combinesDifferentNameAliasesWhenEmailIsEqual() {
        Analysis analysis = new Analysis(temporaryDirectory, temporaryDirectory.resolve("patches"));

        Developer first = analysis.developer("Első Álnév", "shared@test.invalid");
        Developer second = analysis.developer("Második Álnév", "SHARED@test.invalid");

        assertSame(first, second);
        assertEquals(1, analysis.developers.size());
        assertEquals(2, first.names.size());
    }

    @Test
    void keepsDifferentNamesAndEmailsSeparate() {
        Analysis analysis = new Analysis(temporaryDirectory, temporaryDirectory.resolve("patches"));

        Developer first = analysis.developer("Első Személy", "first@test.invalid");
        Developer second = analysis.developer("Második Személy", "second@test.invalid");

        assertNotSame(first, second);
        assertEquals(2, analysis.developers.size());
    }

    @Test
    void transitivelyCombinesNameAndEmailAliasChainsWithoutLosingStatistics() {
        Analysis analysis = new Analysis(temporaryDirectory, temporaryDirectory.resolve("patches"));
        Developer first = analysis.developer("Név A", "first@test.invalid");
        Developer second = analysis.developer("Név B", "second@test.invalid");
        first.authoredCommits = 2;
        second.authoredCommits = 3;
        ContributionStats firstStats = new ContributionStats();
        firstStats.commits = 2;
        ContributionStats secondStats = new ContributionStats();
        secondStats.commits = 3;
        first.byRepository.put("repo", firstStats);
        second.byRepository.put("repo", secondStats);
        RepositoryStats repository = new RepositoryStats("repo", temporaryDirectory, "", 0);
        repository.authors.add(first.key);
        repository.authors.add(second.key);
        repository.byDeveloper.put(first.key, firstStats);
        repository.byDeveloper.put(second.key, secondStats);
        analysis.repositories.add(repository);

        Developer combined = analysis.developer("Név A", "second@test.invalid");

        assertEquals(1, analysis.developers.size());
        assertEquals(5, combined.authoredCommits);
        assertEquals(2, combined.names.size());
        assertEquals(2, combined.emails.size());
        assertEquals(1, repository.authors.size());
        assertEquals(1, repository.byDeveloper.size());
        assertEquals(5, repository.byDeveloper.get(combined.key).commits);
    }
}
