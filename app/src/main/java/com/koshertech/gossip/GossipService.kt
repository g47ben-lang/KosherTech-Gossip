package com.koshertech.gossip

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.IBinder
import android.os.ParcelUuid
import java.util.UUID

@SuppressLint("MissingPermission")
class GossipService : Service() {

    private val SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private val seenMessages = mutableSetOf<Int>()

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager?.adapter?.isEnabled == true) setupMesh()
    }

    private fun setupMesh() {
        try {
            gattServer = bluetoothManager?.openGattServer(this, object : BluetoothGattServerCallback() {
                override fun onCharacteristicWriteRequest(device: BluetoothDevice, reqId: Int, char: BluetoothGattCharacteristic, prep: Boolean, resp: Boolean, offset: Int, value: ByteArray) {
                    if (resp) gattServer?.sendResponse(device, reqId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    handleIncoming(String(value, Charsets.UTF_8), device.address)
                }
            })
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            gattServer?.addService(service)

            val adv = bluetoothManager?.adapter?.bluetoothLeAdvertiser
            adv?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build(), object : AdvertiseCallback() {})
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startActiveScan() {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner?.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                sendBroadcast(Intent("DEVICE_FOUND").putExtra("MAC", result.device.address))
            }
        })
    }

    private fun forceSendToDevice(mac: String, payload: String, msgId: String) {
        val device = bluetoothManager?.adapter?.getRemoteDevice(mac)
        device?.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.requestMtu(512)
                else { gatt.close(); broadcastStatus(msgId, "FAILED") }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) gatt.discoverServices()
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val char = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                if (char != null) {
                    char.value = payload.toByteArray(Charsets.UTF_8)
                    gatt.writeCharacteristic(char)
                } else gatt.disconnect()
            }
            override fun onCharacteristicWrite(gatt: BluetoothGatt, char: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) broadcastStatus(msgId, "SENT")
                else broadcastStatus(msgId, "FAILED")
                gatt.disconnect(); gatt.close()
            }
        })
    }

    private fun broadcastStatus(msgId: String, status: String) {
        sendBroadcast(Intent("MSG_STATUS").putExtra("ID", msgId).putExtra("STATUS", status))
    }

    private fun handleIncoming(data: String, senderMac: String) {
        val hash = data.hashCode()
        if (!seenMessages.contains(hash)) {
            seenMessages.add(hash)
            val parts = data.split("|") // TYPE|MAC|SENDER|TARGET|MSG
            if (parts.size >= 5) {
                sendBroadcast(Intent("NEW_MSG").apply {
                    putExtra("MAC", parts[1])
                    putExtra("SENDER", parts[2])
                    putExtra("TARGET", parts[3])
                    putExtra("MSG", parts[4])
                })
                try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {}
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START_SCAN" -> startActiveScan()
            "STOP_SCAN" -> bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(object : ScanCallback() {})
            "FORCE_SEND" -> {
                val mac = intent.getStringExtra("MAC") ?: return START_STICKY
                val payload = intent.getStringExtra("PAYLOAD") ?: return START_STICKY
                val msgId = intent.getStringExtra("MSG_ID") ?: return START_STICKY
                forceSendToDevice(mac, payload, msgId)
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
