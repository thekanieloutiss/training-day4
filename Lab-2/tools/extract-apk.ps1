param(
    [Parameter(Mandatory = $true)]
    [string]$XapkPath,

    [Parameter(Mandatory = $false)]
    [string]$OutputDir = (Join-Path (Split-Path $XapkPath -Parent) "extracted")
)

# Script: extract-apk.ps1 — Extract APK from XAPK file
if (-not (Test-Path $XapkPath)) {
    Write-Error "File not found: $XapkPath"
    exit 1
}

$extension = [System.IO.Path]::GetExtension($XapkPath).ToLower()
Write-Host "Extracting: $XapkPath" -ForegroundColor Cyan

if ($extension -eq ".xapk") {
    # XAPK is a ZIP file containing APK + config APKs + OBB
    $zipPath = [System.IO.Path]::ChangeExtension($XapkPath, ".zip")
    Copy-Item -Path $XapkPath -Destination $zipPath -Force

    if (Test-Path $OutputDir) {
        Remove-Item -Path $OutputDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

    try {
        Expand-Archive -Path $zipPath -DestinationPath $OutputDir -Force
        Write-Host "Extracted to: $OutputDir" -ForegroundColor Green

        $apkFiles = Get-ChildItem -Path $OutputDir -Recurse -Filter "*.apk"
        Write-Host "`nAPK files found:" -ForegroundColor Yellow
        foreach ($apk in $apkFiles) {
            $size = "{0:N1} MB" -f ($apk.Length / 1MB)
            Write-Host "  - $($apk.Name) ($size)"
        }

        # Show manifest
        $manifestPath = Join-Path $OutputDir "manifest.json"
        if (Test-Path $manifestPath) {
            Write-Host "`nManifest:" -ForegroundColor Yellow
            Get-Content $manifestPath -Raw | ConvertFrom-Json | Format-List
        }
    } catch {
        Write-Error "Failed to extract: $_"
        exit 1
    } finally {
        Remove-Item -Path $zipPath -Force -ErrorAction SilentlyContinue
    }
} elseif ($extension -eq ".apk") {
    Write-Host "File is already an APK, copying..."
    if (-not (Test-Path $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    }
    Copy-Item -Path $XapkPath -Destination (Join-Path $OutputDir (Split-Path $XapkPath -Leaf)) -Force
    Write-Host "Copied to: $OutputDir" -ForegroundColor Green
} else {
    Write-Error "Unsupported file type: $extension"
    exit 1
}
