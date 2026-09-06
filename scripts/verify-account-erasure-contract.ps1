param([Parameter(Mandatory = $true)][string]$ApiRepository)
$ErrorActionPreference = 'Stop'
$relativeSource = 'src/main/java/com/geupddong/account/AccountErasureSql.java'
$batchSource = Join-Path (Split-Path $PSScriptRoot -Parent) $relativeSource
$apiSource = Join-Path (Resolve-Path -LiteralPath $ApiRepository) $relativeSource
$batchText = (Get-Content -LiteralPath $batchSource -Raw).Replace("`r`n", "`n")
$apiText = (Get-Content -LiteralPath $apiSource -Raw).Replace("`r`n", "`n")
if ($batchText -cne $apiText) { throw 'API and batch account erasure SQL contracts differ. Do not release.' }
Write-Output 'Account erasure SQL contract: identical'
