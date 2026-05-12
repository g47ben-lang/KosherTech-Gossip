package com.koshertech.gossip

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.UUID

// מודל להודעות בממשק
data class ChatMsg(val id: String, val sender: String, val text: String, var status: String, val isMine: Boolean)

class MainActivity : AppCompatActivity() {

    private val messages = mutableListOf<ChatMsg>()
    private val foundDevices = mutableMapOf<String, String>() // MAC -> Name
    
    private lateinit var chatAdapter: MessageAdapter
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private lateinit var devicesListUi: ListView
    private lateinit var scanSpinner: ProgressBar
    
    private lateinit var chatScreen: View
    private lateinit var radarScreen: View
    private lateinit var myName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = p.getString("u", "User_${(10..99).random()}")!!
        
        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChatAndSend()
        radarScreen = createRadar().apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "צ'אט ושליחה").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 2, 1, "ראדאר ורשת").setIcon(android.R.drawable.ic_menu_manage)
            setOnItemSelectedListener {
                chatScreen.visibility = if (it.itemId == 1) View.VISIBLE else View.GONE
                radarScreen.visibility = if (it.itemId == 2) View.VISIBLE else View.GONE
                true
            }
        }

        val lp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        main.addView(chatScreen); main.addView(radarScreen); main.addView(nav, lp)
        setContentView(main)

        registerReceivers()
        checkPermissions()
    }

    private fun registerReceivers() {
        // קבלת הודעה חדשה
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val sender = i?.getStringExtra("SENDER") ?: ""
                val target = i?.getStringExtra("TARGET") ?: "ALL"
                val msg = i?.getStringExtra("MSG") ?: ""
                if (target == "ALL" || target == myName) {
                    messages.add(ChatMsg(UUID.randomUUID().toString(), sender, msg, "RECEIVED", false))
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))

        // קבלת סטטוס (נשלח/נכשל)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val id = i?.getStringExtra("ID"); val status = i?.getStringExtra("STATUS") ?: ""
                messages.find { it.id == id }?.status = status
                chatAdapter.notifyDataSetChanged()
                Toast.makeText(this@MainActivity, if(status=="SENT") "הודעה עברה!" else "נכשל", Toast.LENGTH_SHORT).show()
            }
        }, IntentFilter("MSG_STATUS"))

        // מציאת מכשיר חי (עם האפליקציה בלבד!)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: return
                val name = i.getStringExtra("NAME") ?: "Kosher"
                if (!foundDevices.containsKey(mac)) {
                    foundDevices[mac] = name
                    updateDevicesList()
                }
            }
        }, IntentFilter("DEVICE_FOUND"))
    }

    private fun updateDevicesList() {
        val list = foundDevices.map { "${it.value}\n[${it.key}]" }
        devicesAdapter.clear()
        devicesAdapter.addAll(list)
        devicesAdapter.notifyDataSetChanged()
    }

    // הבועות של הוואטסאפ (עם הסטטוס)
    inner class MessageAdapter : ArrayAdapter<ChatMsg>(this, 0, messages) {
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val msg = getItem(p)!!
            return LinearLayout(context).apply {
                gravity = if (msg.isMine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
                
                val textContent = if (msg.isMine) {
                    val statusIcon = when(msg.status) {
                        "PENDING" -> "⏳ ממתין"
                        "SENT" -> "✔️ נשלח"
                        "FAILED" -> "❌ נכשל"
                        else -> ""
                    }
                    "${msg.text}\n$statusIcon"
                } else {
                    "[${msg.sender}]: ${msg.text}"
                }

                addView(TextView(context).apply {
                    text = textContent; setPadding(30, 20, 30, 20); maxWidth = 800
                    background = GradientDrawable().apply {
                        setColor(if (msg.isMine) Color.parseColor("#DCF8C6") else Color.WHITE)
                        cornerRadius = 20f
                    }
                })
                
                // לחיצה ארוכה - פותח חלון חיבור ידני
                setOnLongClickListener {
                    if (msg.isMine) showForceSendDialog(msg)
                    true
                }
            }
        }
    }

    private fun showForceSendDialog(msg: ChatMsg) {
        if (foundDevices.isEmpty()) {
            Toast.makeText(this, "אין מכשירים מחוברים. כנס לראדאר לחפש.", Toast.LENGTH_SHORT).show()
            return
        }
        val array = foundDevices.map { "${it.value} (${it.key})" }.toTypedArray()
        val macs = foundDevices.keys.toList()
        
        AlertDialog.Builder(this)
            .setTitle("אלץ שליחת הודעה")
            .setItems(array) { _, which ->
                val mac = macs[which]
                val payload = "G|$myName|ALL|${msg.text}"
                msg.status = "PENDING"
                chatAdapter.notifyDataSetChanged()
                
                startService(Intent(this, GossipService::class.java).apply {
                    action = "FORCE_SEND"
                    putExtra("MAC", mac)
                    putExtra("PAYLOAD", payload)
                    putExtra("MSG_ID", msg.id)
                })
                Toast.makeText(this, "מנסה לדחוף הודעה ל-$mac...", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun createChatAndSend() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        // רשימת הודעות
        val lv = ListView(context).apply { 
            divider = null; adapter = MessageAdapter().also { this@MainActivity.chatAdapter = it }; setStackFromBottom(true) 
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        
        // אזור הקלדה
        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20,20,20,20); setBackgroundColor(Color.WHITE)
            val msgInp = EditText(context).apply { hint = "הודעה (לחיצה ארוכה על בועה תאלץ שליחה)"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            val btn = Button(context).apply {
                text = "שלח"; setBackgroundColor(Color.parseColor("#25D366")); setTextColor(Color.WHITE)
                setOnClickListener {
                    val text = msgInp.text.toString()
                    if (text.isNotBlank()) {
                        val msgId = UUID.randomUUID().toString()
                        messages.add(ChatMsg(msgId, myName, text, "PENDING", true))
                        chatAdapter.notifyDataSetChanged()
                        msgInp.text.clear()
                    }
                }
            }
            addView(msgInp); addView(btn)
        }
        addView(lv); addView(inputArea)
    }

    private fun createRadar() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
        
        val title = TextView(context).apply { text = "ראדאר וחיפוש מכשירים חכמים"; textSize = 20f; setTextColor(Color.parseColor("#075E54")) }
        
        scanSpinner = ProgressBar(context).apply { visibility = View.GONE; setPadding(0,20,0,20) }
        
        val scanBtn = Button(context).apply {
            text = "חפש משתמשי אפליקציה בסביבה"
            setOnClickListener {
                scanSpinner.visibility = View.VISIBLE
                foundDevices.clear(); updateDevicesList()
                startService(Intent(context, GossipService::class.java).apply { action = "START_SCAN" })
                // סוגר סריקה אחרי 10 שניות לחסוך סוללה
                postDelayed({ 
                    scanSpinner.visibility = View.GONE
                    startService(Intent(context, GossipService::class.java).apply { action = "STOP_SCAN" })
                    if(foundDevices.isEmpty()) Toast.makeText(context, "לא נמצאו מכשירים פעילים.", Toast.LENGTH_SHORT).show()
                }, 10000)
            }
        }

        devicesListUi = ListView(context).apply {
            devicesAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, mutableListOf())
            adapter = devicesAdapter
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }

        addView(title); addView(scanBtn); addView(scanSpinner); addView(devicesListUi)
    }

    private fun checkPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startService(Intent(this, GossipService::class.java))
    }
}
