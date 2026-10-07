# Git Contributor Report

Java alkalmazás hagyományos parancssori és Midnight Commander-stílusú teljes képernyős terminálfelülettel. Egy könyvtár alatt rekurzívan megkeresi a Git repókat, részletes fejlesztői riportokat készít, és branchenként egyetlen Markdown-fájlba tudja fűzni a teljes commitolt forráskódot. A HTML-, Markdown- és forráskód-kimenet egymástól függetlenül kapcsolható.

## Dokumentáció

- [Részletes használat és parancssori opciók](docs/USAGE.md)
- [Kimeneti fájlok és könyvtárszerkezet](docs/OUTPUTS.md)
- [Fejlesztői dokumentáció és architektúra](docs/ARCHITECTURE.md)
- [Tesztelési útmutató és ellenőrzési mátrix](docs/TESTING.md)

## Követelmények

- Java 21+
- Maven 3.9+
- Git 2.x

A `java`, `mvn` és `git` parancsnak elérhetőnek kell lennie a `PATH` környezeti változóban. A Maven csak a fordításhoz szükséges; a létrehozott árnyékolt JAR minden Java-függőséget tartalmaz, de a Git parancsot futás közben is külső programként hívja.

## Felhasznált keretrendszerek és könyvtárak

- **picocli** – annotációalapú parancssori interfész, validáció, automatikus súgó és verziókimenet.
- **Apache FreeMarker** – HTML-oldalsablonok renderelése.
- **Apache Commons Text** – szabványos HTML-escaping.
- **Apache Commons IO** – streammásolás és biztonságos könyvtárkezelés.
- **Lanterna 3** – keretes panelek, menük, beviteli mezők, listák, párbeszédablakok és klasszikus kék TUI-téma.
- **JLine Native** – a Lanterna és a Windows/Linux terminál közötti, Unicode- és funkcióbillentyű-biztos terminálréteg.
- **Jansi** – a hagyományos CLI ANSI-színei és Windows konzolkezelése.
- **JUnit Jupiter** – automatikus tesztek.
- **Maven Shade Plugin** – a függőségeket is tartalmazó, egyetlen futtatható JAR előállítása.

A Git-adatok olvasása továbbra is a natív Git klienssel történik. Ez tudatos: a riportnak pontosan ugyanazt a `.mailmap`, ref-, diff-, attribútum- és objektumkezelést kell használnia, mint a vizsgált repónak, a nagy blobfolyamhoz pedig a `git cat-file --batch` lényegesen hatékonyabb.

## Gyors kezdés

```powershell
git clone git@github.com:mpsgit/git-contributor-report.git
cd git-contributor-report
mvn clean verify
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --help
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

Windows alatt a mellékelt `run.ps1` szükség esetén elkészíti a JAR-t, majd továbbítja a paramétereket:

```powershell
.\run.ps1 -Root "C:\munka\projektek" -Output "C:\munka\riport" -Interactive
```

Csak csomagolás: `mvn clean package`. Csak tesztek: `mvn test`. A teljes, tiszta ellenőrzéshez a javasolt parancs a `mvn clean verify`.

## Használat

Teljes képernyős interaktív mód:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

A felület a teljes terminált elfoglaló, kék–cián, kétpaneles nézetet ad felső menüvel, keretes beállításcsoportokkal, beépített futási naplóval és alsó funkcióbillentyű-sávval. Ugyanazokat a beállításokat kínálja, mint a CLI: útvonalak és időszak, kimeneti formátumok, teljes diff, automatikus fetch, valamint a forráskód-export branch/ref kiválasztása. A gyökér- és kimeneti könyvtár fájlrendszer-böngészőből is kiválasztható; a dátumválasztó gyors értékeket és külön év/hónap/nap listákat kínál, miközben kézi Git-dátumkifejezés is megadható. Generálás közben a súlyozott százalék mellett látható a fázis sorszáma, az aktuális repó/commit/branch/fájl, a részfeladat számlálója, a kiírt adatmennyiség és az eltelt idő; a „dolgozik” állapot másodpercenként frissül, minden fázis pedig a Naplóba is bekerül. A menük közvetlenül az `Alt+F` (Fájl), `Alt+B` (Beállítások) és `Alt+S` (Súgó) kombinációval nyithatók. A `Tab` léptet a mezők között, a `Space` jelöl, az `Enter` választ; `F1` egy 11 témakörös, görgethető részletes súgót nyit, `F2` generálás, `F4` alapértékek és `F10` kilépés. A CLI-opciók interaktív móddal együtt is megadhatók; ilyenkor kezdőértékként jelennek meg:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --interactive --root "C:\munka\projektek" --outputs markdown,source --fetch
```

