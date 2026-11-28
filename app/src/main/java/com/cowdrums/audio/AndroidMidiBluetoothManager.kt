package com.cowdrums.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.cowdrums.model.MidiDeviceEntry
import java.util.UUID

/**
 * Native Android USB MIDI & Bluetooth LE (BLE) MIDI controller manager.
 * - Auto-connects plugged-in USB MIDI devices and system MIDI ports via android.media.midi.MidiManager.
 * - Scans for standard BLE MIDI controllers (Service UUID 03B80E5A-EDE8-4B33-A751-6CE34EC4C700)
 *   and connects directly in 1 tap via MidiManager.openBluetoothDevice().
 */
class AndroidMidiBluetoothManager(
    private val context: Context,
    private val onNoteOn: (channel: Int, note: Int, velocity: Float) -> Unit,
    private val onConnectedDevicesChanged: (List<MidiDeviceEntry>) -> Unit,
    private val onBleDevicesChanged: (List<MidiDeviceEntry>) -> Unit,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        // Official MIDI Manufacturers Association Bluetooth LE MIDI Service UUID
        val BLE_MIDI_SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val midiManager: MidiManager? = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val bluetoothManager: BluetoothManager? = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val openMidiDevices = LinkedHashMap<String, MidiDevice>()
    private val openOutputPorts = LinkedHashMap<String, MutableList<MidiOutputPort>>()
    private val connectedLabels = LinkedHashMap<String, MidiDeviceEntry>()

    private val discoveredBleMap = LinkedHashMap<String, BluetoothDevice>()
    private val discoveredBleEntries = LinkedHashMap<String, MidiDeviceEntry>()
    private var activeScanCallback: ScanCallback? = null
    private var scanStopRunnable: Runnable? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (device.outputPortCount > 0) {
                openMidiDeviceInfo(device)
            }
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            val id = "midi_${device.id}"
            closeMidiDeviceById(id)
            publishConnected()
        }
    }

    @Suppress("DEPRECATION")
    fun start() {
        val mm = midiManager ?: return
        runCatching {
            mm.registerDeviceCallback(deviceCallback, mainHandler)
            for (info in mm.devices) {
                if (info.outputPortCount > 0) {
                    openMidiDeviceInfo(info)
                }
            }
        }
        publishConnected()
    }

    @Suppress("DEPRECATION")
    fun refreshConnectedDevices() {
        val mm = midiManager
        if (mm == null) {
            onStatus("MIDI service not available on this device")
            return
        }
        runCatching {
            for (info in mm.devices) {
                if (info.outputPortCount > 0 && !openMidiDevices.containsKey("midi_${info.id}")) {
                    openMidiDeviceInfo(info)
                }
            }
        }
        publishConnected()
    }

    @SuppressLint("MissingPermission")
    fun startBluetoothMidiScan() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            onStatus("Please turn on Bluetooth first to scan for BLE MIDI controllers.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            onStatus("Bluetooth LE scanner unavailable on this device.")
            return
        }

        stopBluetoothMidiScan()
        discoveredBleMap.clear()
        discoveredBleEntries.clear()

        // Include already-paired Bluetooth devices that might be BLE MIDI controllers
        runCatching {
            for (bonded in adapter.bondedDevices.orEmpty()) {
                if (bonded.type == BluetoothDevice.DEVICE_TYPE_LE || bonded.type == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    val addr = bonded.address ?: continue
                    val name = bonded.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Device ($addr)"
                    discoveredBleMap[addr] = bonded
                    discoveredBleEntries[addr] = MidiDeviceEntry(
                        id = addr,
                        name = name,
                        isBluetooth = true,
                        connected = connectedLabels.containsKey("bt_$addr"),
                        detail = "Paired Bluetooth LE · Tap to connect"
                    )
                }
            }
        }
        publishBleDiscovered()

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return
                val name = runCatching { dev.name }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: "BLE MIDI Controller ($addr)"
                discoveredBleMap[addr] = dev
                discoveredBleEntries[addr] = MidiDeviceEntry(
                    id = addr,
                    name = name,
                    isBluetooth = true,
                    connected = connectedLabels.containsKey("bt_$addr"),
                    detail = "BLE MIDI · Signal ${result.rssi} dBm"
                )
                publishBleDiscovered()
            }

            override fun onScanFailed(errorCode: Int) {
                onScanningChanged(false)
                onStatus("Bluetooth MIDI scan failed (code $errorCode)")
            }
        }

        activeScanCallback = cb
        val started = runCatching {
            scanner.startScan(listOf(filter), settings, cb)
            true
        }.getOrElse { err ->
            onStatus("Bluetooth scan error: ${err.message ?: "permission required"}")
            false
        }

        if (started) {
            onScanningChanged(true)
            val stopTask = Runnable { stopBluetoothMidiScan() }
            scanStopRunnable = stopTask
            mainHandler.postDelayed(stopTask, 12_000L)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBluetoothMidiScan() {
        scanStopRunnable?.let { mainHandler.removeCallbacks(it) }
        scanStopRunnable = null
        val cb = activeScanCallback ?: return
        activeScanCallback = null
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(cb) }
        onScanningChanged(false)
    }

    @SuppressLint("MissingPermission")
    fun connectBluetoothDevice(address: String) {
        val mm = midiManager
        val btDevice = discoveredBleMap[address]
        if (mm == null || btDevice == null) {
            onStatus("Bluetooth MIDI device not found — try scanning again.")
            return
        }
        val devName = runCatching { btDevice.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: address
        val id = "bt_$address"
        if (openMidiDevices.containsKey(id)) {
            closeMidiDeviceById(id)
            publishConnected()
            publishBleDiscovered()
            onStatus("Disconnected $devName")
            return
        }

        stopBluetoothMidiScan()
        onStatus("Connecting Bluetooth MIDI: $devName...")

        mm.openBluetoothDevice(btDevice, { midiDevice ->
            if (midiDevice == null) {
                onStatus("Could not open Bluetooth MIDI on $devName")
                return@openBluetoothDevice
            }
            attachMidiDevice(id, devName, isBluetooth = true, midiDevice = midiDevice)
            onStatus("Connected Bluetooth MIDI: $devName")
            publishConnected()
            publishBleDiscovered()
        }, mainHandler)
    }

    fun disconnectDevice(id: String) {
        closeMidiDeviceById(id)
        publishConnected()
        publishBleDiscovered()
    }

    fun close() {
        stopBluetoothMidiScan()
        runCatching { midiManager?.unregisterDeviceCallback(deviceCallback) }
        for (id in openMidiDevices.keys.toList()) {
            closeMidiDeviceById(id)
        }
    }

    private fun openMidiDeviceInfo(info: MidiDeviceInfo) {
        val mm = midiManager ?: return
        val id = "midi_${info.id}"
        if (openMidiDevices.containsKey(id)) return

        val props = info.properties
        val name = props.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI Device ${info.id}"
        val isBt = info.type == MidiDeviceInfo.TYPE_BLUETOOTH

        mm.openDevice(info, { midiDevice ->
            if (midiDevice != null) {
                attachMidiDevice(id, name, isBluetooth = isBt, midiDevice = midiDevice)
                publishConnected()
            }
        }, mainHandler)
    }

    private fun attachMidiDevice(id: String, name: String, isBluetooth: Boolean, midiDevice: MidiDevice) {
        val ports = mutableListOf<MidiOutputPort>()
        val portCount = midiDevice.info?.outputPortCount ?: 1
        for (p in 0 until portCount) {
            val outPort = midiDevice.openOutputPort(p)
            if (outPort != null) {
                outPort.connect(MidiNoteReceiver(onNoteOn))
                ports.add(outPort)
            }
        }
        openMidiDevices[id] = midiDevice
        openOutputPorts[id] = ports
        connectedLabels[id] = MidiDeviceEntry(
            id = id,
            name = name,
            isBluetooth = isBluetooth,
            connected = true,
            detail = if (isBluetooth) "Bluetooth LE MIDI" else "USB / System MIDI"
        )
    }

    private fun closeMidiDeviceById(id: String) {
        openOutputPorts.remove(id)?.forEach { runCatching { it.close() } }
        openMidiDevices.remove(id)?.let { runCatching { it.close() } }
        connectedLabels.remove(id)
    }

    private fun publishConnected() {
        onConnectedDevicesChanged(connectedLabels.values.toList())
    }

    private fun publishBleDiscovered() {
        val list = discoveredBleEntries.map { (addr, entry) ->
            entry.copy(connected = connectedLabels.containsKey("bt_$addr"))
        }
        onBleDevicesChanged(list)
    }

    /**
     * Parses raw Android MIDI byte streams (including multi-message packets & running status)
     * and fires Note-On callbacks with normalized velocity (0.05..1.0).
     */
    private class MidiNoteReceiver(
        private val onNoteOn: (channel: Int, note: Int, velocity: Float) -> Unit
    ) : MidiReceiver() {
        private var runningStatus = 0

        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = (offset + count).coerceAtMost(msg.size)
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) {
                    // System Real-Time message (single byte, doesn't affect running status)
                    i++
                    continue
                }
                if (b >= 0x80) {
                    runningStatus = b
                    i++
                    if (b == 0xF0) {
                        // Skip SysEx until 0xF7
                        while (i < end && (msg[i].toInt() and 0xFF) != 0xF7) i++
                        if (i < end) i++
                        runningStatus = 0
                        continue
                    } else if (b == 0xF1 || b == 0xF3) {
                        i++
                        continue
                    } else if (b == 0xF2) {
                        i += 2
                        continue
                    } else if (b >= 0xF4) {
                        continue
                    }
                }
                val statusType = runningStatus and 0xF0
                val channel = runningStatus and 0x0F
                when (statusType) {
                    0x90 -> { // Note On (2 data bytes)
                        if (i + 1 >= end) break
                        val note = msg[i].toInt() and 0x7F
                        val vel = msg[i + 1].toInt() and 0x7F
                        i += 2
                        if (vel > 0) {
                            onNoteOn(channel, note, (vel / 127f).coerceIn(0.05f, 1.0f))
                        }
                    }
                    0x80, 0xA0, 0xB0, 0xE0 -> { // 2 data bytes
                        i += 2
                    }
                    0xC0, 0xD0 -> { // 1 data byte
                        i += 1
                    }
                    else -> {
                        i++
                    }
                }
            }
        }
    }
}
