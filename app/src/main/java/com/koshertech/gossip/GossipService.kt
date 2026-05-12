package com.koshertech.gossip

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

class GossipService : Service() {

    private val SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private val seenMessages = mutableSetOf<Int>()
    private var messageToGossip: String? = null

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        startServer()
        startAdvertising()
        startScanning()
    }

    private fun startServer() {
        gattServer = bluetoothManager?.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                val incomingData = String(value, Charsets.UTF_8)
                processMessage(incomingData)
            }
        })
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val char = BluetoothGattCharacteristic(CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE)
        service.addCharacteristic(char)
        gattServer?.addService(service)
    }

    private fun processMessage(data: String) {
        val hash = data.hashCode()
        if (!seenMessages.contains(hash)) {
            seenMessages.add(hash)
            messageToGossip = data // שומר להפצה הלאה
            playNotificationSound()
            sendBroadcast(Intent("NEW_MSG").apply { putExtra("DATA", data) })
        }
    }

    private fun playNotificationSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri).play()
        } catch (e: Exception) {}
    }

    private fun startAdvertising() {
        val adv = bluetoothManager?.adapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        adv?.startAdvertising(settings, data, object : AdvertiseCallback() {})
    }

    private fun startScanning() {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val gossipText = messageToGossip ?: return
                // התחברות אוטומטית להעברת המידע
                result.device.connectGatt(this@GossipService, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val srv = gatt.getService(SERVICE_UUID)
                        val char = srv?.getCharacteristic(CHAR_UUID)
                        if (char != null) {
                            char.value = gossipText.toByteArray(Charsets.UTF_8)
                            gatt.writeCharacteristic(char)
                        }
                    }
                    override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                        gatt.disconnect(); gatt.close()
                    }
                })
            }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SEND_MESSAGE") {
            val user = intent.getStringExtra("USER_NAME") ?: "אנונימי"
            val text = intent.getStringExtra("MESSAGE_TEXT") ?: ""
            messageToGossip = "$user: $text"
            seenMessages.add(messageToGossip.hashCode())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
