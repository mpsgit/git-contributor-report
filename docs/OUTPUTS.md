# Kimenetek

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

## Markdown

A `--outputs markdown` szerkezete:

```text
index.md
developers/*.md
repositories/*.md
commits/*.md
```

Minden fejlesztő egyetlen saját Markdown-fájlt kap. Patch-ek engedélyezésekor ebben a hozzá tartozó commitok teljes üzenete, fájllistája, hozzáadott/törölt kódja és teljes diffje is szerepel.

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

## Kimenetek együttes használata

Az `--outputs html,markdown,source` mindhárom csoportot elkészíti. A HTML- és Markdown-index csak akkor linkeli a `source-code-index.md` fájlt, ha a `source` is a kiválasztott kimenetek között van.
