package com.example.demovbridge.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BluetoothAudioManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isBluetoothConnected = MutableStateFlow(false)
    val isBluetoothConnected: StateFlow<Boolean> = _isBluetoothConnected.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            if (action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.d("BluetoothAudioManager", "SCO State: $state")
                updateConnectionState()
            } else if (action == Intent.ACTION_HEADSET_PLUG) {
                updateConnectionState()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        context.registerReceiver(bluetoothReceiver, filter)
        updateConnectionState()
    }

    private fun updateConnectionState() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = devices.any { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
        }
        _isBluetoothConnected.value = hasBluetooth
        
        if (hasBluetooth) {
            startSco()
        } else {
            stopSco()
        }
    }

    fun startSco() {
        try {
            if (!audioManager.isBluetoothScoOn) {
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
        } catch (e: Exception) {
            Log.e("BluetoothAudioManager", "Failed to start SCO", e)
        }
    }

    fun stopSco() {
        try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
        } catch (e: Exception) {
            Log.e("BluetoothAudioManager", "Failed to stop SCO", e)
        }
    }

    fun release() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
            stopSco()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
