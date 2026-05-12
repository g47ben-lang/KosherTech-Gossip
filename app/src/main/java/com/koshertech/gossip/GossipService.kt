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
    private val autoPushQueue = mutableMapOf<String, String>() // msgId -> payload
    private var isScanningForPush = false

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
            
            // מתחיל סריקה אוטומטית ברקע
            startActiveScan()

        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startActiveScan() {
        if (isScanningForPush) return
        isScanningForPush = true
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        scanner?.startScan(listOf(filter), settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val mac = result.device.address
                sendBroadcast(Intent("DEVICE_FOUND").putExtra("MAC", mac))

                // אם יש הודעות ממתינות לשליחה אוטומטית - דחוף אותן!
                if (autoPushQueue.isNotEmpty()) {
                    pushNextMessageToDevice(result.device)
                }
            }
        })
    }

    private fun pushNextMessageToDevice(device: BluetoothDevice) {
        if (autoPushQueue.isEmpty()) return
        val entry = autoPushQueue.entries.first()
        val msgId = entry.key
        val payload = entry.value

        device.connectGatt(this, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) gatt.requestMtu(512)
                else gatt.close()
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
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    sendBroadcast(Intent("MSG_STATUS").putExtra("ID", msgId).putExtra("STATUS", "SENT"))
                    autoPushQueue.remove(msgId) // הצליח לשלוח? מסיר מהתור!
                }
                gatt.disconnect(); gatt.close()
            }
        })
    }

    private fun handleIncoming(data: String, senderMac: String) {
        val hash = data.hashCode()
        if (!seenMessages.contains(hash)) {
            seenMessages.add(hash)
            
            // מבנה חדש: TYPE|MAC|SENDER|TARGET|MSG
            val parts = data.split("|")
            if (parts.size >= 5) {
                sendBroadcast(Intent("NEW_MSG").apply {
                    putExtra("MAC", parts[1])
                    putExtra("SENDER", parts[2])
                    putExtra("TARGET", parts[3])
                    putExtra("MSG", parts[4])
                })
                
                val prefs = getSharedPreferences("K", Context.MODE_PRIVATE)
                if (prefs.getBoolean("sound_enabled", true)) {
                    try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {}
                }
                
                // מוסיף לתור כדי להעביר הלאה (Gossip)
                autoPushQueue[UUID.randomUUID().toString()] = data
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SEND") {
            val type = intent.getStringExtra("T") ?: "G"
            val mac = bluetoothManager?.adapter?.address ?: "UNKNOWN"
            val sender = intent.getStringExtra("S") ?: "KosherUser"
            val target = intent.getStringExtra("R") ?: "ALL"
            val msg = intent.getStringExtra("M") ?: ""
            val msgId = intent.getStringExtra("ID") ?: UUID.randomUUID().toString()
            
            val payload = "$type|$mac|$sender|$target|$msg"
            seenMessages.add(payload.hashCode())
            
            // מכניס לתור ההפצה האוטומטי
            autoPushQueue[msgId] = payload
            startActiveScan() // מוודא שהסורק פועל
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
