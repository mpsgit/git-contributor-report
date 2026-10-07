package hu.devreport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportWriterTest {
    @Test
    void escapesHtmlAndMarkdownMetadata() {
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", ReportWriter.html("<script>&\"'"));
        assertEquals("oszlop\\|érték  folytatás", ReportWriter.md("oszlop|érték\r\nfolytatás"));
        assertEquals("'kód'", ReportWriter.mdCode("`kód`"));
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
    void mapsCommonSourceExtensionsToMarkdownLanguages() {
        assertEquals("java", ReportWriter.languageFor("src/Main.java"));
        assertEquals("typescript", ReportWriter.languageFor("web/app.tsx"));
        assertEquals("powershell", ReportWriter.languageFor("run.ps1"));
        assertEquals("yaml", ReportWriter.languageFor(".github/workflows/build.yml"));
        assertEquals("text", ReportWriter.languageFor("LICENSE"));
        assertTrue(ReportWriter.languageFor("config.json").contains("json"));
    }
}
