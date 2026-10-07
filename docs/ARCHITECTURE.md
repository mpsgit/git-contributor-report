# Fejlesztői dokumentáció

## Modulok

| Forrásfájl | Felelősség |
|---|---|
| `Main.java` | Minimális belépési pont és hibakód-kezelés. |
| `ConsoleOutput.java` | Unicode-biztos Windows konzolkimenet a Jansi/JLine `WriteConsoleW` API-jával, automatikus átirányítási fallbackkel. |
| `CliOptions.java` | CLI-opciók feldolgozása, validálása és súgója. |
| `InteractiveConsole.java` | Lanterna-alapú, kétpaneles teljes képernyős TUI, menü, napló és háttérben futó generálás. |
| `JLineLanternaTerminal.java` | JLine natív terminál és Lanterna közötti adapter, Windows/Linux méret-, szín-, Unicode- és billentyűkezeléssel. |
| `MarkdownSplitter.java` | A generált Markdownok UTF-8- és kódblokk-biztos, méretkorlát szerinti darabolása. |
| `GitReportApplication.java` | Futtatási folyamat, repófelderítés, Git-történet elemzése és patch-csomagok indexelése. |
| `ReportModel.java` | Az elemzés és kimenetek közös domainmodellje. |
| `ReportWriter.java` | HTML- és Markdown-riportok előállítása. |
| `SourceCodeExporter.java` | Git-fák branchenkénti, streamelt Markdown-exportja. |
| `TemplateRenderer.java` | FreeMarker konfiguráció és HTML-oldalsablonok renderelése. |
| `src/main/resources/templates/` | A prezentációs réteg szerkeszthető FreeMarker-sablonjai. |

## Könyvtárválasztás

| Könyvtár | Feladat | Mit váltott ki? |
|---|---|---|
| picocli | CLI parse, típuskonverzió, validáció, help/version | Saját `switch` alapú argumentumfeldolgozás. |
| FreeMarker | HTML dokumentumváz és prezentációs sablon | Java text blockban összerakott teljes oldalváz. |
| Commons Text | HTML karakter-escaping | Saját karakterenkénti helyettesítés. |
| Commons IO | Stream copy és rekurzív könyvtártörlés | Saját IO segédmetódusok jelentős része. |
| Lanterna 3 | Panelek, keretek, menük, mezők, dialógusok és témázható TUI | Saját képernyőrajzolás és konzol-widgetek. |
| JLine Native | Windows/Linux terminál, raw input, Unicode és funkcióbillentyűk | Platformfüggő konzolkód. |
| Jansi | A normál CLI Windows konzolkódolása és ANSI-színei | Kézi kódlapváltás. |
| JUnit Jupiter | Regressziós tesztek | Kizárólag kézi ellenőrzés. |
| Maven Shade Plugin | Futtatható uber-JAR | Külső classpath kézi összeállítása. |

A natív Git folyamatok megtartása szándékos architekturális döntés, nem saját Git-implementáció. A Git CLI biztosítja a felhasználó konfigurációjával egyező `.mailmap`, attribútum-, diff-, ref- és objektumviselkedést. A `cat-file --batch` a teljes forrásexportot egyetlen Git-processzel streameli branchenként.

## Futtatási folyamat

```text
Main
  -> CliOptions
  -> InteractiveConsole (--interactive; opcionális Lanterna TUI és napló)
       -> JLineLanternaTerminal
  -> GitReportApplication
       -> repófelderítés
       -> opcionális git fetch --all --prune (--fetch)
       -> Git log/ref/patch elemzés (ha HTML vagy Markdown kell)
       -> ReportWriter (HTML/Markdown)
       -> SourceCodeExporter (ha source kell)
```

A két kezelési mód nem tart fenn külön riportlogikát. Az interaktív felület ugyanazt a `CliOptions` objektumot tölti ki, majd ugyanazt a `GitReportApplication.run` metódust hívja, mint a normál CLI. Emiatt az új funkciókat csak egyszer kell megvalósítani.

Csak `source` kimenet esetén a commit- és fejlesztőelemzés teljesen kimarad. Ez fontos nagy repóknál.

Az előrehaladás súlyozott fázisokból épül fel. A bekapcsolt funkcióktól függően a Git-ellenőrzés, repófelderítés, fetch, történetelemzés, riportírás és forrásexport más súlyt kap. A `ProgressReporter` monoton százalékot és fázisszámlálót ad; a TUI ehhez másodpercenként frissülő heartbeat állapotot tesz hozzá.

## Git-adatforrások

