$appPackageName = 'com.elianfabian.bluetooth_testing'



function Get-LapisState {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber,

        [Parameter(Mandatory)]
        [string] $Name
    )

    $intent = New-AdbIntent -PackageName $appPackageName -ComponentClassName .TestingBroadcastReceiver -Action $Name
    return (Send-AdbBroadcast -SerialNumber $SerialNumber -Intent $intent).Data
}

function Invoke-LapisAction {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber,

        [Parameter(Mandatory)]
        [string] $Action, 

        [scriptblock] $Extras = $null
    )

    $intent = New-AdbIntent -PackageName $appPackageName -ComponentClassName .MainTestingActivity -Action Action -Extras $Extras
    Start-AdbActivity -SerialNumber $SerialNumber -Intent $intent
}


function Get-LapisBluetoothState {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    return Get-LapisState -SerialNumber $SerialNumber -Name 'get-state'
}

function Get-LapisIsScanning {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    return Get-LapisState -SerialNumber $SerialNumber -Name 'get-isScanning'
}

function Get-LapisActiveBluetoothServersUuids {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    return Get-LapisState -SerialNumber $SerialNumber -Name 'get-activeBluetoothServersUuids'
}

function Get-LapisScannedDevices {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    return Get-LapisState -SerialNumber $SerialNumber -Name 'get-scannedDevices'
}

function Get-LapisPairedDevices {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    return Get-LapisState -SerialNumber $SerialNumber -Name 'get-pairedDevices'
}

function Start-LapisScan {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    Invoke-LapisAction -Action 'start-scan'
}

function Stop-LapisScan {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )
    
    Invoke-LapisAction -Action 'stop-scan'
}

function Start-LapisServer {
    param (
        [Parameter(Mandatory)]
        [string] $Uuid
    )

    Invoke-LapisAction -Action 'start-server' -Extras {
        New-AdbBundlePair -Key 'uuid' -String $Uuid
    }
}

function Start-LapisServerWithoutPairing {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber,

        [Parameter(Mandatory)]
        [string] $Uuid
    )

    Invoke-LapisAction -Action 'start-serverWithoutPairing' -Extras {
        New-AdbBundlePair -Key 'uuid' -String $Uuid
    }
}

function Stop-LapisServer {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber
    )

    Invoke-LapisAction -Action 'stop-server'
}

function Connect-LapisDevice {
    param (
        [Parameter(Mandatory)]
        [string] $SerialNumber,
        
        [Parameter(Mandatory)]
        [string] $Address,

        [Parameter(Mandatory)]
        [string] $Uuid
    )

    Invoke-LapisAction -Action 'connectTo-device' -Extras {
        New-AdbBundlePair -Key 'address' -String $Address
        New-AdbBundlePair -Key 'uuid' -String $Uuid
    }
}

function Connect-LapisDeviceWithoutPairing {
    param (
        [Parameter(Mandatory)]
        [string] $Address,

        [Parameter(Mandatory)]
        [string] $Uuid
    )

    Invoke-LapisAction -Action 'connectTo-deviceWithoutPairing' -Extras {
        New-AdbBundlePair -Key 'address' -String $Address
        New-AdbBundlePair -Key 'uuid' -String $Uuid
    }
}