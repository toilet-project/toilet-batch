param([Parameter(Mandatory = $true)][string]$ApiRepository)
$ErrorActionPreference = 'Stop'
$relativeSource = 'src/main/java/com/geupddong/account'
$batchDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) $relativeSource
$apiDirectory = Join-Path (Resolve-Path -LiteralPath $ApiRepository) $relativeSource
$batchFiles = @(Get-ChildItem -LiteralPath $batchDirectory -Filter '*.java' | Sort-Object Name)
$apiFiles = @(Get-ChildItem -LiteralPath $apiDirectory -Filter '*.java' | Sort-Object Name)
if (($batchFiles.Name -join ',') -cne ($apiFiles.Name -join ',')) { throw 'Erasure contract file set mismatch' }
foreach ($file in $batchFiles) {
    $batchText = (Get-Content -LiteralPath $file.FullName -Raw).Replace("`r`n", "`n")
    $apiText = (Get-Content -LiteralPath (Join-Path $apiDirectory $file.Name) -Raw).Replace("`r`n", "`n")
    if ($batchText -cne $apiText) { throw 'API and batch erasure contracts differ. Do not release.' }
}
Write-Output "Account erasure contracts: $($batchFiles.Count) identical files"
