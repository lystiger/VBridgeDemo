package com.example.demovbridge.network.bluetooth

sealed interface BluetoothConnectionState {
    object Idle : BluetoothConnectionState
    object Waiting : BluetoothConnectionState
    object Discovering : BluetoothConnectionState
    object Connecting : BluetoothConnectionState
    object Connected : BluetoothConnectionState
    object Disconnected : BluetoothConnectionState
    data class Error(val message: String) : BluetoothConnectionState
}
