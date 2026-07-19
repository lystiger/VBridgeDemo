package com.example.demovbridge.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.demovbridge.network.bluetooth.BluetoothConnectionManager
import com.example.demovbridge.network.bluetooth.BluetoothDiscovery
import com.example.demovbridge.network.bluetooth.BluetoothConnectionState

@SuppressLint("MissingPermission")
@Composable
fun BluetoothSettings(
    btManager: BluetoothConnectionManager,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val pairedDevices = remember(hasPermission) {
        if (hasPermission) adapter?.bondedDevices?.toList() ?: emptyList() else emptyList()
    }
    
    val discovery = remember { BluetoothDiscovery(context, adapter) }
    val foundDevices by discovery.foundDevices.collectAsState()
    val bluetoothState by btManager.connectionState.collectAsState()
    var isScanning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { discovery.stopDiscovery() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Bluetooth Transport", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Offline phone-to-phone mode", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        val (statusText, statusColor) = if (isScanning) {
            "Scanning" to Color(0xFFFFC107)
        } else when (bluetoothState) {
            BluetoothConnectionState.Waiting -> "Waiting for peer" to Color(0xFFFFC107)
            BluetoothConnectionState.Discovering -> "Scanning" to Color(0xFFFFC107)
            BluetoothConnectionState.Connecting -> "Connecting..." to Color(0xFFFFC107)
            BluetoothConnectionState.Connected -> "Connected" to Color(0xFF22C55E)
            BluetoothConnectionState.Disconnected -> "Disconnected" to Color.Gray
            is BluetoothConnectionState.Error -> (bluetoothState as BluetoothConnectionState.Error).message to Color.Red
            BluetoothConnectionState.Idle -> "Idle" to Color.Gray
        }

        Surface(
            color = statusColor.copy(alpha = 0.1f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(modifier = Modifier.size(8.dp).background(statusColor, RoundedCornerShape(4.dp)))
                Spacer(modifier = Modifier.width(8.dp))
                Text(statusText, color = statusColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { if (hasPermission) btManager.startHost() else onRequestPermission() },
                modifier = Modifier.weight(1f),
                enabled = bluetoothState !is BluetoothConnectionState.Connected &&
                    bluetoothState !is BluetoothConnectionState.Connecting &&
                    bluetoothState !is BluetoothConnectionState.Waiting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Host Session")
            }
            
            OutlinedButton(
                onClick = { 
                    btManager.disconnect()
                    discovery.stopDiscovery()
                    isScanning = false
                },
                modifier = Modifier.weight(1f),
                enabled = bluetoothState is BluetoothConnectionState.Connected ||
                    bluetoothState is BluetoothConnectionState.Connecting ||
                    bluetoothState is BluetoothConnectionState.Waiting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Disconnect")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Available Devices", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { 
                if (!hasPermission) {
                    onRequestPermission()
                } else if (isScanning) {
                    discovery.stopDiscovery()
                    isScanning = false
                } else {
                    discovery.startDiscovery()
                    isScanning = true
                }
            }) {
                Icon(if (isScanning) Icons.Default.Close else Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isScanning) "Stop Scan" else "Scan")
            }
        }
        
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!hasPermission) {
                item {
                    TextButton(onClick = onRequestPermission) { Text("Grant Bluetooth permission") }
                }
            }
            item { 
                Text("PAIRED", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(bottom = 4.dp)) 
            }
            
            if (pairedDevices.isEmpty()) {
                item { Text("No paired devices found", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                items(pairedDevices) { device ->
                    DeviceItem(device) { 
                        btManager.connectToDevice(device)
                        discovery.stopDiscovery()
                        isScanning = false
                    }
                }
            }

            if (foundDevices.isNotEmpty()) {
                item { 
                    Text("NEARBY", style = MaterialTheme.typography.labelSmall, color = Color.Gray, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) 
                }
                items(foundDevices.toList()) { device ->
                    DeviceItem(device) { 
                        btManager.connectToDevice(device)
                        discovery.stopDiscovery()
                        isScanning = false
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceItem(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Bluetooth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(device.name ?: "Unknown Device", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}
