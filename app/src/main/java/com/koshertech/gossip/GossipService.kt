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
        gattServer = bluetoothManager?.openGattServer(this, object : BluetoothGattServerCallback() {
            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
            ) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                handleIncoming(String(value, Charsets.UTF_8))
            }
        })
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        gattServer?.addService(service)

        // Advertising & Scanning
        val adv = bluetoothManager?.adapter?.bluetoothLeAdvertiser
        adv?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build(), object : AdvertiseCallback() {})

        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = gossipPayload ?: return
                result.device.connectGatt(this@GossipService, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.requestMtu(512) // מבקש חבילה גדולה כדי למנוע פיצול הודעה
                        }
                    }
                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        gatt.discoverServices()
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val c = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
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
            
            // פרוטוקול: [TYPE]|[SENDER]|[TARGET]|[MSG]
            val parts = data.split("|")
            if (parts.size >= 4) {
                val intent = Intent("NEW_MSG").apply {
                    putExtra("TYPE", parts[0])
                    putExtra("SENDER", parts[1])
                    putExtra("TARGET", parts[2])
                    putExtra("MSG", parts[3])
                }
                sendBroadcast(intent)
                playNotify()
            }
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
            val type = intent.getStringExtra("T") ?: "G"
            val sender = intent.getStringExtra("S") ?: "אנונימי"
            val target = intent.getStringExtra("R") ?: "ALL"
            val msg = intent.getStringExtra("M") ?: ""
            gossipPayload = "$type|$sender|$target|$msg"
            seenMessages.add(gossipPayload.hashCode())
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