- `git log --all --use-mailmap --numstat`: commitok, identitások és sorstatisztikák.
- `git for-each-ref`: lokális és remote branchek.
- `git diff-tree`/patch stream: teljes commit diffek egy ideiglenes, indexelt csomagban.
- `git ls-tree`: egy branch teljes fája.
- `git cat-file --batch`: blobok hatékony, processzenkénti streamelése.

Az alkalmazás nem checkoutol brancheket, és nem olvassa a forrásexportot a munkakönyvtárból. Emiatt a dirty worktree biztonságban marad.

## Identitásfeloldás

1. A `git log --use-mailmap` először a repó saját `.mailmap` szabályait alkalmazza.
2. Az alkalmazás az azonos, kisbetűsre alakított e-mail-címeket közös profilba rendezi.
3. Az azonos név is összevonást okoz; a név-összehasonlítás Unicode-normalizált, ékezet-, kis-/nagybetű- és whitespace-független.
4. Az aliaskapcsolatok tranzitívek: egy név- és egy e-mail-egyezésből álló lánc is egyetlen profilt eredményez.
5. Összevonáskor a statisztikák, repókapcsolatok, branchreferenciák, commitok, napok és aliasok is átkerülnek a közös profilba.

Nincs személyhez kötött hardcode vagy külön kivétellista. A tesztek kizárólag fiktív neveket és a fenntartott `test.invalid` domaint használják.

## Memória- és lemezkezelés

- A commit patch-ek egy ideiglenes `.patch-cache` csomagba kerülnek, a modell csak offsetet és hosszt tárol.
- A fejlesztői Markdownok írása streamelt.
- A forráskód-export branchenként, ideiglenes fájlba készül, majd atomikus átnevezéssel válik láthatóvá.
- A `git cat-file --batch` elkerüli a fájlonkénti Git-processzeket.
- A készülő branch-pillanatkép ideiglenes fájlba íródik, és csak lezárás után kerül a végleges névre.
- A Markdown méretellenőrzése könyvtáranként egyszer gyűjti össze a korábbi részfájlokat, így sok commitoldalnál sem végez négyzetes számú könyvtárbejárást.

## Hiba- és biztonságkezelés

- A külső Git-folyamatok stdout/stderr streamjeit párhuzamos virtuális szálak olvassák, ezért nagy kimenetnél sem telik meg a processz pipe.
- A remote URL-ek riportba kerülése előtt a HTTP userinfo és a gyakori token/jelszó query paraméterek maszkolódnak.
- A fetch hibája figyelmeztetésként kerül a riportba; a helyben elérhető refek elemzése folytatódik.
- A történet- vagy írási hiba megszakítja a futást, és a TUI/CLI jelzi az utolsó fázist.
- A `.patch-cache` `finally` ágban törlődik.

## Bővítés

Egy új kimeneti formátumhoz:

1. Adj új engedélyezett értéket a `CliOptions.OUTPUT_NAMES` halmazhoz és az `OutputConverter` validációjához.
2. Hozz létre külön writer/exporter osztályt.
3. A `GitReportApplication.run` metódusban csak akkor hívd meg, ha kiválasztották.
4. Dokumentáld a kimeneti szerkezetet az `OUTPUTS.md` fájlban.
5. Adj CLI-, unit- és ideiglenes Git-repón futó integrációs tesztet.

## Fordítás és ellenőrzés

```powershell
mvn clean verify
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --help
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

A tesztcsomag rétegei:

| Teszt | Lefedett terület |
|---|---|
| `CliOptionsTest` | Alapértékek, aliasok, kimenet/branch parse, méretegységek, súgó és TUI-kezdőértékek. |
| `GitReportApplicationTest` | Repófelderítés, útvonal/kiterjesztés segédek és külső folyamat kimenete. |
| `GitReportApplicationIntegrationTest` | Valódi ideiglenes Git-repó, commitok, diff, társszerző, HTML/MD/source generálás és dirty worktree kizárása. |
| `IdentityResolutionTest` | E-mail-, név-, Unicode-, whitespace- és tranzitív alias-egyesítés. |
| `MarkdownSplitterTest` | UTF-8 darabolás, kódkerítések, méretkorlát, régi részek kötegelt takarítása. |
| `ReportWriterTest` | HTML/Markdown escaping, fájlnév-slugok és nyelvi jelölések. |
| `ProgressReporterTest` | Súlyozott, monoton folyamatjelzés és részszámlálók. |
| `JLineLanternaTerminalTest` | Enter/Tab/control/Unicode billentyűk és Alt-menükódok. |

Integrációs ellenőrzésnél legalább ezeket a kombinációkat érdemes futtatni:

- `--outputs html --no-patches`
- `--outputs markdown`
- `--outputs source --source-branches local`
- `--source-only --source-ref <branch>`

A teljes automatikus és kézi eljárás a [TESTING.md](TESTING.md) fájlban található.
