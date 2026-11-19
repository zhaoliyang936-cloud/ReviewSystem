param(
  [Parameter(Mandatory=$true)][string]$Manifest,
  [string]$Report = "import-report.json"
)
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$reportPath = [System.IO.Path]::GetFullPath($Report)
$env:APP_IMPORT_ENABLED = 'true'
$env:APP_IMPORT_MANIFEST = $manifestPath
$env:APP_IMPORT_REPORT = $reportPath
& mvn -q spring-boot:run "-Dspring-boot.run.arguments=--spring.main.web-application-type=none"
exit $LASTEXITCODE
