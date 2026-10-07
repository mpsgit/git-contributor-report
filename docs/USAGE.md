# Használati útmutató

## Interaktív, teljes képernyős mód

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar --interactive
```

A Lanterna felület minden funkciót elérhetővé tesz: gyökér- és kimeneti könyvtár, cím, dátumszűrés, HTML/Markdown/forrás kimenetek, teljes commit diff, `git fetch --all --prune`, valamint `all`/`local`/`remote`, egyedi branchlista vagy egyetlen Git ref. A gyökér- és kimeneti útvonal a `Tallóz` gombbal fájlrendszer-böngészőből választható. A dátummezők melletti `Választ` gomb gyors dátumlistát, valamint külön év/hónap/nap listákat nyit; a mezőben továbbra is kézzel megadható bármely Git által elfogadott dátumkifejezés. A beállítások két keretes panelen látszanak, a futás állapota és teljes konzolnaplója pedig az alsó panelen követhető.

Billentyűk: `Tab`/`Shift+Tab` a mezőváltás, nyilak a listák és szövegmezők kezelése, `Space` a jelölés, `Enter` az aktiválás, `Alt+F` a Fájl, `Alt+B` a Beállítások, `Alt+S` a Súgó menü közvetlen megnyitása, `F1` részletes súgó, `F2` generálás, `F4` alapértékek és `F10` kilépés. Az interaktív mód valódi konzolhoz készült; automatizáláshoz, pipe-hoz és átirányításhoz a normál CLI használandó.

Az `F1` súgóablak 11 választható témában magyarázza el a gyors kezdést, navigációt, repókeresést, dátumszűrést, kimeneteket, branch- és forráskód-exportot, Markdown-darabolást, fetch működését, a riport helyes értelmezését, teljesítmény/adatvédelem kérdéseit és a CLI használatát. A témakör listája `Enter`-rel nyitható, a leírás a nyilakkal görgethető, az ablak `Esc` vagy `F1` billentyűvel zárható.

Generáláskor a folyamatjelző nem egyszerű repószámláló: a ténylegesen bekapcsolt munkafázisokat súlyozza. Az állapotsor megmutatja az aktuális és összes fázist, százalékot, eltelt időt, valamint a feldolgozott repót, commitot, branchet vagy forrásfájlt és annak részszámlálóját. Riportíráskor külön látható a fejlesztői adatlap, a beágyazott commit, a repóoldal, a commitoldal, a HTML/Markdown művelet, a kiírt adatmennyiség és a Markdown méretellenőrzés fájlszámlálója. Ha egyetlen nagy fájl művelete hosszabb ideig tart, az eltelt idő másodpercenként frissülő „dolgozik” jelzése mutatja, hogy a program él. Külön fázis a Git ellenőrzése, repókeresés, opcionális fetch, történetelemzés, riportoldalak írása, opcionális forráskód-export és befejezés. Hiba esetén az utolsó elért százalék megmarad, így látható, hol szakadt meg a futás.

A CLI-opciók és az interaktív mód kombinálhatók. Az opciók a felület kezdőértékei lesznek:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --interactive --root "C:\munka\projektek" --output "C:\munka\riport" `
  --outputs markdown,source --source-branches main,develop --fetch
```

## Alapparancs

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root "C:\munka\projektek" `
  --output "C:\munka\riport"
```

A `--root` alatt az alkalmazás rekurzívan keresi a normál, bare és worktree Git repókat. A kimeneti könyvtárat kihagyja a keresésből.

## Fejlesztői identitások egyesítése

A Git `.mailmap` szabályainak alkalmazása után a program közös profilba rendezi az azonos normalizált e-mail-címmel vagy azonos normalizált névvel szereplő közreműködéseket. A névnormalizálás figyelmen kívül hagyja a kis- és nagybetűket, a szóközöket, az ékezeteket és az eltérő Unicode-ábrázolást: például az `Árvíz Tűrő`, `ARVIZ  TURO` és `arvizturo` ugyanaz az identitás. A különböző név- és e-mail-változatok nem vesznek el: a fejlesztői adatlap identitásrészében aliasokként mind megjelennek. Ez különösen a több repón vagy több munkahelyi e-maillel dolgozó fejlesztők ismétlődését szünteti meg.

## Parancssori opciók

| Opció | Alapérték | Jelentés |
|---|---|---|
| `--root <útvonal>` | `.` | A vizsgált gyökérkönyvtár. |
| `--output <útvonal>` | `report` | A generált fájlok célkönyvtára. |
| `--title <szöveg>` | Git fejlesztői közreműködés | A riport címe. |
| `--since <dátum>` | nincs | Git által elfogadott alsó időhatár. |
| `--until <dátum>` | nincs | Git által elfogadott felső időhatár. |
| `--outputs <lista>` | `html,markdown,source` | A generálandó kimenetek vesszővel elválasztva. |
| `--max-md-size <méret>` | `0` | Markdown-fájlonkénti korlát (`500KB`, `10MB`, `1.5GB`); `0` = korlátlan. |
| `--no-patches` | kikapcsolva | Nem gyűjti és nem ágyazza be a commit diffeket. |
| `--fetch` | kikapcsolva | Minden repóban lefuttatja a `git fetch --all --prune` parancsot az elemzés előtt. |
| `--source-only` | kikapcsolva | A `--outputs source` rövidítése. |
| `--source-ref <ref>` | nincs | Egyetlen branch, tag vagy commit; felülírja a branch-listát. |
| `--source-branches <all\|lista>` | `all` | `all`, `local`, `remote` vagy pontos branch-nevek vesszővel elválasztva. |
| `--interactive`, `-i` | kikapcsolva | Midnight Commander-stílusú TUI; a többi CLI-opció kezdőértékként működik. |
| `--help`, `-h` | — | Beépített súgó. |

## Dátumszűrés

Az ajánlott dátumformátum `ÉÉÉÉ-HH-NN` (ISO-8601), például `2026-01-01`. Az értéket az alkalmazás a Git dátumfeldolgozójának adja át: a `--since` az alsó, a `--until` a felső időhatár.

Példa dátumtartományra:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --since 2026-01-01 --until 2026-12-31
```

