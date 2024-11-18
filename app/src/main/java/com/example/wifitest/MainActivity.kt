package com.example.wifitest

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult as WifiScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.math.pow

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private val discoveredBluetoothDevices = mutableSetOf<Pair<Long, BluetoothDeviceInfo>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Wi-Fi Manager
        wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager

        // Initialize Bluetooth LE Scanner
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        // Compose Content
        setContent {
            NetworkScannerApp()
        }
    }

    @Composable
    fun NetworkScannerApp() {
        var wifiList by remember { mutableStateOf(emptyList<WifiScanResult>()) }
        var bluetoothList by remember { mutableStateOf(emptyList<BluetoothDeviceInfo>()) }

        // Permissions Handler
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
//                startWifiScan { results -> wifiList = results }
                startBluetoothScan { results -> bluetoothList = results }
            } else {
                Toast.makeText(this, "Permissions required to scan networks", Toast.LENGTH_SHORT)
                    .show()
            }
        }

        LaunchedEffect(Unit) {
            val permissions = listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN
            ).filter {
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            } else {
//                startWifiScan { results -> wifiList = results }
                startBluetoothScan { results -> bluetoothList = results }
            }
        }

        // UI Layout
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Network Scanner") })
            },
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Top,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Nearby Wi-Fi Networks", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(wifiList) { wifi ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = 4.dp
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = "SSID: ${wifi.SSID}")
                                    Text(text = "Signal: ${wifi.level} dBm")
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nearby Bluetooth Devices", style = MaterialTheme.typography.h6)
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bluetoothList) { device ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                elevation = 4.dp
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = "Device Name: ${device.name ?: "Unknown"}")
                                    Text(text = "Address: ${device.address}")
                                    Text(text = "RSSI: ${device.rssi} dBm")
                                    Text(text = "Tx Power: ${device.txPower?.let { "$it dBm" } ?: "Unavailable"}")
                                    Text(text = "Distance: ${"%.2f".format(device.estimatedDistance)} meters")
                                }
                            }
                        }
                    }
                }
            }
        )
    }

    private fun startWifiScan(onResults: (List<WifiScanResult>) -> Unit) {
        wifiManager.startScan()
        val wifiResults = wifiManager.scanResults
        onResults(wifiResults)
    }

    private fun startBluetoothScan(onResults: (List<BluetoothDeviceInfo>) -> Unit) {
        discoveredBluetoothDevices.clear()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val rssi = result.rssi
                val txPower = result.txPower.takeIf { it != ScanResult.TX_POWER_NOT_PRESENT }
                val estimatedDistance = calculateBluetoothDistance(rssi, txPower ?: -59)

                val currentTime = System.currentTimeMillis()
                discoveredBluetoothDevices.removeIf { it.second.address == device.address || currentTime - it.first > 5000 }
                discoveredBluetoothDevices.add(
                    Pair(
                        currentTime,
                        BluetoothDeviceInfo(
                            name = device.name,
                            address = device.address,
                            rssi = rssi,
                            txPower = txPower,
                            estimatedDistance = estimatedDistance
                        )
                    )
                )
                onResults(
                    discoveredBluetoothDevices
                        .map { it.second }
                        .toList()
                        .sortedBy { it.estimatedDistance }
                )
            }

            override fun onScanFailed(errorCode: Int) {
                Toast.makeText(
                    this@MainActivity,
                    "Bluetooth scan failed: $errorCode",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        bluetoothLeScanner.startScan(scanCallback)
    }

    private fun calculateBluetoothDistance(rssi: Int, txPower: Int): Double {
        val pathLossExponent = 2.0 // Adjust based on environment
        return 10.0.pow((txPower - rssi) / (10 * pathLossExponent))
    }
}

data class BluetoothDeviceInfo(
    val name: String?,
    val address: String,
    val rssi: Int,
    val txPower: Int?,
    val estimatedDistance: Double
)
