param(
    [string]$Root = ".",
    [string]$Output = (Join-Path $PWD "report"),
    [string]$Since,
    [string]$Until,
    [string]$Title = "Git fejlesztői közreműködés",
    [string]$MaxMdSize,
    [string[]]$SourceBranches = @("all"),
    [string]$SourceRef,
    [ValidateSet("html", "markdown", "source")][string[]]$Outputs = @("html", "markdown", "source"),
    [switch]$Fetch,
    [switch]$NoPatches,
    [switch]$SourceOnly,
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
    if ($Since) { $arguments += @("--since", $Since) }
    if ($Until) { $arguments += @("--until", $Until) }
    if ($Outputs) { $arguments += @("--outputs", ($Outputs -join ",")) }
    if ($MaxMdSize) { $arguments += @("--max-md-size", $MaxMdSize) }
    if ($Fetch) { $arguments += "--fetch" }
    if ($NoPatches) { $arguments += "--no-patches" }
    if ($SourceOnly) { $arguments += "--source-only" }
    if ($Interactive) { $arguments += "--interactive" }
    if ($SourceRef) { $arguments += @("--source-ref", $SourceRef) }
    if ($SourceBranches) { $arguments += @("--source-branches", ($SourceBranches -join ",")) }
    & java @arguments
} finally {
    Pop-Location
}
