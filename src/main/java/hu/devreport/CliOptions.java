package hu.devreport;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Annotation-based command-line configuration powered by picocli. */
@Command(
        name = "git-contributor-report",
        version = "Git Contributor Report 1.0.0-SNAPSHOT",
        description = {
                "Részletes Git fejlesztői riportokat és branchenkénti forráskód-Markdownokat készít.",
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
                "  A --since és --until a commitokra szűr; a forrás-export mindig a kiválasztott",
                "  branch/ref teljes, commitolt pillanatképe. Nem commitolt módosítás nem kerül bele.",
                "",
                "@|bold Kimenetek|@",
                "  html: böngészhető összesítő, fejlesztő-, repó- és commitoldalak.",
                "  markdown: index és fejlesztőnkénti, repónkénti, commitonkénti MD fájlok.",
                "  source: kiválasztott branchenként a teljes commitolt forráskód egy Markdownban.",
                "  A --no-patches kihagyja a teljes diffeket. A --max-md-size túllépésekor számozott",
                "  .part-001.md részek készülnek; 0 esetén nincs méretkorlát.",
                "",
                "@|bold Branchek és frissítés|@",
                "  --source-branches all|local|remote vagy pontos, vesszővel tagolt branchlista.",
                "  --source-ref egyetlen branchet, taget vagy commitot választ, és felülírja a listát.",
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
                "  Kiválasztott branchek forráskóda:",
                "  @|bold git-contributor-report --root . --source-only --source-branches main,develop|@",
                "",
                "  Minden branch forráskódja:",
                "  @|bold git-contributor-report --root . --source-only --source-branches all|@",
                "",
                "  500 MB-os Markdown-részek:",
                "  @|bold git-contributor-report --root . --max-md-size 500MB|@",
                "",
                "  Az interaktív felületen F1 nyitja meg a témakörös, görgethető súgót."
        }
)
final class CliOptions implements Callable<Integer> {
    private static final long MIN_MARKDOWN_PART_SIZE = 16 * 1024;
    private static final Pattern BYTE_SIZE = Pattern.compile(
            "(?i)^\\s*(\\d+(?:[.,]\\d+)?)\\s*(B|KB|KIB|MB|MIB|GB|GIB)?\\s*$");
    private static final Set<String> OUTPUT_NAMES = Set.of("html", "markdown", "source");
    private static final Set<String> BRANCH_SCOPES = Set.of("all", "local", "remote");

    @Option(names = "--root", paramLabel = "<útvonal>",
            description = {"Git repókat tartalmazó gyökérkönyvtár.", "Alapérték: ${DEFAULT-VALUE}"})
    Path root = Path.of(".");

    @Option(names = "--output", paramLabel = "<útvonal>",
            description = {"Kimeneti könyvtár.", "Alapérték: ${DEFAULT-VALUE}"})
    Path output = Path.of("report");

    @Option(names = "--since", paramLabel = "<dátum>",
            description = {"Git által elfogadott alsó időhatár.", "Ajánlott formátum: ÉÉÉÉ-HH-NN, például 2026-01-01."})
    String since;

    @Option(names = "--until", paramLabel = "<dátum>",
            description = {"Git által elfogadott felső időhatár.", "Ajánlott formátum: ÉÉÉÉ-HH-NN, például 2026-12-31."})
    String until;

    @Option(names = "--title", paramLabel = "<szöveg>",
            description = {"A riport címe.", "Alapérték: ${DEFAULT-VALUE}"})
    String title = "Git fejlesztői közreműködés";

    @Option(names = "--outputs", split = ",", converter = OutputConverter.class, paramLabel = "<lista>",
            description = {"Kimenetek: html, markdown, source.", "Alapérték: ${DEFAULT-VALUE}"})
    Set<String> outputs = new LinkedHashSet<>(List.of("html", "markdown", "source"));

    @Option(names = {"--max-md-size", "--max-markdown-size"}, converter = ByteSizeConverter.class,
            paramLabel = "<méret>",
            description = {"Markdown-fájlonkénti méretkorlát; túllépéskor számozott részek készülnek.",
                    "Példák: 500KB, 10MB, 1.5GB; 0 = korlátlan. Alapérték: ${DEFAULT-VALUE}"})
    long maxMarkdownBytes;