Automatizálható, nem interaktív mód:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root "C:\munka\projektek" `
  --output "C:\munka\riport" `
  --title "Fejlesztői közreműködés" `
  --outputs html,markdown,source
```

Időszak szűrése:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --since 2026-01-01 --until 2026-12-31
```

A kimenet HTML belépőpontja az `index.html`, Markdown belépőpontja az `index.md`. A `developers` könyvtárban találhatók az egyéni, a `repositories` könyvtárban a repónkénti, a `commits` könyvtárban pedig a commitonkénti HTML- és Markdown-adatlapok. A HTML külső JavaScriptet vagy stíluslapot nem tölt le.

Windows alatt az alkalmazás a Jansi/JLine natív `WriteConsoleW` API-ján keresztül, közvetlen Unicode karakterekként ír a valódi konzolra. Emiatt a magyar szöveg nem függ a PowerShell 850/852/65001 kódlapjától. Valódi terminálban a picocli súgója színezett; fájlba vagy pipe-ba irányítva UTF-8 szöveg készül, ANSI-színkódok nélkül.

Kimenetválasztási példák:

```powershell
# Csak HTML, patch-ek nélkül
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --outputs html --no-patches

# HTML és Markdown, forráskód-export nélkül
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --outputs html,markdown

# Remote branchek frissítése, majd riportkészítés
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --fetch

# Csak a remote branchek teljes forráskódja
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --source-only --source-branches remote

# Csak a felsorolt branchek teljes forráskódja
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --source-only --source-branches main,develop,origin/release

# Maximum 10 MB-os Markdown-részek
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --max-md-size 10MB

# Minden lokális és remote branch
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --root . --source-only --source-branches all
```

A program alapértelmezésben minden lokális és remote branchhez külön `source-code-<branch>.md` fájlt készít. A `--source-branches all` minden branchet, a `local` vagy `remote` csak az adott kört, a `main,develop,origin/release` alakú lista pedig pontosan a felsorolt branch-neveket exportálja. A fájlnév biztonságos formában tartalmazza a teljes branchnevet, a `source-code-index.md` pedig linkeli az összes branch-pillanatképet. Minden Markdown az adott branch minden követett szöveges fájlját összefűzi. A fájlokat szeparátorok és részletes fejécek választják el (repó, teljes relatív útvonal, mappa, fájlnév, blob-hash, Git-mód, méret és nyelv). Egyetlen branch, tag vagy commit a `--source-ref <ref>` opcióval választható ki; ez felülírja a branch-listát.

Csak az összefűzött forráskód gyors előállítása, a teljes fejlesztői riport újragenerálása nélkül:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --source-only --source-ref HEAD
```

## Mit elemez?

- Minden lokális és remote ref/branch által elérhető commitot (`git log --all`).
- Azonos commitot egy repón belül csak egyszer számol, akkor is, ha több branch része; külön repókban külön hozzájárulásként jelenik meg.
- Szerzőt és `Co-authored-by` társszerzőt; a csak review/test/sign-off szerepben megjelenő személyek nem kerülnek a fejlesztői riportba.
- Commit-, nem-merge/merge-, fájl-, hozzáadott/törölt sor-, aktív nap- és időszakadatokat.
- A repó `.mailmap` fájlját identitás-egyesítéshez, majd repókon átívelően az azonos normalizált e-mailt vagy nevet. A név összehasonlításakor a kis-/nagybetű, a szóköz és az ékezet nem számít; az összes eredeti név- és e-mail-alias megmarad a fejlesztői adatlapon.

