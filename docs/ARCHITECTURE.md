# Architektúra

## Fő komponensek

| Komponens | Feladat |
|---|---|
| `Main.java`, `CliOptions.java` | Indítás, picocli opciók, Unicode konzolkimenet. |
| `InteractiveConsole.java` | Lanterna/JLine teljes képernyős TUI, menük, mezők, böngésző, progress. |
| `GitReportApplication.java` | Repókeresés, Git parancsok, párhuzamos vezérlés, identitás- és branch-elemzés. |
| `ReportModel.java` | Commit-, fejlesztő-, repó-, branch- és minőségi domainmodell. |
| `CommitQualityAnalyzer.java` | PMD/CPD elemzés, hozzáadott sorokra szűkítés és cache-kapcsolat. |
| `ReportDatabase.java` | SQLite séma, WAL, minőségi cache, tömörített HTML-archívum és visszaállítás. |
| `ReportWriter.java` | HTML-oldalak, fejlesztői idősorok, összehasonlító dashboard és ECharts konfiguráció. |
| `HtmlSnapshotExporter.java` | Commitfák offline, blob-deduplikált HTML snapshotja. |
| `ParallelSupport.java` | Névvel ellátott korlátozott worker poolok és aktívszál-számlálás. |
| `ProgressReporter.java` | Monoton, súlyozott, fázis- és részfeladat-szintű progress. |

## Adatfolyam

```text
CLI / TUI
  -> repók rekurzív felismerése
  -> opcionális párhuzamos fetch
  -> repónkénti git log --all, ref- és diff-feldolgozás
  -> globális identitás-egyesítés
  -> opcionális PMD/CPD (SQLite cache-ből vagy párhuzamos számításból)
  -> offline snapshot és párhuzamos HTML-oldalírás
  -> HTML/CSS/JS eszközök tömörített archiválása SQLite-ba
```

A `--render-db` rövid út: megnyitja az SQLite-ot, ellenőrzi a relatív útvonalakat, majd az archivált HTML-eszközöket ideiglenes fájlon keresztül írja ki.

## Git-modell

A natív Git kliens biztosítja a `.mailmap`, ref, attribútum, diff és objektumkezelés kompatibilitását. Az elemzés minden `refs/heads/*` és `refs/remotes/*` ágat leltároz, a történetet `git log --all` adja. A Git nem őrzi megbízhatóan a commit eredeti branchét; a riport ezért aktuális elérhetőséget mutat.

Az identitás-egyesítés e-mail-egyezés vagy normalizált névegyezés alapján tranzitív. A név Unicode NFKD normalizálás után ékezet-, kis-/nagybetű- és szóköz-független; az eredeti aliasok megmaradnak.

## Dashboard adatmodell

A dashboard tömör JSON-adatot ágyaz az oldalba, indexelt fejlesztő-, repó- és branch-listával. A kliensoldali aggregáció napi, ISO-heti vagy havi bucketet képez. Fejlesztői módban minden kiválasztott személy külön ECharts sorozatokat kap, egységes személyszínnel és névvel ellátott jelmagyarázattal. A sorozat neve a drill-downhoz vissza van rendelve a fejlesztőre.

## Párhuzamosság

A poolméret a feladatszám, processzorszám és fáziskorlát minimuma. A repók, PMD/CPD commitok, snapshotok és HTML-oldalak külön poolokat használnak. A progress callback thread-safe aggregációt végez, és az aktív/max worker-számot a felhasználónak is megmutatja.

## SQLite

Az Xerial JDBC driver helyi SQLite-fájlt használ. A `quality_cache` elsődleges kulcsa a repository azonosító, commit hash és analyzer kulcs. Az eredmény Jackson JSON, így a séma egyszerű marad, de a részletes findings visszaállíthatók. A `html_asset` relatív út, médiatípus, eredeti/tömörített méret és GZIP blob alapján tárol.

## Biztonság

A Git parancsok argumentumlistával indulnak, nem shell-parancssztringként. Az SQLite-ból visszaállított relatív út nem léphet ki a célkönyvtárból. A remote URL-ből felhasználó/jelszó komponens nem kerül a riportba. Minden HTML-adat escape-elve, minden JSON-adat JavaScript-biztosan kerül kiírásra.
