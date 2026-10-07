# Kimenetek

Minden szöveges kimenet UTF-8 kódolású. A fájlnevek stabil, ékezetmentes slugból készülnek; névütközéskor `-2`, `-3`, … utótag biztosítja az egyediséget.

## Teljes könyvtárszerkezet

```text
report/
├── index.html                 # ha html kimenet aktív
├── index.md                   # ha markdown kimenet aktív
├── style.css                  # önálló HTML-stíluslap
├── developers/
│   ├── <fejlesztő>.html
│   └── <fejlesztő>.md
├── repositories/
│   ├── <repó>.html
│   └── <repó>.md
├── commits/
│   ├── <repó>-<hash>.html
│   └── <repó>-<hash>.md
├── source-code-index.md       # ha source kimenet aktív
├── source-code-<branch>.md
└── *.part-001.md              # csak beállított méretkorlát túllépésekor
```

Több repó forrásexportjánál a fájlnév a repó slugját is tartalmazza: `source-code-<repó>--<branch>.md`.

## HTML

A `--outputs html` az alábbiakat hozza létre:

```text
index.html
style.css
developers/*.html
repositories/*.html
commits/*.html
```

Az `index.html` az összesítő belépőpont. A HTML önállóan megnyitható, külső JavaScriptet vagy webes stíluslapot nem igényel.

Az összesítő fejlesztői és repónkénti táblázatokat, módszertani megjegyzéseket és figyelmeztetéseket tartalmaz. A fejlesztői oldalakon identitásaliasok, aktivitási időszakok, havi/repónkénti bontások, technológiai lábnyom, ritmusadatok és a commitlista jelenik meg. A commitoldalak teljes üzenetet, fájllistát, módosított kódrészleteket és – ha engedélyezett – teljes Git diffet tartalmaznak.

## Markdown

A `--outputs markdown` szerkezete:

```text
index.md
developers/*.md
repositories/*.md
commits/*.md
```

Minden fejlesztő egyetlen saját Markdown-fájlt kap. Patch-ek engedélyezésekor ebben a hozzá tartozó commitok teljes üzenete, fájllistája, hozzáadott/törölt kódja és teljes diffje is szerepel.

A repónkénti és commitonkénti Markdownok ugyanazt az elemzési modellt használják, mint a HTML. A Markdown linkek relatívak, ezért a teljes kimeneti könyvtár együtt mozgatható.

## Branchenkénti forráskód

A `--outputs source` kimenete:

```text
source-code-index.md
source-code-master.md
source-code-origin-develop.md
source-code-origin-feature-valami.md
...
```

A fájlnév a branch nevének fájlrendszer-biztos alakját tartalmazza. Az index megőrzi az eredeti branchnevet és a pontos commit-hasht.

A kiírt branchek a `--source-branches` opcióval választhatók: `all`, `local`, `remote`, vagy vesszővel elválasztott pontos lista, például `main,develop,origin/release`. A `--source-ref` továbbra is egyetlen branch, tag vagy commit exportjára szolgál, és elsőbbséget élvez a listával szemben.

Minden branchfájlban a Git-fában szereplő blobok sorrendben jelennek meg. Egy szöveges fájl blokkja tartalmazza:

- a repó nevét;
- a relatív útvonalat, mappát és fájlnevet;
- a Git blob-hasht és módot;
- a bájtméretet és a Markdown nyelvi jelölést;
- a teljes szöveges tartalmat dinamikusan méretezett kódkerítésben.

A bináris bloboknál a metaadat bekerül, a bináris bájtok nem. A tartalom a Git objektum-adatbázisából származik, ezért a munkakönyvtár nem commitolt állapota nem módosítja.

A source index repónként és branchenként megadja az eredeti refet, a pontos commit-hasht és a létrehozott fájl linkjét. Szimbolikus remote `HEAD` ref nem készít külön exportot.

## Kimenetek együttes használata

Az `--outputs html,markdown,source` mindhárom csoportot elkészíti. A HTML- és Markdown-index csak akkor linkeli a `source-code-index.md` fájlt, ha a `source` is a kiválasztott kimenetek között van.

## Markdown-darabolás

A `--max-md-size` minden létrehozott `.md` dokumentumra vonatkozik. Túllépéskor az eredeti fájl egy részjegyzékké alakul, a tartalom pedig `.part-001.md`, `.part-002.md`, … fájlokba kerül. A daraboló:

- UTF-8 kódponthatáron vág;
- nem hagy lezáratlan bekerített kódblokkot;
- a következő részben újranyitja az átnyúló kódblokkot;
- újrafuttatás előtt eltávolítja az adott dokumentum korábbi részeit;
- a részeket is a beállított bájtkorlát alatt tartja.

## Felülírás és adatkezelés

A generált fájlnevek fenntartottak. Az alkalmazás a kiválasztott formátumhoz tartozó korábbi generált oldalakat takarítja, de tetszőleges más fájlokat nem töröl. Érdemes külön, kizárólag riportcélú könyvtárat megadni.

A kimenet személyes és üzleti szempontból érzékeny lehet: e-mail-címeket, commitüzeneteket, diffeket és teljes forráskódot tartalmazhat. Ne publikáld automatikusan, és ne helyezd nyilvános webkiszolgálóra felülvizsgálat nélkül.
