# Használat

## Parancssori mód

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root "C:\projektek" `
  --output "C:\riport" `
  --since 2026-01-01 --until 2026-12-31 `
  --fetch --quality
```

| Kapcsoló | Alapérték | Jelentés |
|---|---|---|
| `--root <útvonal>` | `.` | A rekurzívan vizsgált gyökérkönyvtár. A nem repó alkönyvtárak automatikusan kimaradnak. |
| `--output <útvonal>` | `report` | A kibontott HTML-riport helye. |
| `--database <fájl>` | `<output>/report.sqlite` | Hordozható cache és HTML-archívum. |
| `--render-db <fájl>` | nincs | HTML visszaállítása SQLite-ból Git-elemzés nélkül. |
| `--since <dátum>` | nincs | Alsó commitdátum-határ; ajánlott példa: `2026-01-01`. |
| `--until <dátum>` | nincs | Felső commitdátum-határ; ajánlott példa: `2026-12-31`. |
| `--title <szöveg>` | beépített cím | A HTML-riport címe. |
| `--no-patches` | kikapcsolva | Nem gyűjti és nem jeleníti meg a teljes commit diffeket. |
| `--fetch` | kikapcsolva | Repónként `git fetch --all --prune`. |
| `--quality` | kikapcsolva | PMD/CPD commitminősítés és tartós cache. |
| `-i`, `--interactive` | kikapcsolva | Teljes képernyős Lanterna felület. |
| `-h`, `--help` | – | Részletes súgó. |
| `-V`, `--version` | – | Verzió. |

A korábbi `--outputs`, `--source-only`, `--source-branches`, `--source-ref` és Markdown-méret kapcsolók megszűntek. A kimenet HTML + SQLite.

## Interaktív mód

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

A kétpaneles felületen a gyökér, kimenet és SQLite-fájl, a dátumok, a teljes diff, a fetch és a PMD/CPD állítható. A gyökér és a kimenet terminálos fájlböngészőből választható; a dátumokhoz gyorslisták és év/hónap/nap választó tartozik.

- `Tab`, `Shift+Tab`: mezők közötti navigáció.
- `Enter`: gomb vagy lista aktiválása.
- `Space`: jelölőnégyzet.
- `Alt+F`, `Alt+B`, `Alt+S`: Fájl, Beállítások, Súgó menü.
- `F1`: részletes témakörös súgó; `F2`: generálás; `F3`: HTML visszaállítása SQLite-ból; `F4`: alapértékek; `F10`: kilépés.

A progress fázist, aktuális repót/commitot/fájlt, elemszámot, kiírt méretet, eltelt időt és `Aktív szál: futó/max` értéket mutat. A repóelemzés, PMD/CPD, snapshot és HTML-oldalírás külön korlátozott poolokon fut.

## Dashboard

A `dashboard.html` alapértelmezésben fejlesztőnként csoportosít. Minden fejlesztő saját színt kap, a jelmagyarázat minden sorozatban tartalmazza a nevét, például `Kiss Anna · + sor`. Az `Összehasonlítás` listában összesített nézet is választható.

Az időszak, napi/heti/havi felbontás, egy vagy több fejlesztő, repó, branch és mutató szűrhető. Grafikonpontra kattintva az adott időszak és – fejlesztőnkénti sorozatnál – az adott fejlesztő commitjai jelennek meg.

## SQLite és folytatás

A PMD/CPD eredmény a repository stabil azonosítója, commit hash és elemzőverzió alapján cache-elődik. Egy új futás csak a hiányzó commitokat elemzi. A lezárt adatbázis másik gépre másolható; a folytatáshoz ugyanazt add meg `--database` értékként.

```powershell
# Elemzés/folytatás
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root C:\projektek --output C:\riport --database D:\adat\report.sqlite --quality

# Csak a tárolt HTML visszaállítása
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --render-db D:\adat\report.sqlite --output D:\adat\html
```

Futó program mellett ne csak a fő `.sqlite` fájlt másold, mert WAL-módban ideiglenes `-wal`/`-shm` fájl is létezhet. A program szabályos befejezése checkpointol és lezárja az adatbázist.

## Git fetch mellékhatása

A `--fetch` nem vált branchet, nem merge-el és nem írja át a munkafájlokat. Frissíti a remote-tracking refeket és a `FETCH_HEAD`-et; a `--prune` eltávolítja a remote-on törölt ágak helyi követő refjeit. Hálózatot és hitelesítést igényelhet. Sikertelenség esetén a program figyelmeztet, majd a helyi adatokkal folytatja.

## Teljesítmény

Nagy történetnél először használj `--since`/`--until` időablakot. A `--no-patches` csökkenti a kimenetet, a `--quality` viszont történelmi forrásrekonstrukciót és statikus elemzést igényel. Az adatbázis-cache miatt a következő futás jellemzően sokkal gyorsabb.
