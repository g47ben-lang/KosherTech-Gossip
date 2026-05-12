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
    private var gossipPayload: String? = null
    
    // מניעת חיבורים כפולים לאותו מכשיר
    private val recentlyConnected = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        if (bluetoothManager?.adapter?.isEnabled == true) {
            setupMesh()
            sendLog("שירות הבלוטוס הופעל בהצלחה.")
        } else {
            sendLog("שגיאה: הבלוטוס כבוי!")
        }
    }

    private fun sendLog(msg: String) {
        sendBroadcast(Intent("RADAR_LOG").putExtra("LOG", msg))
    }

    private fun setupMesh() {
        try {
            gattServer = bluetoothManager?.openGattServer(this, object : BluetoothGattServerCallback() {
                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                    preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
                ) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    val data = String(value, Charsets.UTF_8)
                    sendLog("קיבלתי מידע ממכשיר: ${device.address}")
                    handleIncoming(data)
                }
            })
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(CHAR_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            gattServer?.addService(service)

            val adv = bluetoothManager?.adapter?.bluetoothLeAdvertiser
            adv?.startAdvertising(AdvertiseSettings.Builder().setConnectable(true).build(), AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).build(), object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) { sendLog("מפרסם את עצמי ברשת...") }
                override fun onStartFailure(errorCode: Int) { sendLog("שגיאת פרסום: $errorCode") }
            })

            startScanning()

        } catch (e: Exception) {
            sendLog("קריסה בהפעלת הרשת: ${e.message}")
        }
    }

    private fun startScanning() {
        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val payload = gossipPayload ?: return
                val mac = result.device.address
                
                if (recentlyConnected.contains(mac)) return // כבר מנסה להתחבר אליו
                recentlyConnected.add(mac)
                
                sendLog("זיהיתי מכשיר ($mac). מנסה לשלוח...")
                
                result.device.connectGatt(this@GossipService, false, object : BluetoothGattCallback() {
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            sendLog("התחברתי ($mac). מבקש הגדלת חבילה...")
                            gatt.requestMtu(512)
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            recentlyConnected.remove(mac)
                            gatt.close()
                        }
                    }
                    override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) gatt.discoverServices()
                        else { gatt.disconnect(); gatt.close() }
                    }
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        val c = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHAR_UUID)
                        if (c != null) {
                            c.value = payload.toByteArray(Charsets.UTF_8)
                            gatt.writeCharacteristic(c)
                        } else {
                            sendLog("המכשיר לא תומך ב-Gossip ($mac)")
                            gatt.disconnect()
                        }
                    }
                    override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, s: Int) {
                        if (s == BluetoothGatt.GATT_SUCCESS) sendLog("ההודעה הועברה בהצלחה למכשיר!")
                        else sendLog("כשל בשליחה.")
                        gatt.disconnect(); gatt.close()
                        recentlyConnected.remove(mac)
                    }
                })
            }
        })
        sendLog("סורק מכשירים ברקע...")
    }

    private fun handleIncoming(data: String) {
        val hash = data.hashCode()
        if (!seenMessages.contains(hash)) {
            seenMessages.add(hash)
            gossipPayload = data
            val parts = data.split("|")
            if (parts.size >= 4) {
                sendBroadcast(Intent("NEW_MSG").apply {
                    putExtra("TYPE", parts[0])
                    putExtra("SENDER", parts[1])
                    putExtra("TARGET", parts[2])
                    putExtra("MSG", parts[3])
                })
                playNotify()
            }
        }
    }

    private fun playNotify() {
        try { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)).play() } catch (e: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "SEND" -> {
                val type = intent.getStringExtra("T") ?: "G"
                val sender = intent.getStringExtra("S") ?: "אנונימי"
                val target = intent.getStringExtra("R") ?: "ALL"
                val msg = intent.getStringExtra("M") ?: ""
                gossipPayload = "$type|$sender|$target|$msg"
                seenMessages.add(gossipPayload.hashCode())
                sendLog("הודעה חדשה נכנסה לתור ההפצה.")
                recentlyConnected.clear() // מאפשר התחברות מחודשת לכולם
            }
            "FORCE_SCAN" -> {
                recentlyConnected.clear()
                sendLog("איפוס זיכרון חיבורים. מחפש שוב...")
            }
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
