# Snapshot the exact JuiceDict source tree that produced a given build.
# Usage:  powershell -ExecutionPolicy Bypass -File scripts\snapshot-source.ps1 -Label JuiceDict-0.0.2-20260903-rc2
# Output: build-artifacts/source-snapshots/<Label>/  (full reproducible source slice)
# NOTE: since the repo moved into D:\dsh\juicedict (repo root == project folder),
#       snapshots live inside the project next to the APKs, not in a workspace-level outputs/.
param(
    [Parameter(Mandatory = $true)][string]$Label
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot          # repo root (juicedict/)
$destRoot = Join-Path $root "build-artifacts\source-snapshots"
$dest = Join-Path $destRoot $Label
if (Test-Path $dest) { Remove-Item -LiteralPath $dest -Recurse -Force }
New-Item -ItemType Directory -Path $dest -Force | Out-Null
$excludeDirs = @(".git", "build", ".gradle", ".kotlin", ".idea", "keystore", "internal-dicts", "build-artifacts")
Get-ChildItem $root -Force -Directory | Where-Object { $excludeDirs -notcontains $_.Name } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $dest $_.Name) -Recurse -Force
}
Get-ChildItem $root -Force -File | Where-Object { $_.Name -notin @("local.properties", "keystore.properties") } | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $dest $_.Name) -Force
}
# Record provenance: which APK this snapshot produced + keystore fingerprint (no secrets).
$info = @(
    "# JuiceDict source snapshot: $Label",
    "# created: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
    "# NOTE: keystore/ and signing properties are intentionally excluded (secrets)."
) -join "`n"
Set-Content -Path (Join-Path $dest "SNAPSHOT.txt") -Value $info -Encoding UTF8
Write-Host "Snapshot saved to: $dest"


