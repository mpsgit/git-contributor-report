param(
    [string]$Root = ".",
    [string]$Output = (Join-Path $PWD "report"),
    [string]$Since,
    [string]$Until,
    [string]$Title = "Git fejlesztői közreműködés",
    [string]$Database,
    [string]$RenderDb,
    [switch]$Fetch,
    [switch]$Quality,
    [switch]$NoPatches,
    [switch]$Interactive
)

$ErrorActionPreference = "Stop"
$projectDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $projectDirectory
try {
    if (-not (Test-Path "target/git-contributor-report-1.0.0-SNAPSHOT.jar")) {
        mvn package
    }
    $arguments = @("-jar", "target/git-contributor-report-1.0.0-SNAPSHOT.jar", "--root", $Root, "--output", $Output, "--title", $Title)
    if ($RenderDb) { $arguments = @("-jar", "target/git-contributor-report-1.0.0-SNAPSHOT.jar", "--render-db", $RenderDb, "--output", $Output) }
    if ($Since) { $arguments += @("--since", $Since) }
    if ($Until) { $arguments += @("--until", $Until) }
    if ($Database) { $arguments += @("--database", $Database) }
    if ($Fetch) { $arguments += "--fetch" }
    if ($Quality) { $arguments += "--quality" }
    if ($NoPatches) { $arguments += "--no-patches" }
    if ($Interactive) { $arguments += "--interactive" }
    & java @arguments
} finally {
    Pop-Location
}