Csak alsó időhatárral:

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --since 2026-01-01
```

## Kimenet-kombinációk

```powershell
# Legfeljebb 10 MB-os Markdown-fájlok
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --max-md-size 10MB

# Gyors, böngészhető riport
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --outputs html --no-patches

# Csak Markdown fejlesztői riportok, teljes commit diffekkel
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --outputs markdown

# Minden lokális branch teljes forráskódja
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --outputs source --source-branches local

# Egy konkrét remote branch
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --source-only --source-ref origin/develop

# Kiválasztott branchek
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --source-only --source-branches main,develop,origin/release

# Minden lokális és remote branch
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --source-only --source-branches all
```

## Markdown méretkorlát és darabolás

A `--max-md-size` (hosszabb nevén `--max-markdown-size`) minden generált riport- és forráskód-Markdownra vonatkozik. A korlátnál nagyobb fájl helyén egy kisméretű tartalomjegyzék marad, a tartalom pedig `.part-001.md`, `.part-002.md`, … fájlokba kerül. A darabolás UTF-8 karakterhatáron történik, és a több részen átnyúló bekerített kódblokkokat minden részben szabályosan lezárja, illetve újranyitja.

Elfogadott egységek: `B`, `KB`/`KiB`, `MB`/`MiB`, `GB`/`GiB`. Az interaktív lista többek között `500 MB`, `750 MB` és `1024 MB` értéket is kínál. A legkisebb nem nulla korlát `16KB`; `0` vagy az interaktív felületen a `korlátlan` érték kikapcsolja a darabolást.

## Remote branchek frissítése

```powershell
java -jar target/git-contributor-report-1.0.0-SNAPSHOT.jar `
  --root . --output report --fetch
```

A `--fetch` hálózati művelet. Frissíti a remote-tracking refeket és a `.git/FETCH_HEAD` fájlt; a `--prune` eltávolítja a remote-on már nem létező remote-tracking refeket. A munkakönyvtár fájljait és a lokális brancheket nem checkoutolja vagy merge-eli. Speciális, tageket a lokális tag-névtérbe leképező refspec esetén a prune tageket is törölhet. Sikertelen fetch esetén az alkalmazás figyelmeztet, majd a helyben elérhető Git-adatokkal folytatja.

## PowerShell indító

```powershell
.\run.ps1 -Root "C:\munka\projektek" `
  -Output "C:\munka\riport" `
  -Outputs html,markdown `
  -MaxMdSize 10MB `
  -SourceBranches main,develop `
  -Fetch `
  -NoPatches

# Teljes képernyős mód (a többi paraméter itt is kezdőérték)
.\run.ps1 -Interactive -Root "C:\munka\projektek" -Outputs markdown,source
```

## Nagy repók

- A `source` kimenet branchenként a teljes fát kiírja, ez több gigabájt is lehet.
- Ha csak az aktivitási adatok kellenek, használd a `--outputs html,markdown` beállítást.
- Ha a konkrét kódmódosítások nem kellenek, a `--no-patches` jelentősen csökkenti az idő- és tárigényt.
- A remote branchek csak a lokális remote refek aktuális állapotát tükrözik. Automatikus frissítéshez használd a `--fetch`, PowerShell indítónál a `-Fetch` kapcsolót.

## Hibakeresés

Részletes Java stack trace bekapcsolása:

```powershell
$env:DEV_REPORT_DEBUG = "1"
```
