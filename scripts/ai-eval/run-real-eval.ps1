param(
    [string]$ReportDate = (Get-Date -Format "yyyy-MM-dd"),
    [string]$DbSuffix = (Get-Date -Format "yyyyMMdd")
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$EnvFile = Join-Path $RepoRoot ".env"
$EvalDb = "rs_eval_$DbSuffix"
$PG_BIN = "D:\DevTools\pgsql\bin"
$PG_DATA = "D:\DevTools\.verification-a1b92b7686f2482f862ec999434068ba\pg-data"
$M2_HOME = "D:\DevTools\apache-maven-3.9.16"

if (-not (Test-Path $EnvFile)) {
    throw ".env file not found at $EnvFile"
}

Get-Content $EnvFile | ForEach-Object {
    if ($_ -match "^\s*([^#=]+?)\s*=\s*(.*)\s*$") {
        $k = $Matches[1].Trim()
        $v = $Matches[2].Trim()
        [Environment]::SetEnvironmentVariable($k, $v, "Process")
    }
}

if (Test-Path "$PG_DATA\postmaster.pid") { Remove-Item "$PG_DATA\postmaster.pid" -Force -ErrorAction SilentlyContinue }
& "$PG_BIN\pg_ctl.exe" -D $PG_DATA -l "D:\DevTools\.verification-a1b92b7686f2482f862ec999434068ba\postgres.log" -w start
try {
    & "$PG_BIN\psql.exe" -h 127.0.0.1 -U verification -d postgres -c "DROP DATABASE IF EXISTS $EvalDb;"
    & "$PG_BIN\psql.exe" -h 127.0.0.1 -U verification -d postgres -c "CREATE DATABASE $EvalDb;"
    $env:DB_URL = "jdbc:postgresql://127.0.0.1:5432/$EvalDb"
    $env:DB_USER = "verification"
    $env:DB_PASSWORD = ""
    $env:M10B_REAL_EVAL = "true"
    $env:M10B_EVAL_REPORT = Join-Path $RepoRoot "docs\M10b质量评测_$ReportDate.md"

    java -classpath "$M2_HOME\boot\plexus-classworlds-2.11.0.jar" "-Dclassworlds.conf=$M2_HOME\bin\m2.conf" "-Dmaven.home=$M2_HOME" "-Dmaven.multiModuleProjectDirectory=$RepoRoot\backend" org.codehaus.plexus.classworlds.launcher.Launcher -f "$RepoRoot\backend\pom.xml" test "-Dtest=AiQualityEvalTest"
} finally {
    & "$PG_BIN\pg_ctl.exe" -D $PG_DATA -m fast -w stop
}
