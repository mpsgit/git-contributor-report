<#ftl output_format="HTML" strip_whitespace=true>
<!doctype html>
<html lang="hu">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>${title}</title>
  <link rel="stylesheet" href="${resourcePrefix}style.css">
</head>
<body>
  <main>${body?no_esc}</main>
  <footer>Git Contributor Report · A forrásadat a Git történet · Leíró statisztika, nem automatikus teljesítményértékelés.</footer>
</body>
</html>
