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
    $clientBluetoothState = Get-LapisBluetoothState -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientBluetoothState -IsEqualTo 'ON'

    $serverBluetoothState = Get-LapisBluetoothState -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverBluetoothState -IsEqualTo 'ON'
}

Test "devices should be unpaired" {
    Invoke-LapisDeviceUnpairing -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address
    Invoke-LapisDeviceUnpairing -SerialNumber $serverDevice.SerialNumber -Address $clientDevice.Address

    $clientPairedDevices = Get-LapisPairedDevices -SerialNumber $clientDevice.SerialNumber | Select-Object -ExpandProperty Address
    $serverPairedDevices = Get-LapisPairedDevices -SerialNumber $serverDevice.SerialNumber | Select-Object -ExpandProperty Address

    Assert-That $clientPairedDevices -NotContain $serverDevice.Address
    Assert-That $serverPairedDevices -NotContain $clientDevice.Address
}

Test "scannedDevices should be empty by now" {
    $clientScannedDevices = Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber
    $serverScannedDevices = Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber

    Assert-That $clientScannedDevices -IsEmpty
    Assert-That $serverScannedDevices -IsEmpty
}

Test "startScan sets isScanning to true" {
    Start-LapisScan -SerialNumber $clientDevice.SerialNumber
    $clientIsScanning = Get-LapisIsScanning -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientIsScanning -IsTrue

    Start-LapisScan -SerialNumber $serverDevice.SerialNumber
    $serverIsScanning = Get-LapisIsScanning -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverIsScanning -IsTrue
}

Test "stopScan sets isScanning to false" {
    Stop-LapisScan -SerialNumber $clientDevice.SerialNumber
    $clientIsScanning = Get-LapisIsScanning -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientIsScanning -IsFalse

    Stop-LapisScan -SerialNumber $serverDevice.SerialNumber
    $serverIsScanning = Get-LapisIsScanning -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverIsScanning -IsFalse
}

# Stricly this isn't necessary true, but on a practical level this will always be the case
Test "scannedDevices should be not be empty" {
    $clientScannedDevices = Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientScannedDevices -IsNotEmpty
    
    $serverScannedDevices = Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverScannedDevices -IsNotEmpty
}

Test "activeBluetoothServersUuids should be empty by now" {
    $clientUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientUuids -IsEmpty
    
    $serverUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverUuids -IsEmpty
}

Test "activeBluetoothServersUuids should have count 1 after calling startServer" {
    $uuid = (New-Guid).Guid
    Start-LapisServer -SerialNumber $clientDevice.SerialNumber -Uuid $uuid
    Start-LapisServer -SerialNumber $serverDevice.SerialNumber -Uuid $uuid

    $clientUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientUuids -HasCount 1

    $serverUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverUuids -HasCount 1
}

Test "activeBluetoothServersUuids should be empty after calling stopServer" {
    $clientUuid = Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber
    $serverUuid = Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber

    Assert-That $clientUuid -IsEqualTo $serverUuid
    $uuid = $clientUuid

    Stop-LapisServer -SerialNumber $clientDevice.SerialNumber -Uuid $uuid
    Stop-LapisServer -SerialNumber $serverDevice.SerialNumber -Uuid $uuid

    $clientUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $clientDevice.SerialNumber
    Assert-That $clientUuids -IsEmpty

    $serverUuids = Get-LapisActiveBluetoothServersUuids -SerialNumber $serverDevice.SerialNumber
    Assert-That $serverUuids -IsEmpty
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

    Assert-That $clientConnectedDevices -HasCount 1
    Assert-That $clientConnectedDevices[0].Address -IsEqualTo $serverDevice.Address

    Assert-That $serverConnectedDevices -HasCount 1
    Assert-That $serverConnectedDevices[0].Address -IsEqualTo $clientDevice.Address
}

Test "both devices can send and receive mirrored data" {
    $random = [System.Random]::new(1)
    $length = 100

    $byteArrayA = 1..$length | ForEach-Object {
        $value = $random.Next()
        [sbyte](($value % 256 + 128) % 256 - 128)
    }

    $byteArrayB = 1..$length | ForEach-Object {
        $value = $random.Next()
        [sbyte](($value % 256 + 128) % 256 - 128)
    }

    Send-LapisData -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address -ByteArray $byteArrayA
    $receivedOnServer = Receive-LapisData -SerialNumber $serverDevice.SerialNumber -Address $clientDevice.Address -BytesLength $length
    Assert-That $receivedOnServer -IsEqualTo $byteArrayA

    Send-LapisData -SerialNumber $serverDevice.SerialNumber -Address $clientDevice.Address -ByteArray $byteArrayB
    $receivedOnClient = Receive-LapisData -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address -BytesLength $length
    Assert-That $receivedOnClient -IsEqualTo $byteArrayB
}

Test "an unpaired device should appear on scannedDevices as Disconnected after disconnection" {
    Clear-AdbLogcat -SerialNumber $clientDevice.SerialNumber
    Clear-AdbLogcat -SerialNumber $serverDevice.SerialNumber

    Disconnect-LapisDevice -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address

    Get-AdbLogcat -SerialNumber $clientDevice.SerialNumber -FilteredTag 'MainTestingActivity' `
        -Pattern 'event: OnDeviceDisconnected' -StopAtMatchCount 1 > $null

    Get-AdbLogcat -SerialNumber $serverDevice.SerialNumber -FilteredTag 'MainTestingActivity' `
        -Pattern 'event: OnDeviceDisconnected' -StopAtMatchCount 1 > $null

    $clientDisconnectedDevice = Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber | Where-Object { $_.Address -eq $serverDevice.Address }
    $serverDisconnectedDevice = Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber | Where-Object { $_.Address -eq $clientDevice.Address }

    Assert-That $clientDisconnectedDevice.connectionState -IsEqualTo 'Disconnected'
    Assert-That $serverDisconnectedDevice.connectionState -IsEqualTo 'Disconnected'
}

# I'm not sure how we can test cancelConnectionAttempt since sometimes it immediatly connects
# Test "cancelConnectionAttempt works" {
#     $uuid = (New-Guid).Guid

#     Start-LapisServerWithoutPairing -SerialNumber $serverDevice.SerialNumber -Uuid $uuid
#     Connect-LapisDeviceWithoutPairing -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address -Uuid $uuid
#     Invoke-LapisCancellingConnectionAttempt -SerialNumber $clientDevice.SerialNumber -Address $serverDevice.Address

#     Get-AdbLogcat -SerialNumber $clientDevice.SerialNumber -FilteredTag 'MainTestingActivity' `
#         -Pattern 'END cancel-connectionAttempt: true' -StopAtMatchCount 1 > $null

#     $clientScannedDevices = Get-LapisScannedDevices -SerialNumber $clientDevice.SerialNumber | Select-Object -ExpandProperty Address
#     $serverScannedDevices = Get-LapisScannedDevices -SerialNumber $serverDevice.SerialNumber | Select-Object -ExpandProperty Address

#     $clientScannedDevices | Should -NotContain $serverDevice.Address 
#     $serverScannedDevices | Should -NotContain $clientDevice.Address 
# }



Read-Host "Press enter to exit..."
