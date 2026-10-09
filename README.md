# Git Contributor Report

Java 21-es parancssori és Midnight Commander-stílusú terminálalkalmazás Git-fejlesztői aktivitás elemzéséhez. Egy gyökérkönyvtár alatt rekurzívan felismeri a normál, bare és worktree repókat, minden helyben elérhető lokális és remote ref commitjait egy közös HTML-riportba rendezi, az eredményt pedig hordozható SQLite-adatbázisban is megőrzi.

A dedikált offline dashboard napi, heti vagy havi bontásban, névvel és külön színnel jelölt fejlesztői sorozatokon hasonlítja össze a hozzáadott/törölt sorokat, commitokat, fájlérintéseket és PMD/CPD eredményeket. A darabszám-alapú tengelyek lineáris vagy 10-es alapú logaritmikus skálán jeleníthetők meg; a PMD/CPD-score megtartja a saját 0–100-as lineáris tengelyét. Dátum, fejlesztő, repó és branch szerint többszörösen szűrhető; egy grafikonpontra kattintva megnyitható a commitlista, az előtte/utána kód és az adott commit offline forrássnapshotja.

## Dokumentáció

- [Használat és CLI](docs/USAGE.md)
- [HTML- és SQLite-kimenet](docs/OUTPUTS.md)
- [Architektúra](docs/ARCHITECTURE.md)
- [Tesztelés](docs/TESTING.md)

## Követelmények

- Java 21+
- Maven 3.9+
- Git 2.x

A Maven csak a buildhez kell. A Shade pluginnal készülő futtatható JAR tartalmazza a Java-függőségeket; a `git` futás közben külső parancsként szükséges.

## Build és gyors kezdés

```powershell
git clone git@github.com:mpsgit/git-contributor-report.git
cd git-contributor-report
mvn clean verify
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --help
```

Normál CLI futtatás:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root "C:\munka\projektek" `
  --output "C:\munka\riport" `
  --since 2026-01-01 --until 2026-12-31 `
  --fetch --quality
```

Teljes képernyős interaktív mód:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

Windows PowerShell wrapper:

```powershell
.\run.ps1 -Root "C:\munka\projektek" -Output "C:\munka\riport" -Interactive
```

## Kimenet és folytatható feldolgozás

A program kizárólag HTML-riportot készít; a korábbi Markdown- és branch-forrásexport megszűnt. Az alapértelmezett tartós adatbázis `<output>/report.sqlite`, más hely a `--database` kapcsolóval adható meg:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --database "D:\riport-adatok\csapat.sqlite"
```

Az SQLite-adatbázis tömörítve tartalmazza a teljes generált HTML-oldalkészletet és a commitonkénti PMD/CPD cache-t. A sikeresen elkészült minősítések azonnal mentésre kerülnek, ezért megszakítás vagy másnapi/másik gépes folytatás esetén nem kell őket újraszámolni. A lezárt `.sqlite` fájl önmagában másolható.

HTML visszaállítása Git-repók és új elemzés nélkül:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --render-db "D:\riport-adatok\csapat.sqlite" `
  --output "D:\riport-adatok\visszaallitott-html"
```

Belépőpontok:

- `index.html` – összesítés, fejlesztők, repók és teljes branch/ref-leltár;
- `dashboard.html` – fejlesztőnkénti összehasonlító grafikonok és drill-down;
- `developers/`, `repositories/`, `commits/` – részletes adatlapok;
- `snapshot.html`, `snapshot-data.js`, `snapshots/` – offline forrásböngészés;
- `report.sqlite` – hordozható cache és HTML-archívum.

Az ECharts JavaScript helyben kerül kiírásra, ezért a dashboard internetkapcsolat nélkül is működik.

## Mit elemez?

- Minden `git log --all` által elérhető commitot; egy repón belül ugyanazt a commitot csak egyszer.
- Minden lokális és remote-tracking branchet/refet, valamint a commitok aktuális branch-elérhetőségét.
- Szerzőt és `Co-authored-by` társszerzőt; review-kommenteket nem.
- Commit-, merge-, fájl-, hozzáadott/törölt sor-, aktív nap-, technológia- és időbeli adatokat.
- A `.mailmap` eredményét, majd az azonos normalizált e-mailt vagy nevet. Névnél a kis-/nagybetű, ékezet és szóköz nem hoz létre külön profilt.
- Opcionálisan PMD 7 + CPD minősítést Java, SQL, HTML, JavaScript, TypeScript és PL/SQL kódra.

A `--fetch` minden felismert repóban `git fetch --all --prune` parancsot futtat. Nem checkoutol és nem merge-el, de frissíti a remote-tracking refeket és eltávolíthatja a remote-on már nem létező követő refeket.

## Technológiák

- picocli, Lanterna 3, JLine Native, Jansi
- Apache FreeMarker, Commons Text, Commons IO
- Apache ECharts WebJar
- PMD 7 és CPD
- Xerial SQLite JDBC és Jackson
- JUnit Jupiter, Maven Surefire/Failsafe, Maven Shade Plugin

A repóelemzés, PMD/CPD, snapshotkészítés és HTML-oldalírás korlátozott worker poolokon párhuzamos. A konzol és a TUI az aktuális fázist, elemszámot, eltelt időt és az `Aktív szál: futó/max` értéket is mutatja.

## Fontos értelmezési korlát

A Git-statisztika és a PMD/CPD pontszám leíró technikai adat, nem automatikus teljesítményértékelés. A squash merge, generált kód, formázás, páros munka, mentoring, tervezés, incidenskezelés és a feladatok eltérő nehézsége torzíthatja az összehasonlítást. A riport neveket, e-mail-címeket, commitüzeneteket, diffeket és forráskódot tartalmazhat; bizalmas fejlesztési adatként kezelendő.