    @Option(names = "--no-patches", description = "A teljes commit diffek kihagyása.")
    boolean noPatches;
    boolean includePatches = true;

    @Option(names = "--fetch",
            description = "Elemzés előtt repónként: git fetch --all --prune.")
    boolean fetch;

    @Option(names = "--source-ref", paramLabel = "<ref>",
            description = "Egyetlen branch, tag vagy commit; felülírja a --source-branches beállítást.")
    void sourceRef(String value) {
        sourceRef = value;
        sourceRefExplicit = true;
    }

    String sourceRef = "HEAD";
    boolean sourceRefExplicit;

    @Option(names = "--source-branches", split = ",", converter = BranchSelectorConverter.class,
            paramLabel = "<all|lista>",
            description = {"all, local, remote vagy pontos branch-nevek vesszővel elválasztva.",
                    "Példa: main,develop,origin/release. Alapérték: ${DEFAULT-VALUE}"})
    Set<String> sourceBranches = new LinkedHashSet<>(List.of("all"));

    @Option(names = "--source-only", description = "Rövidítés: --outputs source.")
    boolean sourceOnly;

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
        if (sourceOnly) {
            outputs.clear();
            outputs.add("source");
        }
    }

    static final class OutputConverter implements ITypeConverter<String> {
        @Override
        public String convert(String value) {
            String normalized = value.toLowerCase(Locale.ROOT).trim();
            if (!OUTPUT_NAMES.contains(normalized)) {
                throw new CommandLine.TypeConversionException(
                        "ismeretlen kimenet: " + value + " (html, markdown vagy source)");
            }
            return normalized;
        }
    }

    static final class BranchSelectorConverter implements ITypeConverter<String> {
        @Override
        public String convert(String value) {
            String branch = value.trim();
            if (branch.isEmpty()) throw new CommandLine.TypeConversionException("a branch-név nem lehet üres");
            String normalized = branch.toLowerCase(Locale.ROOT);
            return BRANCH_SCOPES.contains(normalized) ? normalized : branch;
        }
    }

    static long parseByteSize(String value) {
        Matcher matcher = BYTE_SIZE.matcher(value == null ? "" : value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("hibás méret: " + value + " (például 10MB vagy 500KB)");
        }
        BigDecimal amount = new BigDecimal(matcher.group(1).replace(',', '.'));
        String unit = matcher.group(2) == null ? "B" : matcher.group(2).toUpperCase(Locale.ROOT);
        long multiplier = switch (unit) {
            case "KB", "KIB" -> 1024L;
            case "MB", "MIB" -> 1024L * 1024L;
            case "GB", "GIB" -> 1024L * 1024L * 1024L;
            default -> 1L;
        };
        final long bytes;
        try {
            bytes = amount.multiply(BigDecimal.valueOf(multiplier)).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("a megadott méret túl nagy vagy nem egész bájtra adódik: " + value);
        }
        if (bytes < 0 || (bytes > 0 && bytes < MIN_MARKDOWN_PART_SIZE)) {
            throw new IllegalArgumentException("a Markdown-méretkorlát legalább 16KB legyen, vagy 0 a korlátlan módhoz");
        }
        return bytes;
    }

    static String formatByteSize(long bytes) {
        if (bytes == 0) return "korlátlan";
        if (bytes % (1024L * 1024L * 1024L) == 0) return bytes / (1024L * 1024L * 1024L) + " GB";
        if (bytes % (1024L * 1024L) == 0) return bytes / (1024L * 1024L) + " MB";
        if (bytes % 1024L == 0) return bytes / 1024L + " KB";
        return bytes + " B";
    }

    static final class ByteSizeConverter implements ITypeConverter<Long> {
        @Override
        public Long convert(String value) {
            try {
                return parseByteSize(value);
            } catch (IllegalArgumentException exception) {
                throw new CommandLine.TypeConversionException(exception.getMessage());
            }
        }
    }
}
