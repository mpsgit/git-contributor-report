package hu.devreport;

import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.bundle.LanternaThemes;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Borders;
import com.googlecode.lanterna.gui2.Button;
import com.googlecode.lanterna.gui2.CheckBox;
import com.googlecode.lanterna.gui2.ComboBox;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.DefaultWindowManager;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.EmptySpace;
import com.googlecode.lanterna.gui2.GridLayout;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.ProgressBar;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.dialogs.MessageDialog;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.DirectoryDialogBuilder;
import com.googlecode.lanterna.gui2.menu.Menu;
import com.googlecode.lanterna.gui2.menu.MenuBar;
import com.googlecode.lanterna.gui2.menu.MenuItem;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import com.googlecode.lanterna.screen.TerminalScreen;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Midnight Commander-style full-screen terminal UI backed by Lanterna. */
final class InteractiveConsole {
    private static final TerminalSize FIELD_SIZE = new TerminalSize(26, 1);
    private static final TerminalSize LOG_SIZE = new TerminalSize(76, 4);

    private final CliOptions options;
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Interactable> controls = new ArrayList<>();

    private MultiWindowTextGUI gui;
    private BasicWindow window;
    private TextBox root;
    private TextBox output;
    private TextBox title;
    private TextBox since;
    private TextBox until;
    private CheckBox html;
    private CheckBox markdown;
    private CheckBox source;
    private CheckBox patches;
    private CheckBox fetch;
    private ComboBox<String> markdownLimit;
    private ComboBox<SourceMode> sourceMode;
    private TextBox sourceValue;
    private TextBox log;
    private Label status;
    private Label progressInfo;
    private ProgressBar progress;
    private Button generate;
    private Button reset;
    private ShortcutMenu fileMenu;
    private ShortcutMenu settingsMenu;
    private ShortcutMenu helpMenu;
    private Instant generationStarted;
    private String lastProgressPhase = "";
    private volatile ProgressUpdate lastProgressUpdate;

    private InteractiveConsole(CliOptions options) {
        this.options = options;
    }

    static int run(CliOptions options) throws Exception {
        options.normalize();
        new InteractiveConsole(options).open();
        return CommandLine.ExitCode.OK;
    }

    private void open() throws IOException {
        try (Screen screen = new TerminalScreen(JLineLanternaTerminal.create())) {
            screen.startScreen();
            gui = new MultiWindowTextGUI(screen, new DefaultWindowManager(),
                    new EmptySpace(TextColor.ANSI.BLUE));
            gui.setTheme(LanternaThemes.getRegisteredTheme("conqueror"));
            buildWindow();
            gui.addWindowAndWait(window);
        }
    }

    private void buildWindow() {
        window = new BasicWindow("Git Contributor Report") {
            @Override
            public boolean handleInput(KeyStroke keyStroke) {
                Character menuShortcut = menuShortcut(keyStroke);
                if (menuShortcut != null) {
                    switch (menuShortcut) {
                        case 'f' -> fileMenu.open();
                        case 'b' -> settingsMenu.open();
                        case 's' -> helpMenu.open();
                        default -> { return super.handleInput(keyStroke); }
                    }
                    return true;
                }
                if (keyStroke.getKeyType() == KeyType.F1) {
                    showHelp();
                    return true;
                }
                if (keyStroke.getKeyType() == KeyType.F2) {
                    startGeneration();
                    return true;
                }
                if (keyStroke.getKeyType() == KeyType.F4) {
                    resetForm();
                    return true;
                }
                if (keyStroke.getKeyType() == KeyType.F10) {
                    requestClose();
                    return true;
                }
                return super.handleInput(keyStroke);
            }
        };
        window.setHints(List.of(Window.Hint.FULL_SCREEN, Window.Hint.NO_DECORATIONS));
        window.setCloseWindowWithEscape(false);
        window.setMenuBar(menuBar());

        Panel rootPanel = new Panel(new LinearLayout(Direction.VERTICAL));
        rootPanel.addComponent(new Label(" Git Contributor Report  |  TAB: mezőváltás  SPACE: jelölés  ENTER: választás")
                .setForegroundColor(TextColor.ANSI.WHITE));

        Panel columns = new Panel(new GridLayout(2).setHorizontalSpacing(1));
        Panel paths = buildPathsPanel();
        Panel report = buildReportPanel();
        columns.addComponent(paths.withBorder(Borders.singleLine(" Könyvtárak és időszak ")),
                GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        columns.addComponent(report.withBorder(Borders.singleLine(" Riport és forráskód ")),
                GridLayout.createLayoutData(GridLayout.Alignment.FILL, GridLayout.Alignment.FILL, true, true));
        rootPanel.addComponent(columns,
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));

        log = new TextBox(LOG_SIZE, TextBox.Style.MULTI_LINE).setReadOnly(true);
        log.setVerticalFocusSwitching(true);
        rootPanel.addComponent(log.withBorder(Borders.singleLine(" Napló ")),
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.CanGrow));

