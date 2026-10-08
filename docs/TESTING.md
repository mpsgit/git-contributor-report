# Tesztelés

## Automatizált ellenőrzés

```powershell
mvn test
mvn clean verify
```

A `verify` a unit/integrációs tesztek mellett a csomagolt, minden függőséget tartalmazó JAR-t is elindítja. Ez külön védi a Lanterna dialógusosztályokat a Shade minimalizálási hibától.

| Terület | Ellenőrzés |
|---|---|
| CLI | HTML/SQLite alapértékek, dátumok, `--database`, `--render-db`, interaktív flag, megszűnt opciók elutasítása, magyar súgó. |
| Identitás | Azonos e-mail vagy normalizált név tranzitív összevonása; ékezet, szóköz és kis-/nagybetű kezelése. |
| Git integráció | Valódi ideiglenes repók, lokális/remote branchek, társszerző, diff, nem commitolt tartalom kizárása. |
| Dashboard | Dátum-, fejlesztő-, repó- és branch-szűrők; fejlesztőnkénti csoportosítás; névvel jelölt sorozatok; PMD/CPD és drill-down adatok. |
| Snapshot | Deduplikált offline blobok, commit/fájl index és előtte/utána kód. |
| SQLite | Cache mentés-visszaolvasás, bezárás utáni hordozhatóság, GZIP HTML-archívum, Markdown kizárása, teljes HTML-visszaállítás. |
| PMD/CPD | Java, SQL, HTML, JavaScript, TypeScript, PL/SQL, pontszám és finding részletek. |
| Párhuzamosság | Worker-korlát, aktívszál-jelzés és repók közötti identitásegyesítés. |
| Csomagolt JAR | `--help`, `--version`, Lanterna `MessageDialogButton` jelenléte. |

## Kézi smoke teszt

```powershell
mvn clean verify

java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --help

java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\teszt\repok --output C:\teszt\riport `
  --since 2026-01-01 --until 2026-12-31 --quality

java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --render-db C:\teszt\riport\report.sqlite `
  --output C:\teszt\visszaallitott
```

Ellenőrizd:

1. Az `index.html`, `dashboard.html`, `report.sqlite` és az offline snapshot megvan.
2. A dashboard hálózat nélkül betöltődik.
3. Az `Összehasonlítás` alapértéke `Fejlesztőnként`.
4. A jelmagyarázatban minden sorozat a fejlesztő nevével kezdődik, és a személyek különböző színűek.
5. Egy fejlesztői sorozat pontjára kattintva csak az adott személy adott időszaki commitjai jelennek meg.
6. Fejlesztő-, repó-, branch- és from/to szűrés után a grafikon és az összesítő kártyák frissülnek.
7. A commitoldalon látszik az előtte/utána kód és a snapshot link.
8. A visszaállított HTML fájljai megegyeznek az archivált változattal.

## Interaktív felület

Indítsd `-i` kapcsolóval, majd ellenőrizd a `Tab`, `Shift+Tab`, nyilak, `Enter`, `Space`, `Alt+F`, `Alt+B`, `Alt+S`, `F1`, `F2`, `F3`, `F4`, `F10` működését. Az F3-nak a megadott vagy alapértelmezett SQLite-fájlból elemzés nélkül kell visszaállítania a HTML-t. A fájlböngészőből válassz gyökeret és kimenetet, a dátumválasztóból fix értéket, majd generálás alatt figyeld az aktuális fázist és az `Aktív szál` számlálót.

## Tesztadatok

Az automatizált tesztek ideiglenes könyvtárakat és fiktív `test.invalid` e-mail-címeket használnak. Valós fejlesztői név vagy e-mail nincs hard code-olva az alkalmazás logikájában.
