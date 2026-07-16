param(
    [Parameter(Mandatory = $true)]
    [string]$OriginalApk,

    [Parameter(Mandatory = $false)]
    [string]$ModifiedApk,

    [Parameter(Mandatory = $false)]
    [switch]$Compare,

    [Parameter(Mandatory = $false)]
    [switch]$InstallTest
)

# Script: sign-check.ps1 — Compare APK signatures and test installation
$script:hasError = $false

function Write-Header {
    param([string]$Text)
    Write-Host "`n$("-" * 60)" -ForegroundColor DarkGray
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host "$("-" * 60)" -ForegroundColor DarkGray
}

function Check-File {
    param([string]$Path)
    if (-not (Test-Path $Path)) {
        Write-Error "File not found: $Path"
        $script:hasError = $true
        return $false
    }
    return $true
}

# ================================
# 1. CERTIFICATE INFO
# ================================
Write-Header "1. CERTIFICATE INFORMATION"

if (Check-File $OriginalApk) {
    Write-Host "`n-- Original APK: $OriginalApk" -ForegroundColor Yellow
    $origCert = keytool -printcert -jarfile $OriginalApk 2>&1
    Write-Host $origCert
}

if ($ModifiedApk -and (Check-File $ModifiedApk)) {
    Write-Host "`n-- Modified APK: $ModifiedApk" -ForegroundColor Yellow
    $modCert = keytool -printcert -jarfile $ModifiedApk 2>&1
    Write-Host $modCert
}

# ================================
# 2. APK SIGNATURE VERIFICATION
# ================================
Write-Header "2. APK SIGNATURE VERIFICATION"

if (Check-File $OriginalApk) {
    Write-Host "`n-- Original APK:" -ForegroundColor Yellow
    $origVerify = apksigner verify --verbose $OriginalApk 2>&1
    Write-Host $origVerify
}

if ($ModifiedApk -and (Check-File $ModifiedApk)) {
    Write-Host "`n-- Modified APK:" -ForegroundColor Yellow
    $modVerify = apksigner verify --verbose $ModifiedApk 2>&1
    Write-Host $modVerify
}

# ================================
# 3. HASH COMPARISON
# ================================
if ($Compare -and $ModifiedApk) {
    Write-Header "3. HASH COMPARISON"

    $origHash = Get-FileHash $OriginalApk -Algorithm SHA256
    $modHash = Get-FileHash $ModifiedApk -Algorithm SHA256

    Write-Host "`nOriginal SHA256: $($origHash.Hash)" -ForegroundColor Yellow
    Write-Host "Modified SHA256: $($modHash.Hash)" -ForegroundColor Yellow

    if ($origHash.Hash -eq $modHash.Hash) {
        Write-Host "`n⚠️  HASHES MATCH — APKs are identical" -ForegroundColor Red
    } else {
        Write-Host "`n✅ Hashes differ — APKs are different" -ForegroundColor Green
    }

    # Certificate comparison
    if ((Check-File $OriginalApk) -and (Check-File $ModifiedApk)) {
        Write-Host "`n-- Certificate Owner Comparison:" -ForegroundColor Yellow
        $origOwner = (keytool -printcert -jarfile $OriginalApk 2>&1 | Select-String "Owner:")
        $modOwner = (keytool -printcert -jarfile $ModifiedApk 2>&1 | Select-String "Owner:")
        Write-Host "  Original: $origOwner"
        Write-Host "  Modified: $modOwner"

        if ($origOwner -eq $modOwner) {
            Write-Host "  ✅ Same signer" -ForegroundColor Green
        } else {
            Write-Host "  ❌ Different signer — signature mismatch!" -ForegroundColor Red
        }
    }
}

# ================================
# 4. INSTALLATION TEST
# ================================
if ($InstallTest) {
    Write-Header "4. INSTALLATION TEST"

    Write-Host "`n-- Checking connected devices..." -ForegroundColor Yellow
    $devices = adb devices 2>&1
    Write-Host $devices

    $deviceList = $devices | Select-String "device$"
    if (-not $deviceList) {
        Write-Warning "No Android device connected via adb. Skipping install test."
    } else {
        # Test 1: Install original APK
        Write-Host "`n-- TEST 1: Install original APK" -ForegroundColor Yellow
        $result1 = adb install $OriginalApk 2>&1
        Write-Host $result1
        if ($result1 -match "Success") {
            Write-Host "  ✅ Original APK installed successfully" -ForegroundColor Green

            # Test 2: Update with modified APK
            if (Check-File $ModifiedApk) {
                Write-Host "`n-- TEST 2: Update with modified APK (sign detection)" -ForegroundColor Yellow
                $result2 = adb install -r $ModifiedApk 2>&1
                Write-Host $result2
                if ($result2 -match "Success") {
                    Write-Host "  ⚠️  Modified APK installed OVER original!" -ForegroundColor Red
                } elseif ($result2 -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
                    Write-Host "  ✅ Signature mismatch detected!" -ForegroundColor Green
                } elseif ($result2 -match "SIGNATURE") {
                    Write-Host "  ✅ Signature error detected!" -ForegroundColor Green
                } else {
                    Write-Host "  ❓ Unexpected result" -ForegroundColor Yellow
                }
            }

            # Cleanup
            Write-Host "`n-- Cleaning up..." -ForegroundColor Yellow
            adb uninstall com.kpk.gol 2>&1 | Out-Null
        }

        # Test 3: Install modified APK (fresh)
        if ($ModifiedApk -and (Check-File $ModifiedApk)) {
            Write-Host "`n-- TEST 3: Install modified APK (fresh install)" -ForegroundColor Yellow
            $result3 = adb install $ModifiedApk 2>&1
            Write-Host $result3
            if ($result3 -match "Success") {
                Write-Host "  ✅ Modified APK installed (debug sign)" -ForegroundColor Green
                adb uninstall com.kpk.gol 2>&1 | Out-Null
            } elseif ($result3 -match "INSTALL_FAILED") {
                Write-Host "  ❌ Install failed" -ForegroundColor Red
            }
        }
    }
}

Write-Header "DONE"
