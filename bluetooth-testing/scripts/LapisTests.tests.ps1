# TODO: we should probably use Pester for this, but it's kind of harder to do things like check if gradlew exists


$PowerAdbPath = $env:PowerAdbPath
Import-Module -Name $PowerAdbPath -Force


if (-not (Test-Path 'BluetoothBridge.ps1')) {
    Write-Error "Could not find BluetoothBridge.ps1"
    Read-Host "Press enter to exit"
    exit
}
if (-not (Test-Path 'TestFunctions.ps1')) {
    Write-Error "Could not find BluetoothBridge.ps1"
    Read-Host "Press enter to exit"
    exit
}


. ./BluetoothBridge.ps1
. ./TestFunctions.ps1


Set-Location "../.." # Change to root directoy

if (-not (Get-Command 'adb' -ErrorAction SilentlyContinue)) {
    Write-Error "Could not find adb"
    Read-Host "Press enter to exit"
    exit
}
if (-not (Test-Path 'gradlew')) {
    Write-Error "Could not find gradlew"
    Read-Host "Press enter to exit"
    exit
}



$realDevices = Get-AdbDevice | Where-Object { -not (Test-AdbEmulator -SerialNumber $_) }
if ($realDevices.Count -lt 2) {
    Write-Error "This test requieres 2 real devices connected"
    Show-AdbDevice
    Read-Host "Press enter to exit"
    exit
}



$device1 = New-DeviceObject $realDevices[0]
$device2 = New-DeviceObject $realDevices[1]

# The one whose API level is higher is more likely to have the command to enable bluetooth
if ($device1.ApiLevel -ge $device2.ApiLevel) {
    $clientDevice = $device1
    $serverDevice = $device2
}
else {
    $clientDevice = $device2
    $serverDevice = $device1
}

Write-Host "client device: $clientDevice" -ForegroundColor Green
Write-Host "server device: $serverDevice" -ForegroundColor Green

./gradlew :bluetooth-testing:assembleDebug

$apkPath = 'bluetooth-testing/build/outputs/apk/debug/bluetooth-testing-debug.apk'
$appPackageName = 'com.elianfabian.bluetooth_testing'

foreach ($device in @($clientDevice, $serverDevice)) {
    Install-AdbPackage -SerialNumber $device.SerialNumber -Path $apkPath
    Stop-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName

    Grant-AdbPermission -SerialNumber $device.SerialNumber -PackageName $appPackageName `
        -PermissionName @(
        'android.permission.ACCESS_FINE_LOCATION'
        'android.permission.BLUETOOTH_SCAN'
        'android.permission.BLUETOOTH_CONNECT'
    ) `
        -ErrorAction SilentlyContinue

    Start-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName

    Ensure-BluetoothOn $device
}


Write-Host "STARTING TESTS" -ForegroundColor Green
Write-Host
Write-Host
Write-Host


Test "bluetooth state should be on" {
    Get-LapisBluetoothState -SerialNumber $clientDevice.SerialNumber | Should -Be 'ON'
    Get-LapisBluetoothState -SerialNumber $serverDevice.SerialNumber | Should -Be 'ON'
}

Test "scannedDevices should be empty by now" {
    Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber | Should -BeEmpty
    Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber | Should -BeEmpty
}

Test "startScan sets isScanning to true" {
    Start-LapisScan -SerialNumber $clientDevice.SerialNumber
    $isScanning = Get-LapisIsScanning -SerialNumber $clientDevice.SerialNumber
    $isScanning | Should -BeTrue

    Start-LapisScan -SerialNumber $serverDevice.SerialNumber
    $isScanning = Get-LapisIsScanning -SerialNumber $serverDevice.SerialNumber
    $isScanning | Should -BeTrue
}

Test "stopScan sets isScanning to false" {
    Stop-LapisScan -SerialNumber $clientDevice.SerialNumber
    Get-LapisIsScanning -SerialNumber $clientDevice.SerialNumber | Should -BeFalse

    Stop-LapisScan -SerialNumber $serverDevice.SerialNumber
    Get-LapisIsScanning -SerialNumber $serverDevice.SerialNumber | Should -BeFalse
}

# Stricly this isn't necessary true, but on a practical level this will always be the case
Test "scannedDevices should be not be empty" {
    Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber | Should -BeNotEmpty
    Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber | Should -BeNotEmpty
}

Test "activeBluetoothServersUuids should be empty by now" {
    Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber | Should -BeEmpty
    Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber | Should -BeEmpty
}

Test "activeBluetoothServersUuids should have count 1 after calling startServer" {
    $uuid = (New-Guid).Guid

    Start-LapisServer -SerialNumber $clientDevice.SerialNumber -Uuid $uuid
    Start-LapisServer -SerialNumber $serverDevice.SerialNumber -Uuid $uuid
    Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber | Should -HaveCount 1
    Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber | Should -HaveCount 1
}

Test "activeBluetoothServersUuids should be empty after calling stopServer" {
    $clientUuid = Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber
    $serverUuid = Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber

    $clientUuid | Should -BeEqualTo $serverUuid

    $uuid = $clientUuid

    Stop-LapisServer -SerialNumber $clientDevice.SerialNumber -Uuid $uuid
    Stop-LapisServer -SerialNumber $serverDevice.SerialNumber -Uuid $uuid
    Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber | Should -BeEmpty
    Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber | Should -BeEmpty
}

Test "startServerWithoutPairing and connectToDeviceWithoutPairing should work" {
    $uuid = (New-Guid).Guid

    Clear-AdbLogcat -SerialNumber $clientDevice.SerialNumber -Force

    Start-LapisServerWithoutPairing -SerialNumber $serverDevice.SerialNumber -Uuid $uuid
    Connect-LapisDeviceWithoutPairing -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address -Uuid $uuid

    Get-AdbLogcat -SerialNumber $clientDevice.SerialNumber -FilteredTag 'MainTestingActivity' `
        -Pattern 'END connectTo-deviceWithoutPairing:' -StopAtMatchCount 1 > $null

    $clientScannedDevices = Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber
    $serverScannedDevices = Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber

    $clientConnectedDevices = $clientScannedDevices | Where-Object { $_.connectionState -eq 'Connected' }
    $serverConnectedDevices = $serverScannedDevices | Where-Object { $_.connectionState -eq 'Connected' }

    $clientConnectedDevices | Should -HaveCount 1
    $clientConnectedDevices[0].Address | Should -BeEqualTo $serverDevice.Address

    $serverConnectedDevices | Should -HaveCount 1
    $serverConnectedDevices[0].Address | Should -BeEqualTo $clientDevice.Address
}



Read-Host "Press enter to exit..."
