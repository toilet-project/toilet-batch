param([Parameter(Mandatory = $true)][string]$ApiRepository)
$ErrorActionPreference = 'Stop'
$apiRoot = (Resolve-Path -LiteralPath $ApiRepository).Path
$batchRoot = Split-Path $PSScriptRoot -Parent
$verifiedCount = 0
$batchOnlyAccountFiles = @(
    'ErasureLedgerMigration.java', 'GitHubRetirementRecoveryStore.java', 'LocalLedgerDirectoryProbe.java',
    'LocalLedgerEmptyPreflight.java', 'LocalLedgerRedeploymentPreflight.java', 'RetirementAuditSealCli.java',
    'RetirementExecutionContext.java', 'RetirementHistoryAdapter.java', 'RetirementMaintenanceCli.java',
    'RetirementMaintenanceRunner.java', 'RetirementMaintenanceService.java', 'RetirementOperationalEvidence.java',
    'RetirementRecoveryCoordinator.java', 'RetirementRecoveryJournal.java'
)
function Get-SharedFileStoreText([string]$Text) {
    # The batch-only retirement executor needs this existing package-private primitive.
    # Remove precisely its enum, documentation and method; compare every other source character.
    $start = $Text.IndexOf('    enum Removal { REMOVED, ALREADY_ABSENT }', [StringComparison]::Ordinal)
    $signature = '    Removal removeExact(String key, String ciphertextSha256, boolean preparedRetry) {'
    $methodStart = $Text.IndexOf($signature, [StringComparison]::Ordinal)
    if ($start -lt 0 -or $methodStart -le $start -or $Text.IndexOf($signature, $methodStart + 1, [StringComparison]::Ordinal) -ge 0) {
        throw 'Unexpected batch-only file-store extension shape'
    }
    $between = $Text.Substring($start, $methodStart - $start)
    if ($between -notmatch '(?s)^    enum Removal \{ REMOVED, ALREADY_ABSENT \}\s*/\*\*\s*\* Low-level retirement IO, NOT authorization.*\*/\s*$') {
        throw 'Unexpected declarations before the retirement primitive'
    }
    $openBrace = $methodStart + $signature.Length - 1
    $depth = 1
    $cursor = $openBrace + 1
    while ($cursor -lt $Text.Length -and $depth -gt 0) {
        if ($Text[$cursor] -eq '{') { $depth++ }
        elseif ($Text[$cursor] -eq '}') { $depth-- }
        $cursor++
    }
    if ($depth -ne 0 -or $Text.Substring($cursor) -notmatch '^\s+@Override public List<String> list') {
        throw 'Unexpected declarations after the retirement primitive'
    }
    $Text = $Text.Substring(0, $start) + $Text.Substring($cursor).TrimStart("`n")
    $batchComment = ' * No auto-bootstrap, overwrite, cloud fallback, or local "latest" checkpoint.' + "`n" +
        ' * Normal ledger callers cannot delete. Only the guarded retirement context uses the package-private removal primitive.'
    $apiComment = ' * No auto-bootstrap, overwrite, delete, cloud fallback, or local "latest" checkpoint.'
    if (!$Text.Contains($batchComment)) { throw 'Unexpected file-store extension documentation' }
    return $Text.Replace($batchComment, $apiComment)
}
foreach ($package in @('account', 'review', 'growth')) {
    $relativeSource = "src/main/java/com/geupddong/$package"
    $batchDirectory = Join-Path $batchRoot $relativeSource
    $apiDirectory = Join-Path $apiRoot $relativeSource
    $batchFiles = @(Get-ChildItem -LiteralPath $batchDirectory -Filter '*.java' | Sort-Object Name)
    $apiFiles = @(Get-ChildItem -LiteralPath $apiDirectory -Filter '*.java' | Sort-Object Name)
    $expectedBatchNames = @($apiFiles.Name)
    if ($package -eq 'account') { $expectedBatchNames += $batchOnlyAccountFiles }
    if ($batchFiles.Count -eq 0 -or ($batchFiles.Name -join ',') -cne (($expectedBatchNames | Sort-Object) -join ',')) {
        throw "Restore contract file set mismatch: $package"
    }
    foreach ($file in $batchFiles | Where-Object { $apiFiles.Name -contains $_.Name }) {
        $batchText = (Get-Content -LiteralPath $file.FullName -Raw).Replace("`r`n", "`n")
        $apiText = (Get-Content -LiteralPath (Join-Path $apiDirectory $file.Name) -Raw).Replace("`r`n", "`n")
        if ($package -eq 'account' -and $file.Name -eq 'FileErasureObjectStore.java') {
            $batchText = Get-SharedFileStoreText $batchText
        }
        if ($batchText -cne $apiText) { throw "API and batch restore contracts differ: $package/$($file.Name). Do not release." }
        $verifiedCount++
    }
}
Write-Output "Account, review and growth restore contracts: $verifiedCount identical files"
