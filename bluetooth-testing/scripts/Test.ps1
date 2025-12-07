param (

)

$PowerAdbPath = $env:PowerAdbPath
Import-Module -Name $PowerAdbPath -Force


if (-not (Test-Path 'BluetoothBridge.ps1')) {
    Write-Error "Could not find BluetoothBridge.ps1"
    Read-Host "Press enter to exit"
    exit
}

. ./BluetoothBridge.ps1


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


function Fail($message) {
    Write-Error $message
    Read-Host "Press enter to exit"
    exit
}

function Assert-BluetoothOn {
    param(
        [PSCustomObject] $Device
    )

    if (-not (Test-AdbBluetooth -SerialNumber $Device.SerialNumber)) {
        if ($Device.ApiLevel -ge 33) {
            Enable-AdbBluetooth -SerialNumber $Device.SerialNumber
        }

        Write-Host "Waiting until you turn on bluetooth for device: $Device" -ForegroundColor Yellow

        do {
            $state = Get-AdbBluetoothState -SerialNumber $Device.SerialNumber
        }
        while ($state -ne 'ON')
    }
}

function New-DeviceObject {
    param([string] $SerialNumber)

    return [pscustomobject]@{
        SerialNumber = $SerialNumber
        Name         = Get-AdbDeviceName -SerialNumber $SerialNumber
        Address      = Get-AdbBluetoothAddress -SerialNumber $SerialNumber
        ApiLevel     = Get-AdbApiLevel -SerialNumber $SerialNumber
    }
}





$device1 = New-DeviceObject $realDevices[0]
$device2 = New-DeviceObject $realDevices[1]

# The one whose API level is lower is more likely to have the command to enable bluetooth
if ($device1.ApiLevel -ge $device2.ApiLevel) {
    $client = $device1
    $server = $device2
}
else {
    $client = $device2
    $server = $device1
}

Write-Host "client device: $client" -ForegroundColor Green
Write-Host "server device: $server" -ForegroundColor Green

./gradlew :bluetooth-testing:assembleDebug

$apkPath = 'bluetooth-testing/build/outputs/apk/debug/bluetooth-testing-debug.apk'
$appPackageName = 'com.elianfabian.bluetooth_testing'

foreach ($device in @($client, $server)) {
    Install-AdbPackage -SerialNumber $device.SerialNumber -Path $apkPath
    Stop-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName
    Start-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName
}


Assert-BluetoothOn $client
Assert-BluetoothOn $server


Write-Host "STARTING TESTS" -ForegroundColor Green
Write-Host
Write-Host
Write-Host


# TODO: add tests

$clientPairedDevices = Get-LapisPairedDevices -SerialNumber $client.SerialNumber
Write-Host "client paired devices: $clientPairedDevices"


$serverPairedDevices = Get-LapisPairedDevices -SerialNumber $server.SerialNumber
Write-Host "server paired devices: $serverPairedDevices"








Read-Host "Press enter to exit"