        progressInfo = new Label("  Készen áll – az F2 indítja a riportgenerálást");
        rootPanel.addComponent(progressInfo,
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.None));

        progress = new ProgressBar(0, 100, 100);
        progress.setLabelFormat("%3.0f%%");
        rootPanel.addComponent(progress,
                LinearLayout.createLayoutData(LinearLayout.Alignment.Fill, LinearLayout.GrowPolicy.None));

        Panel footer = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        footer.addComponent(new Button("F1 Súgó", this::showHelp));
        generate = new Button("F2 Generálás", this::startGeneration);
        reset = new Button("F4 Alapérték", this::resetForm);
        footer.addComponent(generate);
        footer.addComponent(reset);
        footer.addComponent(new Button("F10 Kilépés", this::requestClose));
        status = new Label("  Készen áll");
        footer.addComponent(status);
        rootPanel.addComponent(footer);

        window.setComponent(rootPanel);
        fillForm(options);
    }

    private Panel buildPathsPanel() {
        Panel panel = new Panel(new GridLayout(2).setHorizontalSpacing(1));
        root = textBox();
        output = textBox();
        title = textBox();
        since = textBox();
        until = textBox();
        addField(panel, "Gyökér:", pathPicker(root, "Gyökérkönyvtár kiválasztása"));
        addField(panel, "Kimenet:", pathPicker(output, "Kimeneti könyvtár kiválasztása"));
        addField(panel, "Riport címe:", title);
        addField(panel, "Kezdet:", datePicker(since, "Kezdődátum kiválasztása"));
        addField(panel, "Vége:", datePicker(until, "Záródátum kiválasztása"));
        panel.addComponent(new Label("Dátum:"));
        panel.addComponent(new Label("kézzel vagy a [Választ] gombbal"));
        panel.addComponent(new Label("A gyökér alatt minden Git repó szerepel."),
                GridLayout.createLayoutData(GridLayout.Alignment.BEGINNING, GridLayout.Alignment.BEGINNING,
                        false, false, 2, 1));
        return panel;
    }

    private Panel pathPicker(TextBox value, String dialogTitle) {
        value.setPreferredSize(new TerminalSize(20, 1));
        Panel picker = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        picker.addComponent(value);
        picker.addComponent(control(new Button("Tallóz", () -> chooseDirectory(value, dialogTitle))));
        return picker;
    }

    private Panel datePicker(TextBox value, String dialogTitle) {
        value.setPreferredSize(new TerminalSize(16, 1));
        Panel picker = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        picker.addComponent(value);
        picker.addComponent(control(new Button("Választ", () -> chooseDate(value, dialogTitle))));
        return picker;
    }

    private Panel buildReportPanel() {
        Panel panel = new Panel(new LinearLayout(Direction.VERTICAL));
        panel.addComponent(new Label("Kimenetek:"));
        html = control(new CheckBox("HTML riport"));
        markdown = control(new CheckBox("Markdown + fejlesztői MD fájlok"));
        source = control(new CheckBox("Forráskód branchenként"));
        panel.addComponent(html);
        panel.addComponent(markdown);
        panel.addComponent(source);
        patches = control(new CheckBox("Teljes commit diffek"));
        fetch = control(new CheckBox("git fetch --all --prune"));
        panel.addComponent(patches);
        panel.addComponent(fetch);
        markdownLimit = control(new ComboBox<>("korlátlan", "1 MB", "5 MB", "10 MB", "25 MB", "50 MB", "100 MB",
                "500 MB", "750 MB", "1024 MB"));
        markdownLimit.setDropDownNumberOfRows(10);
        markdownLimit.setPreferredSize(new TerminalSize(13, 1));
        Panel markdownLimitRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        markdownLimitRow.addComponent(new Label("MD max.:"));
        markdownLimitRow.addComponent(markdownLimit);
        panel.addComponent(markdownLimitRow);
        sourceMode = control(new ComboBox<>(SourceMode.values()));
        sourceMode.setReadOnly(true);
        sourceMode.setDropDownNumberOfRows(SourceMode.values().length);
        sourceMode.setPreferredSize(new TerminalSize(25, 1));
        sourceMode.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> updateSourceControl());
        Panel sourceModeRow = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        sourceModeRow.addComponent(new Label("Forrás:"));
        sourceModeRow.addComponent(sourceMode);
        panel.addComponent(sourceModeRow);
        panel.addComponent(new Label("Branchlista vagy Git ref:"));
        sourceValue = textBox();
        panel.addComponent(sourceValue);
        panel.addComponent(new Label("Lista példa: main,develop,origin/release"));
        return panel;
    }

    private MenuBar menuBar() {
        fileMenu = new ShortcutMenu("Fájl");
        fileMenu.add(new MenuItem("Riport generálása   F2", this::startGeneration));
        fileMenu.add(new MenuItem("Kilépés             F10", this::requestClose));
        settingsMenu = new ShortcutMenu("Beállítások");
        settingsMenu.add(new MenuItem("Alapértékek visszaállítása   F4", this::resetForm));
        helpMenu = new ShortcutMenu("Súgó");
        helpMenu.add(new MenuItem("Részletes súgó   F1", this::showHelp));
        helpMenu.add(new MenuItem("Névjegy", () -> MessageDialog.showMessageDialog(gui,
                "Git Contributor Report", "Midnight Commander-stílusú terminálfelület\nLanterna 3 alapokon",
                MessageDialogButton.OK)));
        return new MenuBar().add(fileMenu).add(settingsMenu).add(helpMenu);
    }

    static Character menuShortcut(KeyStroke keyStroke) {
        Character character = keyStroke.getCharacter();
        return keyStroke.isAltDown() && character != null
                ? Character.toLowerCase(character)
                : null;
    }

    private void chooseDirectory(TextBox target, String dialogTitle) {
        DirectoryDialogBuilder builder = new DirectoryDialogBuilder()
                .setTitle(dialogTitle)
                .setDescription("Válaszd ki a könyvtárat, majd nyomj Entert a Kiválasztás gombon.")
                .setActionLabel("Kiválasztás")
                .setSuggestedSize(new TerminalSize(70, 20))
                .setSelectedDirectory(existingDirectory(target.getText()).toFile());
        builder.setShowHiddenDirectories(true);
        File selected = builder.build().showDialog(gui);
        if (selected != null) target.setText(selected.toPath().toAbsolutePath().normalize().toString());
    }

    private void chooseDate(TextBox target, String dialogTitle) {
        LocalDate today = LocalDate.now();
        LocalDate initial = isoDateOr(target.getText(), today);
        AtomicBoolean adjusting = new AtomicBoolean(true);

        ComboBox<DatePreset> preset = new ComboBox<>(DatePreset.values());
        preset.setReadOnly(true);
        preset.setDropDownNumberOfRows(DatePreset.values().length);
        preset.setSelectedItem(DatePreset.CUSTOM);

        ComboBox<Integer> year = new ComboBox<>(yearChoices(initial.getYear()));
        year.setReadOnly(true);
        year.setDropDownNumberOfRows(10);
        year.setPreferredSize(new TerminalSize(7, 1));
        ComboBox<Integer> month = new ComboBox<>(numberChoices(1, 12));
        month.setReadOnly(true);
        month.setDropDownNumberOfRows(12);
        month.setPreferredSize(new TerminalSize(5, 1));
        ComboBox<Integer> day = new ComboBox<>(numberChoices(1, YearMonth.from(initial).lengthOfMonth()));
        day.setReadOnly(true);
        day.setDropDownNumberOfRows(10);
        day.setPreferredSize(new TerminalSize(5, 1));
        setDateParts(year, month, day, initial);

        TextBox value = new TextBox(new TerminalSize(34, 1), TextBox.Style.SINGLE_LINE);
        value.setText(target.getText());

        Runnable dateChanged = () -> {
            if (adjusting.get()) return;
            adjusting.set(true);
            int selectedDay = day.getSelectedItem();
            refillDays(day, year.getSelectedItem(), month.getSelectedItem(), selectedDay);
            LocalDate selected = LocalDate.of(year.getSelectedItem(), month.getSelectedItem(), day.getSelectedItem());
            value.setText(selected.toString());
            preset.setSelectedItem(DatePreset.CUSTOM);
            adjusting.set(false);
        };
        year.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> dateChanged.run());
        month.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> dateChanged.run());
        day.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> dateChanged.run());
        preset.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> {
            if (!changedByUserInteraction || adjusting.get()) return;
            adjusting.set(true);
            DatePreset selectedPreset = preset.getSelectedItem();
            if (selectedPreset == DatePreset.NONE) {
                value.setText("");
            } else if (selectedPreset != DatePreset.CUSTOM) {
                LocalDate selected = selectedPreset.resolve(today);
                setDateParts(year, month, day, selected);
                value.setText(selected.toString());
            }
            adjusting.set(false);
        });
        adjusting.set(false);

        BasicWindow dialog = new BasicWindow(dialogTitle);
        dialog.setHints(List.of(Window.Hint.CENTERED));
        Panel content = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(new Label("Gyors választás:"));
        content.addComponent(preset);
        content.addComponent(new Label("Év / hónap / nap:"));
        Panel dateParts = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        dateParts.addComponent(year);
        dateParts.addComponent(month);
        dateParts.addComponent(day);
        content.addComponent(dateParts);
        content.addComponent(new Label("Érték (ISO vagy Git dátumkifejezés):"));
        content.addComponent(value);
        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        buttons.addComponent(new Button("OK", () -> {
            target.setText(value.getText().trim());
            dialog.close();
        }));
        buttons.addComponent(new Button("Mégse", dialog::close));
        content.addComponent(buttons);
        dialog.setComponent(content);
        gui.addWindowAndWait(dialog);
    }

    static Path existingDirectory(String value) {
        Path candidate;
        try {
            candidate = value == null || value.isBlank()
                    ? Path.of(System.getProperty("user.dir", "."))
                    : Path.of(value.trim());
        } catch (InvalidPathException exception) {
            candidate = Path.of(System.getProperty("user.dir", "."));
        }
        candidate = candidate.toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) candidate = candidate.getParent();
        while (candidate != null && !Files.isDirectory(candidate)) candidate = candidate.getParent();
        return candidate == null ? Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize() : candidate;
    }

    private static LocalDate isoDateOr(String value, LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : LocalDate.parse(value.trim());
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private static List<Integer> yearChoices(int selectedYear) {
        int maximum = Math.max(LocalDate.now().getYear() + 5, selectedYear);
        int minimum = Math.min(1970, selectedYear);
        List<Integer> years = new ArrayList<>();
        for (int year = maximum; year >= minimum; year--) years.add(year);
        return years;
    }

    private static List<Integer> numberChoices(int first, int last) {
        List<Integer> values = new ArrayList<>();
        for (int value = first; value <= last; value++) values.add(value);
        return values;
    }

    private static void setDateParts(ComboBox<Integer> year, ComboBox<Integer> month,
                                     ComboBox<Integer> day, LocalDate value) {
        year.setSelectedItem(value.getYear());
        month.setSelectedItem(value.getMonthValue());
        refillDays(day, value.getYear(), value.getMonthValue(), value.getDayOfMonth());
    }

    private static void refillDays(ComboBox<Integer> day, int year, int month, int requestedDay) {
        int lastDay = YearMonth.of(year, month).lengthOfMonth();
        day.clearItems();
        for (int value = 1; value <= lastDay; value++) day.addItem(value);
        day.setSelectedItem(Math.min(requestedDay, lastDay));
    }

    private void startGeneration() {
        if (!running.compareAndSet(false, true)) return;
        try {
            collectOptions();
        } catch (IllegalArgumentException exception) {
            running.set(false);
            MessageDialog.showMessageDialog(gui, "Hibás beállítás", exception.getMessage(), MessageDialogButton.OK);
            return;
        }

        setControlsEnabled(false);
        log.setText("");
        progress.setValue(0);
        generationStarted = Instant.now();
        lastProgressPhase = "";
        lastProgressUpdate = null;
        progressInfo.setText("  [0%] Indítás – a feladat előkészítése");
        status.setText("  0%  Indítás");
        appendLog("=== Riportgenerálás indítása ===");
        startProgressHeartbeat();

        Thread.ofVirtual().name("report-generator").start(() -> {
            UiLogWriter writer = new UiLogWriter(this::appendLog);
            try (PrintWriter printWriter = new PrintWriter(writer, true);
                 ConsoleOutput.Redirection ignored = ConsoleOutput.redirect(printWriter, printWriter)) {
                GitReportApplication.run(options, this::progressUpdated);
                writer.finish();
                ui(() -> generationFinished(null));
            } catch (Exception exception) {
                writer.finish();
                ui(() -> generationFinished(exception));
            }
        });
    }

    private void generationFinished(Exception exception) {
        if (exception == null) progress.setValue(100);
        setControlsEnabled(true);
        running.set(false);
        if (exception == null) {
            status.setText("  Kész: " + options.output.toAbsolutePath().normalize());
            progressInfo.setText("  [100%] Kész – minden kiválasztott kimenet elkészült – eltelt "
                    + elapsedText());
            appendLog("=== A generálás sikeresen befejeződött ===");
        } else {
            status.setText("  Hiba: " + oneLine(exception.getMessage()));
            progressInfo.setText("  HIBA " + progress.getValue() + "% után – "
                    + shorten(oneLine(exception.getMessage()), 100) + " – eltelt " + elapsedText());
            appendLog("HIBA: " + oneLine(exception.getMessage()));
        }
    }

    private void progressUpdated(ProgressUpdate update) {
        lastProgressUpdate = update;
        ui(() -> {
            renderProgress(update);
            String phase = phaseText(update);
            if (!phase.equals(lastProgressPhase)) {
                lastProgressPhase = phase;
                appendLog(">>> " + phase + ": " + update.detail());
            }
        });
    }

    private void startProgressHeartbeat() {
        Thread.ofVirtual().name("progress-heartbeat").start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (!running.get()) return;
                ProgressUpdate update = lastProgressUpdate;
                if (update != null) ui(() -> renderProgress(update));
            }
        });
    }

    private void renderProgress(ProgressUpdate update) {
        progress.setValue(update.percent());
        String phase = phaseText(update);
        String counters = update.total() > 0 ? " | " + update.current() + "/" + update.total() : "";
        progressInfo.setText("  [" + update.percent() + "%] " + phase + " – "
                + shorten(update.detail(), 110) + counters + " – dolgozik, eltelt " + elapsedText());
        status.setText("  " + update.percent() + "%  " + phase);
    }

    private static String phaseText(ProgressUpdate update) {
        return "[" + update.phaseNumber() + "/" + update.phaseCount() + "] " + update.phase();
    }

    private String elapsedText() {
        if (generationStarted == null) return "00:00";
        Duration duration = Duration.between(generationStarted, Instant.now());
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        long seconds = duration.toSecondsPart();
        return hours > 0
                ? "%d:%02d:%02d".formatted(hours, minutes, seconds)
                : "%02d:%02d".formatted(minutes, seconds);
    }

    static String shorten(String value, int maximumLength) {
        if (value == null) return "";
        String singleLine = value.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= maximumLength) return singleLine;
        return singleLine.substring(0, Math.max(1, maximumLength - 1)) + "…";
    }

    private void collectOptions() {
        options.root = path(root.getText(), "A gyökérkönyvtár");
        options.output = path(output.getText(), "A kimeneti könyvtár");
        options.title = required(title.getText(), "A riport címe");
        options.since = blankToNull(since.getText());
        options.until = blankToNull(until.getText());
        options.outputs = new LinkedHashSet<>();
        if (html.isChecked()) options.outputs.add("html");
        if (markdown.isChecked()) options.outputs.add("markdown");
        if (source.isChecked()) options.outputs.add("source");
        if (options.outputs.isEmpty()) throw new IllegalArgumentException("Legalább egy kimenetet válassz ki.");
        options.includePatches = patches.isChecked();
        options.noPatches = !options.includePatches;
        options.fetch = fetch.isChecked();
        String maximumSize = markdownLimit.getText().trim();
        options.maxMarkdownBytes = maximumSize.equalsIgnoreCase("korlátlan") || maximumSize.equals("0")
                ? 0
                : CliOptions.parseByteSize(maximumSize);
        options.sourceOnly = false;

        SourceMode mode = sourceMode.getSelectedItem();
        options.sourceRefExplicit = false;
        if (mode == SourceMode.REF) {
            options.sourceRef = required(sourceValue.getText(), "A Git ref");
            options.sourceRefExplicit = true;
        } else if (mode == SourceMode.LIST) {
            options.sourceBranches = parseBranches(sourceValue.getText());
            if (options.sourceBranches.isEmpty()) {
                throw new IllegalArgumentException("A branchlista nem lehet üres.");
            }
        } else {
            options.sourceBranches = new LinkedHashSet<>(List.of(mode.value));
        }
        options.normalize();
    }

    private void fillForm(CliOptions values) {
        root.setText(values.root.toString());
        output.setText(values.output.toString());
        title.setText(values.title);
        since.setText(orEmpty(values.since));
        until.setText(orEmpty(values.until));
        html.setChecked(values.outputs.contains("html"));
        markdown.setChecked(values.outputs.contains("markdown"));
        source.setChecked(values.outputs.contains("source"));
        patches.setChecked(values.includePatches);
        fetch.setChecked(values.fetch);
        String formattedLimit = CliOptions.formatByteSize(values.maxMarkdownBytes);
        boolean knownLimit = false;
        for (int index = 0; index < markdownLimit.getItemCount(); index++) {
            if (markdownLimit.getItem(index).equals(formattedLimit)) knownLimit = true;
        }
        if (!knownLimit) markdownLimit.addItem(formattedLimit);
        markdownLimit.setSelectedItem(formattedLimit);

        SourceMode mode = SourceMode.from(values);
        sourceMode.setSelectedItem(mode);
        sourceValue.setText(mode == SourceMode.REF
                ? values.sourceRef
                : mode == SourceMode.LIST ? String.join(",", values.sourceBranches) : "");
        updateSourceControl();
    }

    private void resetForm() {
        if (running.get()) return;
        CliOptions defaults = new CliOptions();
        defaults.normalize();
        fillForm(defaults);
        log.setText("");
        progress.setValue(0);
        progressInfo.setText("  Készen áll – az F2 indítja a riportgenerálást");
        status.setText("  Alapértékek visszaállítva");
    }

    private void requestClose() {
        if (running.get()) {
            MessageDialog.showMessageDialog(gui, "Folyamatban",
                    "A riportgenerálás még fut. Várd meg a befejezését.", MessageDialogButton.OK);
            return;
        }
        MessageDialogButton answer = MessageDialog.showMessageDialog(gui, "Kilépés",
                "Biztosan kilépsz?", MessageDialogButton.Yes, MessageDialogButton.No);
        if (answer == MessageDialogButton.Yes) window.close();
    }

    private void showHelp() {
        BasicWindow helpWindow = new BasicWindow("Részletes súgó") {
            @Override
            public boolean handleInput(KeyStroke keyStroke) {
                if (keyStroke.getKeyType() == KeyType.Escape || keyStroke.getKeyType() == KeyType.F1) {
                    close();
                    return true;
                }
                return super.handleInput(keyStroke);
            }
        };
        helpWindow.setHints(List.of(Window.Hint.CENTERED));
        helpWindow.setCloseWindowWithEscape(true);

        ComboBox<HelpTopic> topics = new ComboBox<>(HelpTopic.values());
        topics.setReadOnly(true);
        topics.setDropDownNumberOfRows(Math.min(HelpTopic.values().length, 10));
        topics.setPreferredSize(new TerminalSize(42, 1));
        TextBox helpText = new TextBox(new TerminalSize(72, 14), TextBox.Style.MULTI_LINE).setReadOnly(true);
        helpText.setVerticalFocusSwitching(false);

        Runnable updateTopic = () -> {
            HelpTopic selected = topics.getSelectedItem();
            helpText.setText(wrapHelp(selected == null ? "" : selected.content, 70));
            helpText.setCaretPosition(0, 0);
        };
        topics.addListener((selectedIndex, previousSelection, changedByUserInteraction) -> updateTopic.run());

        Panel content = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(1));
        content.addComponent(new Label("Témakör (Enter: lista, ↑/↓: választás):"));
        content.addComponent(topics);
        content.addComponent(helpText.withBorder(Borders.singleLine(" Leírás – nyilakkal görgethető ")));
        Panel buttons = new Panel(new LinearLayout(Direction.HORIZONTAL).setSpacing(1));
        buttons.addComponent(new Button("Bezárás  Esc/F1", helpWindow::close));
        content.addComponent(buttons);
        helpWindow.setComponent(content);
        updateTopic.run();
        gui.addWindowAndWait(helpWindow);
    }

    static String completeHelpText() {
        StringBuilder text = new StringBuilder();
        for (HelpTopic topic : HelpTopic.values()) {
            text.append(topic.label).append('\n').append(topic.content).append("\n\n");
        }
        return text.toString();
    }

    private static String wrapHelp(String text, int width) {
        StringBuilder result = new StringBuilder();
        for (String paragraph : text.split("\\R", -1)) {
            if (paragraph.isBlank()) {
                result.append('\n');
                continue;
            }
            String indent = paragraph.startsWith("  ") ? "  " : "";
            String remaining = paragraph.strip();
            int lineLength = 0;
            result.append(indent);
            lineLength += indent.length();
            for (String word : remaining.split("\\s+")) {
                if (lineLength > indent.length() && lineLength + 1 + word.length() > width) {
                    result.append('\n').append(indent);
                    lineLength = indent.length();
                } else if (lineLength > indent.length()) {
                    result.append(' ');
                    lineLength++;
                }
                result.append(word);
                lineLength += word.length();
            }
            result.append('\n');
        }
        return result.toString().stripTrailing();
    }

    private void updateSourceControl() {
        SourceMode mode = sourceMode.getSelectedItem();
        sourceValue.setEnabled(mode == SourceMode.LIST || mode == SourceMode.REF);
        if (mode == SourceMode.LIST && sourceValue.getText().isBlank()) sourceValue.setText("main");
        if (mode == SourceMode.REF && sourceValue.getText().isBlank()) sourceValue.setText("HEAD");
    }

    private void setControlsEnabled(boolean enabled) {
        controls.forEach(control -> control.setEnabled(enabled));
        generate.setEnabled(enabled);
        reset.setEnabled(enabled);
        if (enabled) updateSourceControl();
    }

    private void appendLog(String line) {
        ui(() -> {
            while (log.getLineCount() >= 500) log.removeLine(0);
            log.addLine(line);
            int last = Math.max(0, log.getLineCount() - 1);
            log.setCaretPosition(last, log.getLine(last).length());
        });
    }

    private void ui(Runnable action) {
        if (gui == null) return;
        if (Thread.currentThread() == gui.getGUIThread().getThread()) action.run();
        else gui.getGUIThread().invokeLater(action);
    }

    private TextBox textBox() {
        TextBox textBox = new TextBox(FIELD_SIZE, TextBox.Style.SINGLE_LINE);
        controls.add(textBox);
        return textBox;
    }

    private <T extends Interactable> T control(T control) {
        controls.add(control);
        return control;
    }

    private static void addField(Panel panel, String label, Component value) {
        panel.addComponent(new Label(label));
        panel.addComponent(value, GridLayout.createHorizontallyFilledLayoutData());
    }

    private static Path path(String value, String label) {
        String required = required(value, label);
        try {
            return Path.of(required);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException(label + " érvénytelen: " + exception.getMessage());
        }
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " nem lehet üres.");
        return value.trim();
    }

    static LinkedHashSet<String> parseBranches(String value) {
        LinkedHashSet<String> branches = new LinkedHashSet<>();
        if (value == null) return branches;
        for (String branch : value.split(",")) {
            String trimmed = branch.trim();
            if (!trimmed.isEmpty()) branches.add(trimmed);
        }
        return branches;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String oneLine(String value) {
        return value == null ? "ismeretlen hiba" : value.replaceAll("\\s+", " ").trim();
    }

    private enum SourceMode {
        ALL("Minden helyi és remote branch", "all"),
        LOCAL("Csak helyi branchek", "local"),
        REMOTE("Csak remote branchek", "remote"),
        LIST("Branchlista", "list"),
        REF("Egyetlen branch / tag / commit", "ref");

        private final String label;
        private final String value;

        SourceMode(String label, String value) {
            this.label = label;
            this.value = value;
        }

        static SourceMode from(CliOptions options) {
            if (options.sourceRefExplicit) return REF;
            if (options.sourceBranches.size() == 1) {
                return switch (options.sourceBranches.iterator().next()) {
                    case "all" -> ALL;
                    case "local" -> LOCAL;
                    case "remote" -> REMOTE;
                    default -> LIST;
                };
            }
            return LIST;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum HelpTopic {
        QUICK_START("1. Gyors kezdés", """
                Interaktív használat:
                  java -jar git-contributor-report-1.0.0-SNAPSHOT.jar --interactive

                1. Válaszd ki a Git repókat tartalmazó gyökérkönyvtárat.
                2. Add meg a riport kimeneti könyvtárát.
                3. Jelöld ki a szükséges HTML, Markdown és forráskód kimeneteket.
                4. Szükség esetén állíts dátumot, branch-kört, fetch-et és MD méretkorlátot.
                5. Nyomj F2-t vagy válaszd a Fájl / Riport generálása menüpontot.

                A futás üzenetei a Napló panelen, állapota az alsó százalékos sávon látható.
                A kész fájlok a Kimenet mezőben megadott könyvtárba kerülnek.
                """),
        NAVIGATION("2. Navigáció és menük", """
                Tab / Shift+Tab: következő vagy előző kezelőelem.
                Nyilak: lista, szövegmező és görgethető súgó kezelése.
                Space: jelölőnégyzet be- vagy kikapcsolása.
                Enter: gomb aktiválása vagy lista megnyitása.
                Esc: párbeszédablak vagy menü bezárása.

                Alt+F: Fájl menü.
                Alt+B: Beállítások menü.
                Alt+S: Súgó menü.
                F1: részletes súgó; F2: generálás; F4: alapértékek; F10: kilépés.

                A legördülő listában Enter után a fel/le nyíllal válassz, majd Enterrel fogadd el.
                """),
        DIRECTORIES("3. Könyvtárak és repókeresés", """
                A Gyökér az a könyvtár, amely alatt az alkalmazás rekurzívan Git repókat keres.
                Normál repók, bare repók és Git worktree-k is felismerhetők. A Kimenet könyvtárat
                a keresés kihagyja, így a korábban generált riport nem válik elemzési bemenetté.

                A Tallóz gomb terminálos könyvtárböngészőt nyit. A listában Enterrel beléphetsz
                egy könyvtárba, a .. elemmel visszaléphetsz. A Kiválasztás gomb az aktuális
                könyvtárat írja vissza a mezőbe. Nem létező kimeneti könyvtárat az alkalmazás
                generáláskor létrehoz.
                """),
        DATES("4. Dátumszűrés", """
                A Kezdet a Git --since, a Vége a Git --until szűrője. Üresen hagyva azon az
                oldalon nincs időkorlát. Ajánlott forma: ÉÉÉÉ-HH-NN, például 2026-01-01.

                A Választ gomb gyors értékeket kínál: nincs korlát, ma, tegnap, 7/30/90 napja
                és az év első napja. Év, hónap és nap külön listából is megadható. Az értékmező
                szerkeszthető, ezért a Git által elfogadott más dátumkifejezés is használható.

                A szűrés a commit dátumára vonatkozik; a forráskód-export mindig a kiválasztott
                branch vagy ref aktuális commitolt pillanatképét írja ki.
                """),
        OUTPUTS("5. Riportkimenetek", """
                HTML riport: böngészhető index, fejlesztői, repó- és commitoldalak, helyi CSS-sel.
                Markdown: index.md, továbbá fejlesztőnkénti, repónkénti és commitonkénti MD fájlok.
                Forráskód: minden kiválasztott branch teljes commitolt fája külön Markdownban.

                Több kimenet egyszerre is kijelölhető. A Teljes commit diffek kapcsoló a konkrét
                hozzáadott és törölt kódot is begyűjti. Kikapcsolása jelentősen gyorsíthatja a
                riportot és csökkentheti a tárhelyigényt, de a kódmódosítások tartalma kimarad.
                """),
        SOURCE("6. Branch- és forráskód-export", """
                Minden helyi és remote branch: minden elérhető refs/heads és refs/remotes ág.
                Csak helyi / csak remote: a megadott branch-csoport.
                Branchlista: vesszővel elválasztott pontos nevek, például main,develop,origin/release.
                Egyetlen branch / tag / commit: egy Git ref vagy commit hash, például HEAD vagy master.

                A forrás a Git objektum-adatbázisából készül, ezért csak commitolt fájlokat tartalmaz;
                a munkakönyvtár nem commitolt változásai nem kerülnek bele. A bináris fájlokról metaadat
                készül, de bináris tartalmuk nem ágyazódik a Markdownba.
                """),
        MARKDOWN_SIZE("7. Markdown méretkorlát", """
                Az MD max. mező fájlonkénti felső határt állít. Választható például 10 MB, 500 MB,
                750 MB vagy 1024 MB; egyéni érték is beírható. A korlátlan érték kikapcsolja a
                darabolást. A CLI megfelelője: --max-md-size 500MB.

                A nagy dokumentum helyén egy tartalomjegyzék marad, a részek neve .part-001.md,
                .part-002.md és így tovább. A daraboló nem vág félbe UTF-8 karaktert, és a részeken
                átívelő Markdown-kódblokkokat lezárja, majd a következő részben újranyitja.
                A legkisebb nem nulla korlát 16 KB.
                """),
        FETCH("8. Git fetch működése", """
                A git fetch --all --prune kapcsoló minden megtalált repóban frissíti a remote-tracking
                refeket az elemzés előtt. Hálózati kapcsolatot és a remote eléréséhez szükséges
                hitelesítést igényelhet.

                Nem checkoutol branchet, nem merge-el és nem módosítja a munkakönyvtár fájljait.
                Frissíti azonban a remote refeket és a FETCH_HEAD fájlt; a --prune eltávolítja a
                remote-on már nem létező követő refeket. Sikertelen fetch esetén figyelmeztetés után
                a helyben elérhető Git-adatokkal folytatódik a riport.
                """),
        INTERPRETATION("9. A riport értelmezése", """
                A riport szerző, e-mail, commit, branch-elérhetőség, aktív nap, fájlérintés,
                hozzáadott/törölt sor, fájltípus és időbeli aktivitás szerint összesít. A commit
                részletek tartalmazhatják az üzenetet, az érintett fájlokat és a teljes diffet.
                A Co-authored-by trailerek külön közreműködést jelezhetnek.

                Az identitás-egyesítés először a Git .mailmap szabályait alkalmazza, majd az azonos
                normalizált e-mailt vagy nevet közös fejlesztői profilba rendezi. A kis-/nagybetű,
                Unicode-alak, ékezet és szóköz nem hoz létre külön profilt. Az eredeti név- és
                e-mail-változatok aliasokként megmaradnak a fejlesztői adatlapon.

                Ezek leíró aktivitási adatok, nem önmagukban használható teljesítménypontok.
                Squash merge, generált kód, formázás, páros munka, mentoring, kutatás, review és
                eltérő feladatnehézség torzíthatja az összehasonlítást. Értékeléskor mindig szükséges
                a technikai és szervezeti kontextus emberi vizsgálata.
                """),
        PERFORMANCE("10. Teljesítmény és adatvédelem", """
                Sok branch, hosszú történet és teljes forráskód esetén a futás és a kimenet nagy lehet.
                Gyorsításhoz szűkítsd a dátumtartományt vagy a brancheket, kapcsold ki a teljes diffeket,
                illetve csak a szükséges kimenettípusokat jelöld ki. Forráskód-exportnál célszerű MD
                méretkorlátot használni.

                Generálás közben a százalékos sáv feletti állapotsor mutatja a fázis sorszámát, az
                aktuális repót, commitot, branchet vagy fájlt, a részfeladat számlálóit és az eltelt
                időt. A Napló minden új fázist külön sorban rögzít. A százalék a kiválasztott
                munkafázisok súlyozott készültsége, ezért nem pusztán a repók darabszámát jelenti.

                A riport forráskódot, neveket, e-mail-címeket, commitüzeneteket és repository URL-eket
                tartalmazhat. Kezeld bizalmas fejlesztési adatként; megosztás előtt ellenőrizd a
                jogosultságokat és a célkönyvtár tartalmát.
                """),
        CLI("11. Parancssori használat", """
                A teljes képernyős felület nélkül minden beállítás automatizálható. Példa:

                  java -jar git-contributor-report-1.0.0-SNAPSHOT.jar --root C:\\projektek
                  --output report --outputs html,markdown,source --since 2026-01-01
                  --source-branches main,develop --max-md-size 500MB --fetch

                A --help kilistázza az összes opciót és további példákat. Az --interactive együtt is
                használható a többi kapcsolóval; ilyenkor azok a felület kezdőértékei. Automatizált
                futtatáshoz, pipe-hoz és kimenetátirányításhoz a normál CLI mód ajánlott.
                """);

        private final String label;
        private final String content;

        HelpTopic(String label, String content) {
            this.label = label;
            this.content = content.strip();
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private enum DatePreset {
        CUSTOM("Egyéni / jelenlegi", 0),
        NONE("Nincs dátumkorlát", 0),
        TODAY("Ma", 0),
        YESTERDAY("Tegnap", 1),
        DAYS_7("7 nappal ezelőtt", 7),
        DAYS_30("30 nappal ezelőtt", 30),
        DAYS_90("90 nappal ezelőtt", 90),
        YEAR_START("Az év első napja", -1);

        private final String label;
        private final int daysAgo;

        DatePreset(String label, int daysAgo) {
            this.label = label;
            this.daysAgo = daysAgo;
        }

        LocalDate resolve(LocalDate today) {
            if (this == YEAR_START) return today.withDayOfYear(1);
            return today.minusDays(daysAgo);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private static final class ShortcutMenu extends Menu {
        private ShortcutMenu(String label) {
            super(label);
        }

        private void open() {
            takeFocus();
            onActivated();
        }
    }

    private static final class UiLogWriter extends Writer {
        private final java.util.function.Consumer<String> sink;
        private final StringBuilder line = new StringBuilder();

        private UiLogWriter(java.util.function.Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void write(char[] characters, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                char character = characters[index];
                if (character == '\n') emit();
                else if (character != '\r') line.append(character);
            }
        }

        @Override
        public synchronized void flush() { }

        @Override
        public synchronized void close() {
            finish();
        }

        private synchronized void finish() {
            if (!line.isEmpty()) emit();
        }

        private void emit() {
            sink.accept(line.toString());
            line.setLength(0);
        }
    }
}
