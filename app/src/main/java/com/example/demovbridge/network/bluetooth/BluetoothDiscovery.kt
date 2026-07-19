package com.example.demovbridge.network.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SuppressLint("MissingPermission")
class BluetoothDiscovery(private val context: Context, private val adapter: BluetoothAdapter?) {
    private val _foundDevices = MutableStateFlow<Set<BluetoothDevice>>(emptySet())
    val foundDevices: StateFlow<Set<BluetoothDevice>> = _foundDevices

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    device?.let { _foundDevices.value += it }
                }
            }
        }
    }

    fun startDiscovery() {
        _foundDevices.value = emptySet()
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
            context.registerReceiver(receiver, filter)
            adapter?.startDiscovery()
        } catch (_: SecurityException) {
            stopDiscovery()
        }
    }

    fun stopDiscovery() {
        try {
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {
            // Permission may have been revoked while discovery was active.
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }
    }
}
