package com.koshertech.gossip

import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.IBinder
import android.os.ParcelUuid
import java.util.UUID

class GossipService : Service() {

    private val SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805f9b34fb")
    private val CHAR_UUID = UUID.fromString("00002A37-0000-1000-8000-00805f9b34fb")
    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private val seenMessages = mutableSetOf<Int>()
    private var gossipPayload: String? = null

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        setupMesh()
    }

    private fun setupMesh() {
        // שרת לקבלת הודעות
        gattServer = bluetoothManager?.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                val text = String(value, Charsets.UTF_8)
                handleIncoming(text)
            }
        })
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        gattServer?.addService(service)

        // שידור עצמי (Advertising)
        val adv = bluetoothManager?.adapter?.bluetoothLeAdvertiser
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        adv?.startAdvertising(settings, data, object : AdvertiseCallback() {})

        // סריקה מתמדת להפצה (Scanning)
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner?.startScan(null, scanSettings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = gossipPayload ?: return
                // דחיפה אקטיבית למכשיר שנמצא
                result.device.connectGatt(this@GossipService, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val s = gatt.getService(SERVICE_UUID)
                        val c = s?.getCharacteristic(CHAR_UUID)
                        if (c != null) {
                            c.value = payload.toByteArray(Charsets.UTF_8)
                            gatt.writeCharacteristic(c)
                        }
                    }
                    override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                        gatt.disconnect(); gatt.close()
                    }
                })
            }
        })
    }

    private fun handleIncoming(data: String) {
        val hash = data.hashCode()
        if (!seenMessages.contains(hash)) {
            seenMessages.add(hash)
            gossipPayload = data
            playNotify()
            sendBroadcast(Intent("NEW_MSG").apply { putExtra("DATA", data) })
        }
    }

    private fun playNotify() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(this, uri).play()
        } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SEND") {
            val user = intent.getStringExtra("U") ?: "אנונימי"
            val text = intent.getStringExtra("M") ?: ""
            gossipPayload = "$user: $text"
            seenMessages.add(gossipPayload.hashCode())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
