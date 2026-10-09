# Kimenetek

## Könyvtárszerkezet

```text
report/
├── index.html
├── dashboard.html
├── style.css
├── echarts.min.js
├── snapshot.html
├── snapshot-data.js
├── report.sqlite
├── developers/*.html
├── repositories/*.html
├── commits/*.html
└── snapshots/blobs/*.html
```

A program nem készít Markdown-kimenetet. Friss generáláskor az általa korábban létrehozott legacy `.md` riportokat eltávolítja a kimeneti riportkönyvtárból.

## `index.html`

Az összes repó közös összesítője: közreműködők, commitok, sorok, repók, branch/ref-leltár, origin linkek és módszertani megjegyzések. Egy commit repónként egyszer számít, akkor is, ha több branchből elérhető.

## `dashboard.html`

Offline ECharts nézet az alábbiakkal:

- fejlesztőnkénti vagy összesített csoportosítás;
- minden fejlesztőhöz stabil szín és névvel ellátott sorozat/jelmagyarázat;
- from/to dátum, napi/heti/havi felbontás;
- több fejlesztő, repó és branch/ref egyidejű kiválasztása, illetve Összes;
- hozzáadott/törölt sor, commit, fájl, PMD/CPD találat és score;
- lineáris vagy 10-es alapú logaritmikus skála a darabszám-alapú tengelyekhez, külön lineáris PMD/CPD-score tengellyel;
- grafikonpontból fejlesztőre is szűkített commit drill-down;
- commitoldal, előtte/utána kód és teljes commit-snapshot link.

## Részletes oldalak

A `developers` oldalak identitás-aliasokat, repó-, havi-, napi/heti/havi grafikon-, technológia- és commitbontást tartalmaznak. A `repositories` oldalak brancheket, közreműködőket, fájltípusokat és commitokat mutatnak. A `commits` oldalak teljes üzenetet, fájllistát, előtte/utána kódot, opcionális teljes diffet és PMD/CPD eredményt adnak.

## Snapshot

A `snapshot.html` a `snapshot-data.js` index és a deduplikált `snapshots/blobs` tartalom alapján internet nélkül megmutatja egy kiválasztott commit teljes követett forrásfáját. Bináris blobnál metaadat látszik, a bináris tartalom nem ágyazódik be.

## `report.sqlite`

Az adatbázis WAL módot és normál szinkronizálást használ. Tartalma:

- metaadatok és sémaverzió;
- repository/commit/elemzőverzió kulcsú PMD/CPD cache JSON formában;
- minden `.html`, `.css` és `.js` kimeneti eszköz GZIP-tömörítve.

Szabályos lezáráskor WAL-checkpoint történik. A lezárt egyetlen SQLite-fájl másolható, archiválható és `--render-db` segítségével újra kibontó.

## Adatvédelem

A HTML és az SQLite ugyanúgy tartalmazhat nevet, e-mailt, commitüzenetet, diffet és forráskódot. Mindkettő bizalmas fejlesztési adatnak tekintendő.
