# Tesztelési útmutató

## Előfeltételek

- Java 21 vagy újabb;
- Maven 3.9 vagy újabb;
- Git 2.x a `PATH`-on.

A Git nemcsak az alkalmazás, hanem a végponttól végpontig teszt előfeltétele is. Az integrációs teszt minden futáskor a JUnit ideiglenes könyvtárában hoz létre repót, ezért nem módosít valódi projektet vagy globális Git-konfigurációt.

## Automatikus tesztek

Gyors futtatás:

```powershell
mvn test
```

Tiszta fordítás és teljes Maven lifecycle-ellenőrzés:

```powershell
mvn clean verify
```

A Surefire szöveges és XML eredményei a `target/surefire-reports` könyvtárba kerülnek. A `target` generált könyvtár, nincs verziókezelésben.

## Mit ellenőriz a tesztcsomag?

| Réteg | Ellenőrzés |
|---|---|
| CLI | Alapértékek, rövid/hosszú opciók, hibás értékek, kimenet- és branchlista, Markdown-méretek, magyar súgó. |
| TUI-segédek | Branchlista, könyvtárválasztó kezdőpont, súgótémák, Alt-menükódok és szövegrövidítés. |
| Termináladapter | Tab, CR/LF Enter, kontrollkarakterek és magyar Unicode karakterek. |
| Identitások | Azonos e-mail, normalizált név, ékezet/case/whitespace és tranzitív aliaslánc. |
| Modell/writer | HTML- és Markdown-escaping, stabil slugok, forrásnyelv-felismerés. |
| Repófelderítés | Normál `.git` könyvtár, bare repó, worktree `.git` pointer és output könyvtár kizárása. |
| Markdown-darabolás | Bájtlimit, UTF-8 épség, kódkerítések egyensúlya és régi részek takarítása. |
| Folyamatjelzés | Súlyozott, monoton százalék, fázisok és részszámlálók. |
| Integráció | Valódi Git commitokból HTML/Markdown/source, diff és társszerző; nem commitolt tartalom kizárása; source-only út. |

Az integrációs fixture fiktív neveket és `test.invalid` e-mail-címeket használ. Nincs valódi fejlesztői név vagy e-mail a tesztkódban.

## Csomagolási és CLI smoke teszt

```powershell
mvn clean package
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --version
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --help
```

Elvárt eredmény:

- a build `BUILD SUCCESS` üzenettel zárul;
- az összes JUnit teszt sikeres;
- a verzió és a magyar, ékezethelyes súgó megjelenik;
- a létrejött JAR közvetlenül, külön classpath nélkül fut.

## Kézi CLI mátrix

Egy kisméretű, nem érzékeny tesztrepón ellenőrizd:

```powershell
# Csak HTML, diff nélkül
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\teszt\repo --output C:\teszt\riport-html --outputs html --no-patches

# Markdown és darabolás
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\teszt\repo --output C:\teszt\riport-md --outputs markdown --max-md-size 16KB

# Kiválasztott branchek teljes forrása
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\teszt\repo --output C:\teszt\riport-source --source-only `
  --source-branches main,develop

# Egyetlen commit/ref
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\teszt\repo --output C:\teszt\riport-ref --source-only --source-ref HEAD
```

Ellenőrizd, hogy az indexlinkek megnyílnak, a kódblokkban látszik a tényleges módosítás, a source export nem tartalmaz dirty worktree tartalmat, és a `.part-NNN.md` fájlok nem nagyobbak a megadott limitnél.

## Kézi interaktív ellenőrzés

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

Ellenőrzési lista:

1. `Tab` és `Shift+Tab` minden vezérlőn végighalad.
2. `Enter` aktiválja a gombot/listaelemet, `Space` kapcsolja a checkboxot.
3. `Alt+F`, `Alt+B`, `Alt+S`, valamint `F1`, `F2`, `F4`, `F10` működik.
4. A root/output tallózó kiválasztott könyvtára visszakerül a mezőbe.
5. A dátumválasztó preset és év/hónap/nap listája helyes értéket ír be.
6. A branchmódok és az egyedi lista/ref mező engedélyezése követi a választást.
7. Az MD-limit listában a `500 MB`, `750 MB` és `1024 MB` érték elérhető.
8. Generálás közben változik a fázis, százalék, részszámláló, kiírt méret és eltelt idő.
9. A magyar karakterek helyesek, a napló görgethető, a felület futás közben sem fagy meg.

## Hibakeresés

Részletes stack trace:

```powershell
$env:DEV_REPORT_DEBUG = "1"
mvn test
```

Egy tesztosztály vagy tesztmetódus futtatása:

```powershell
mvn -Dtest=GitReportApplicationIntegrationTest test
mvn -Dtest=CliOptionsTest#parsesHumanReadableMarkdownLimits test
```

Ha az integrációs teszt Git-hibával áll le, először futtasd a `git --version` parancsot. A teszt csak repository-local `user.name` és `user.email` beállítást ír az ideiglenes repóba; a globális konfigurációt nem módosítja.
