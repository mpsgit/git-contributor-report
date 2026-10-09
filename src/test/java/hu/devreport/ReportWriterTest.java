package hu.devreport;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {
    @Test
    void convertsCommonGithubRemoteUrlsToBrowserLinks() {
        assertEquals("https://github.com/mpsgit/maps-business",
                ReportWriter.githubWebUrl("git@github.com:mpsgit/maps-business.git"));
        assertEquals("https://github.com/mpsgit/git-contributor-report",
                ReportWriter.githubWebUrl("ssh://git@ssh.github.com:443/mpsgit/git-contributor-report.git"));
        assertEquals("https://github.com/mpsgit/maps-business",
                ReportWriter.githubWebUrl("https://github.com/mpsgit/maps-business.git"));
        assertEquals("", ReportWriter.githubWebUrl("https://gitlab.example.test/team/repo.git"));
    }

    @Test
    void escapesHtmlMetadata() {
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", ReportWriter.html("<script>&\"'"));
        assertEquals("k&oacute;d&#32;&#32;\n&#32;", ReportWriter.htmlCode("kód  \n "));
        assertEquals("", ReportWriter.html(null));
    }

    @Test
    void createsStableFilesystemSafeSlugs() {
        assertEquals("arvizturo-tukorfurogep", ReportWriter.slug("Árvíztűrő tükörfúrógép"));
        assertEquals("feature-login-api", ReportWriter.slug("feature/login API"));
        assertEquals("item", ReportWriter.slug("---"));
        assertFalse(ReportWriter.slug("név").contains("é"));
    }

    @Test
    void mapsCommonSourceExtensionsToSyntaxLanguages() {
        assertEquals("java", ReportWriter.languageFor("src/Main.java"));
        assertEquals("typescript", ReportWriter.languageFor("web/app.tsx"));
        assertEquals("powershell", ReportWriter.languageFor("run.ps1"));
        assertEquals("yaml", ReportWriter.languageFor(".github/workflows/build.yml"));
        assertEquals("text", ReportWriter.languageFor("LICENSE"));
        assertTrue(ReportWriter.languageFor("config.json").contains("json"));
    }

    @Test
    void buildsContinuousDailyWeeklyAndMonthlyCodeActivitySeries() {
        Developer developer = new Developer("mail:chart@test.invalid", "Grafikon Teszt");
        ContributionStats first = stats(2, 3, 10, 2);
        ContributionStats second = stats(1, 2, 0, 4);
        ContributionStats third = stats(3, 5, 8, 1);
        developer.daily.put(LocalDate.of(2026, 1, 1), first);
        developer.daily.put(LocalDate.of(2026, 1, 10), second);
        developer.daily.put(LocalDate.of(2026, 2, 2), third);
        developer.monthly.put(YearMonth.of(2026, 1), stats(3, 5, 10, 6));
        developer.monthly.put(YearMonth.of(2026, 2), third);

        String charts = ReportWriter.activityCharts(developer);

        assertTrue(charts.contains("activity-daily"));
        assertTrue(charts.contains("activity-weekly"));
        assertTrue(charts.contains("activity-monthly"));
        assertTrue(charts.contains("\"2026-01-01\",\"2026-01-02\""));
        assertTrue(charts.contains("\"2026-W01\""));
        assertTrue(charts.contains("\"2026-01\",\"2026-02\""));
        assertTrue(charts.contains("\"additions\":[10,0"));
        assertTrue(charts.contains("\"deletions\":[2,0"));
        assertTrue(charts.contains("Összes módosított sor"));
        assertTrue(charts.contains("echarts.init"));
        assertTrue(charts.contains("dataZoom"));
    }

    @Test
    void explainsWhenAContributorHasNoAuthoredCodeSeries() {
        String charts = ReportWriter.activityCharts(new Developer("mail:role@test.invalid", "Csak Társszerző"));

        assertTrue(charts.contains("nincs szerzőként rögzített"));
        assertFalse(charts.contains("echarts.min.js"));
    }

    @Test
    void buildsFilterablePortfolioDashboardWithQualityAndBranchData() {
        Analysis analysis = new Analysis(Path.of("projects"), Path.of("patches"));
        analysis.developer("Ágnes Fejlesztő", "agnes@test.invalid");
        RepositoryStats repository = new RepositoryStats("project-one", Path.of("projects/project-one"), "", 2);
        CommitSummary commit = new CommitSummary("1234567890abcdef", "", "project-one",
                Instant.parse("2026-02-03T10:15:30Z"), "feat: dashboard", "feat: dashboard",
                "Ágnes Fejlesztő", "agnes@test.invalid", "Ágnes Fejlesztő", "agnes@test.invalid",
                2, 25, 4, false, List.of(), null);
        commit.branches.add("main");
        commit.quality = new QualityAssessment(QualityAssessment.Status.COMPLETE, 86, "B", "jó",
                1, 25, Map.of("Java", 1), List.of(), List.of());
        repository.commits.add(commit);
        analysis.repositories.add(repository);

        String dashboard = ReportWriter.portfolioCharts(analysis);

        assertTrue(dashboard.contains("dashboard-from"));
        assertTrue(dashboard.contains("dashboard-to"));
        assertTrue(dashboard.contains("dashboard-developers"));
        assertTrue(dashboard.contains("dashboard-grouping"));
        assertTrue(dashboard.contains("dashboard-scale"));
        assertTrue(dashboard.contains("Logaritmikus (10-es)"));
        assertTrue(dashboard.contains("type:logarithmic?'log':'value'"));
        assertTrue(dashboard.contains("min:logarithmic?1:0"));
        assertTrue(dashboard.contains("Fejlesztőnként"));
        assertTrue(dashboard.contains("developerPalette"));
        assertTrue(dashboard.contains("dashboard-developer-key"));
        assertTrue(dashboard.contains("developerColors"));
        assertTrue(dashboard.contains("developer+' · + sor'"));
        assertTrue(dashboard.contains("dashboard-repositories"));
        assertTrue(dashboard.contains("dashboard-branches"));
        assertTrue(dashboard.contains("PMD/CPD score"));
        assertTrue(dashboard.contains("project-one :: main"));
        assertTrue(dashboard.contains("\"q\":86"));
        assertTrue(dashboard.contains("Ágnes Fejlesztő"));
        assertTrue(dashboard.contains("echarts.init"));
        assertTrue(dashboard.contains("saveAsImage"));
        assertTrue(dashboard.contains("dashboard-drilldown"));
        assertTrue(dashboard.contains("showDrilldown"));
        assertTrue(dashboard.contains("Mit jelent a PMD, a CPD"));
        assertTrue(dashboard.contains("\"h\":\"1234567890abcdef\""));
        assertTrue(dashboard.contains("\"p\":\"commits/project-one-1234567890ab.html\""));
    }

    private static ContributionStats stats(long commits, long files, long additions, long deletions) {
        ContributionStats stats = new ContributionStats();
        stats.commits = commits;
        stats.files = files;
        stats.additions = additions;
        stats.deletions = deletions;
        return stats;
    }
}
