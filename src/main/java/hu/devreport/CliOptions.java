package hu.devreport;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/** Annotation-based command-line configuration powered by picocli. */
@Command(
        name = "git-contributor-report",
        version = "Git Contributor Report 1.0.0-SNAPSHOT",
        description = {
                "Részletes Git fejlesztői HTML-riportot és hordozható SQLite adatbázist készít.",
                "A --root alatt rekurzívan felismeri a normál, bare és worktree repókat; az összes",
                "helyi és remote ref által elérhető commitot elemzi. Interaktív felület: --interactive."
        },
        sortOptions = false,
        synopsisHeading = "@|bold,cyan Használat:|@%n",
        optionListHeading = "%n@|bold,cyan Opciók:|@%n",
        footerHeading = "%n@|bold,cyan Részletes használat és működés:|@%n",
        footer = {
                "@|bold Bemenet és elemzés|@",
                "  A program a --root könyvtár alatt rekurzívan keres Git repókat. A kimeneti",
                "  könyvtárat kihagyja. Egy repón belül ugyanazt a commitot csak egyszer számolja,",
                "  akkor is, ha több branchből elérhető. A .mailmap identitás-egyesítését használja.",
                "  A --since és --until a commitokra szűr. Nem commitolt módosítás nem kerül bele.",
                "",
                "@|bold Kimenetek|@",
                "  A report.sqlite a tartós, hordozható cache és a HTML-riport tömörített archívuma.",
                "  A HTML összesítő, dashboard, fejlesztő-, repó-, commit- és snapshotoldalakat tartalmaz.",
                "  A --render-db egy meglévő SQLite-fájlból Git-repó nélkül újra kiírja a HTML-t.",
                "  A --no-patches kihagyja a teljes diffeket.",
                "  A --quality PMD/CPD segítségével minősíti a hozzáadott Java, SQL, HTML, JavaScript, TypeScript és PL/SQL kódot.",
                "  Ez történelmi commitonként forráselemzést futtat, ezért nagy repón lassú lehet.",
                "",
                "@|bold Branchek és frissítés|@",
                "  A riport minden helyben elérhető lokális és remote ref commitjait egyben elemzi.",
                "  --fetch repónként git fetch --all --prune parancsot futtat. Nem checkoutol vagy",
                "  merge-el, de frissíti a remote-tracking refeket/FETCH_HEAD-et és törölheti a már",
                "  nem létező remote ágak követő refjeit.",
                "",
                "@|bold Értelmezés és adatvédelem|@",
                "  A statisztikák leíró aktivitási adatok, nem önmagukban használható teljesítménypontok.",
                "  A riport neveket, e-mail-címeket, commitüzeneteket, diffeket és forráskódot is",
                "  tartalmazhat, ezért bizalmas fejlesztési adatként kezelendő.",
                "",
                "@|bold Példák|@",
                "  Teljes képernyős interaktív felület:",
                "  @|bold git-contributor-report --interactive|@",
                "",
                "  Példa dátumtartományra:",
                "  @|bold git-contributor-report --root . --since 2026-01-01 --until 2026-12-31|@",
                "",
                "  Csak a 2026. január 1-jétől kezdődő aktivitás:",
                "  @|bold git-contributor-report --root . --since 2026-01-01|@",
                "",
                "  Remote branchek frissítése az elemzés előtt:",
                "  @|bold git-contributor-report --root . --fetch|@",
                "",
                "  HTML visszaállítása hordozható adatbázisból:",
                "  @|bold git-contributor-report --render-db report.sqlite --output report|@",
                "",
                "  Az interaktív felületen F1 nyitja meg a témakörös, görgethető súgót."
        }
)
final class CliOptions implements Callable<Integer> {
    @Option(names = "--root", paramLabel = "<útvonal>",
            description = {"Git repókat tartalmazó gyökérkönyvtár.", "Alapérték: ${DEFAULT-VALUE}"})
    Path root = Path.of(".");