## A részletes riport tartalma

- Összesítő táblázat minden közreműködőről, valamint külön fejlesztői adatlapok.
- Repónkénti és havi aktivitásbontás, első/utolsó aktivitás és aktív napok.
- Minden lokális és remote branch/ref részletes listája és a commitok branch-elérhetősége.
- Szerzői és `Co-authored-by` társszerzői közreműködés.
- Fájltípusonkénti technológiai lábnyom, fájlérintés és sorszámok.
- Conventional Commit kategóriák és issue-/ticket-hivatkozást tartalmazó commitok.
- Hét napja és napszak szerinti aktivitási eloszlás.
- Auditálható, teljes commitlista hash-sel, dátummal, repóval, üzenettel és változási statisztikával.
- Commitonkénti teljes commitüzenet, fájllista és Git patch/diff, a módosított kódsorokkal.
- Külön repó-adatlap közreműködő-, branch-, fájltípus- és commitbontással.
- A teljes riport HTML és Markdown formátumban is elkészül. Minden fejlesztő egyetlen önálló `.md` fájlt kap, amelybe a saját és társszerzői commitok teljes üzenete, fájllistája és diffje is be van ágyazva.
- Minden branch teljes commitolt forráskódja külön, a branch nevét tartalmazó Markdown-fájlba kerül, fájlonkénti szeparátorokkal és metaadat-fejlécekkel.
- Nyomtatásbarát megjelenés és módszertani/értelmezési megjegyzések.

## Fontos korlátok

A Git-statisztika nem önálló teljesítménymérés. A squash merge, generált fájlok, formázás, páros munka, review, mentoring, tervezés, incidenskezelés és a feladatok eltérő nehézsége erősen befolyásolja az adatokat. A riportot emberi, kontextusos értékelés egyik bemeneteként érdemes használni, automatikus rangsorolásra vagy munkaviszonyt érintő döntésre nem.

Az összes távoli branch csak akkor látható, ha a repó remote refjei naprakészek. A `--fetch` kapcsoló minden megtalált repóban automatikusan lefuttatja a `git fetch --all --prune` parancsot az elemzés előtt. Alapértelmezésben kikapcsolt, mert hálózati hozzáférést és hitelesítést igényelhet, valamint remote-tracking refeket törölhet.

Review-kommenteket és hosting szolgáltatói beszélgetéseket a riport nem dolgoz fel; a commitokban ténylegesen rögzített kódváltozásra koncentrál.

## Adatvédelem és biztonság

A generált riportok neveket, e-mail-címeket, commitüzeneteket, fájlútvonalakat, teljes diffeket és akár a teljes commitolt forráskódot is tartalmazhatják. A kimeneti könyvtárat ezért ugyanúgy kell védeni, mint magát a forráskódot. A program nem küld adatot külső szolgáltatásnak; hálózati kapcsolatot csak a külön bekapcsolt `--fetch` okozhat a Git remote-ok felé.

A programban nincs fejlesztői név vagy e-mail-címhez kötött összevonási szabály. Az identitásokat minden futáskor kizárólag a vizsgált Git-adatokból, a `.mailmap` eredményéből, valamint a dokumentált név- és e-mail-normalizálásból építi fel.

## Projektellenőrzés

Az automatikus tesztcsomag valódi, ideiglenes Git-repót is létrehoz, commitokat készít, majd ellenőrzi a HTML-, Markdown- és branchenkénti forráskód-kimenetet. A tesztadatok fiktív `test.invalid` címeket használnak. A részletek és a kézi TUI-ellenőrzési lista a [tesztelési útmutatóban](docs/TESTING.md) található.