    @Option(names = "--output", paramLabel = "<útvonal>",
            description = {"Kimeneti könyvtár.", "Alapérték: ${DEFAULT-VALUE}"})
    Path output = Path.of("report");

    @Option(names = "--database", paramLabel = "<fájl>",
            description = "SQLite riportadatbázis. Alapérték: <output>/report.sqlite.")
    Path database;

    @Option(names = "--render-db", paramLabel = "<fájl>",
            description = "A HTML-riport újragenerálása egy korábbi report.sqlite fájlból, Git-elemzés nélkül.")
    Path renderDatabase;

    @Option(names = "--since", paramLabel = "<dátum>",
            description = {"Git által elfogadott alsó időhatár.", "Ajánlott formátum: ÉÉÉÉ-HH-NN, például 2026-01-01."})
    String since;

    @Option(names = "--until", paramLabel = "<dátum>",
            description = {"Git által elfogadott felső időhatár.", "Ajánlott formátum: ÉÉÉÉ-HH-NN, például 2026-12-31."})
    String until;

    @Option(names = "--title", paramLabel = "<szöveg>",
            description = {"A riport címe.", "Alapérték: ${DEFAULT-VALUE}"})
    String title = "Git fejlesztői közreműködés";

    @Option(names = "--no-patches", description = "A teljes commit diffek kihagyása.")
    boolean noPatches;
    boolean includePatches = true;

    @Option(names = "--fetch",
            description = "Elemzés előtt repónként: git fetch --all --prune.")
    boolean fetch;

    @Option(names = "--quality",
            description = "PMD/CPD commitminősítés Java, SQL, HTML, JavaScript, TypeScript és PL/SQL kódra.")
    boolean qualityAnalysis;

    @Option(names = {"-i", "--interactive"},
            description = "Midnight Commander-stílusú teljes képernyős terminálfelület.")
    boolean interactive;

    @Option(names = {"-h", "--help"}, usageHelp = true, description = "A súgó megjelenítése.")
    boolean helpRequested;

    @Option(names = {"-V", "--version"}, versionHelp = true, description = "A verzió megjelenítése.")
    boolean versionRequested;

    @Override
    public Integer call() throws Exception {
        if (interactive) return InteractiveConsole.run(this);
        normalize();
        GitReportApplication.run(this);
        return CommandLine.ExitCode.OK;
    }

    static int execute(String[] args) {
        CommandLine commandLine = commandLine(new CliOptions());
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            command.getErr().println("Végzetes hiba: " + exception.getMessage());
            if (System.getenv("DEV_REPORT_DEBUG") != null) exception.printStackTrace(command.getErr());
            return CommandLine.ExitCode.SOFTWARE;
        });
        return commandLine.execute(args);
    }

    static CliOptions parse(String[] args) {
        CliOptions options = new CliOptions();
        commandLine(options).parseArgs(args);
        options.normalize();
        return options;
    }

    static String helpText() {
        return commandLine(new CliOptions()).getUsageMessage();
    }

    private static CommandLine commandLine(CliOptions options) {
        CommandLine.Help.Ansi ansi = ConsoleOutput.supportsAnsi()
                ? CommandLine.Help.Ansi.ON
                : CommandLine.Help.Ansi.OFF;
        return new CommandLine(options)
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setColorScheme(CommandLine.Help.defaultColorScheme(ansi))
                .setOut(ConsoleOutput.out())
                .setErr(ConsoleOutput.err());
    }

    void normalize() {
        includePatches = !noPatches;
    }

    static String formatByteSize(long bytes) {
        if (bytes == 0) return "0 B";
        if (bytes % (1024L * 1024L * 1024L) == 0) return bytes / (1024L * 1024L * 1024L) + " GB";
        if (bytes % (1024L * 1024L) == 0) return bytes / (1024L * 1024L) + " MB";
        if (bytes % 1024L == 0) return bytes / 1024L + " KB";
        return bytes + " B";
    }

}
