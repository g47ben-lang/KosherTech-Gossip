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

data class ChatMsg(val id: String, val mac: String, val senderName: String, val target: String, val text: String, var status: String, val isMine: Boolean, val type: String)

class MainActivity : AppCompatActivity() {

    private val allMessages = mutableListOf<ChatMsg>()
    private val discoveredMacs = mutableSetOf<String>()
    
    private lateinit var publicAdapter: MessageAdapter
    private lateinit var privateAdapter: MessageAdapter
    private lateinit var outboxAdapter: MessageAdapter
    private lateinit var radarAdapter: ArrayAdapter<String>
    private lateinit var prefs: SharedPreferences
    
    private lateinit var publicScreen: View
    private lateinit var privateScreen: View
    private lateinit var outboxScreen: View
    private lateinit var settingsScreen: View
    private lateinit var myName: String
    private lateinit var myMac: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = prefs.getString("u", "User_${(10..99).random()}") ?: "User"
        myMac = android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.address ?: "UNKNOWN"

        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        publicScreen = createChatScreen("G")
        privateScreen = createChatScreen("P").apply { visibility = View.GONE }
        outboxScreen = createOutboxScreen().apply { visibility = View.GONE }
        settingsScreen = createSettingsAndRadar().apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "ציבורי").setIcon(android.R.drawable.ic_menu_sort_by_size)
            menu.add(0, 2, 1, "פרטי").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 3, 2, "ניהול שליחה").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 4, 3, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
            setOnItemSelectedListener {
                publicScreen.visibility = if (it.itemId == 1) View.VISIBLE else View.GONE
                privateScreen.visibility = if (it.itemId == 2) View.VISIBLE else View.GONE
                outboxScreen.visibility = if (it.itemId == 3) View.VISIBLE else View.GONE
                settingsScreen.visibility = if (it.itemId == 4) View.VISIBLE else View.GONE
                true
            }
        }

        val lp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        main.addView(publicScreen); main.addView(privateScreen); main.addView(outboxScreen); main.addView(settingsScreen); main.addView(nav, lp)
        setContentView(main)

        registerReceivers()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startService(Intent(this, GossipService::class.java))
    }

    private fun getAlias(mac: String, defaultName: String): String = prefs.getString("alias_$mac", defaultName) ?: defaultName

    private fun registerReceivers() {
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: ""
                val sender = i?.getStringExtra("SENDER") ?: ""
                val target = i?.getStringExtra("TARGET") ?: "ALL"
                val msg = i?.getStringExtra("MSG") ?: ""
                val type = if (target == "ALL") "G" else "P"
                
                val displayName = getAlias(mac, sender)
                
                if (target == "ALL" || target == myName || target == displayName) {
                    allMessages.add(ChatMsg(UUID.randomUUID().toString(), mac, displayName, target, msg, "RECEIVED", false, type))
                    updateLists()
                }
            }
        }, IntentFilter("NEW_MSG"))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val id = i?.getStringExtra("ID"); val status = i?.getStringExtra("STATUS") ?: ""
                allMessages.find { it.id == id }?.status = status
                updateLists()
                if (status == "SENT") Toast.makeText(this@MainActivity, "נשלח בהצלחה!", Toast.LENGTH_SHORT).show()
            }
        }, IntentFilter("MSG_STATUS"))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: return
                if (!discoveredMacs.contains(mac)) {
                    discoveredMacs.add(mac)
                    radarAdapter.clear(); radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                    radarAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("DEVICE_FOUND"))
    }

    private fun updateLists() {
        publicAdapter.notifyDataSetChanged()
        privateAdapter.notifyDataSetChanged()
        outboxAdapter.notifyDataSetChanged()
    }

    inner class MessageAdapter(private val filterType: String) : ArrayAdapter<ChatMsg>(this, 0, allMessages) {
        override fun getCount(): Int = allMessages.count { it.type == filterType || (filterType == "OUTBOX" && it.isMine) }
        override fun getItem(p: Int): ChatMsg = allMessages.filter { it.type == filterType || (filterType == "OUTBOX" && it.isMine) }[p]

        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val msg = getItem(p)
            return LinearLayout(context).apply {
                gravity = if (msg.isMine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
                orientation = LinearLayout.VERTICAL
                
                val bubble = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(30, 20, 30, 15)
                    background = GradientDrawable().apply {
                        setColor(if (msg.isMine) Color.parseColor("#DCF8C6") else Color.WHITE)
                        cornerRadius = 20f
                    }
                    if (!msg.isMine) addView(TextView(context).apply { text = msg.senderName; setTextColor(Color.parseColor("#075E54")); textSize = 12f })
                    addView(TextView(context).apply { text = msg.text; setTextColor(Color.BLACK); textSize = 16f; maxWidth = 800 })
                    if (msg.isMine) {
                        val statusTxt = when(msg.status) { "SENT" -> "נשלח"; "PENDING" -> "ממתין..."; "FAILED" -> "נכשל"; else -> "" }
                        addView(TextView(context).apply { text = statusTxt; setTextColor(Color.GRAY); textSize = 10f; gravity = Gravity.END })
                    }
                }
                addView(bubble)
                
                setOnClickListener { if (msg.isMine) showForceSendDialog(msg) }
            }
        }
    }

    private fun showForceSendDialog(msg: ChatMsg) {
        if (discoveredMacs.isEmpty()) {
            Toast.makeText(this, "אין מכשירים בראדאר. גש להגדרות ובצע חיפוש.", Toast.LENGTH_SHORT).show()
            return
        }
        val array = discoveredMacs.map { getAlias(it, it) }.toTypedArray()
        val macs = discoveredMacs.toList()
        
        AlertDialog.Builder(this).setTitle("שדר למכשיר פיזי:").setItems(array) { _, w ->
            val mac = macs[w]
            val payload = "${msg.type}|$myMac|$myName|${msg.target}|${msg.text}"
            msg.status = "PENDING"; updateLists()
            startService(Intent(this, GossipService::class.java).apply { action = "FORCE_SEND"; putExtra("MAC", mac); putExtra("PAYLOAD", payload); putExtra("MSG_ID", msg.id) })
            Toast.makeText(this, "מתחבר אל ${array[w]}...", Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun createChatScreen(type: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        val lv = ListView(context).apply { 
            divider = null; setStackFromBottom(true)
            val adp = MessageAdapter(type)
            if (type == "G") publicAdapter = adp else privateAdapter = adp
            adapter = adp; layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        
        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(20,20,20,20); setBackgroundColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            
            // הוספת Type מפורש (EditText?) כדי לפתור את שגיאת הקומפילציה
            val targetInp: EditText? = if (type == "P") EditText(context).apply { hint = "נמען"; layoutParams = LinearLayout.LayoutParams(0, -2, 0.4f) } else null
            val msgInp: EditText = EditText(context).apply { 
                hint = "כתוב..."; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                background = GradientDrawable().apply { setStroke(1, Color.LTGRAY); cornerRadius = 40f; setColor(Color.parseColor("#F5F5F5")) }
                setPadding(40, 30, 40, 30)
            }
            
            val btn = Button(context).apply {
                text = "שלח"; setBackgroundColor(Color.parseColor("#00897B")); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.parseColor("#00897B")); cornerRadius = 40f }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(15, 0, 0, 0) }
                
                setOnClickListener {
                    val m = msgInp.text.toString()
                    val target = targetInp?.text?.toString()?.takeIf { it.isNotBlank() } ?: if(type == "G") "ALL" else return@setOnClickListener
                    if (m.isNotBlank()) {
                        val msg = ChatMsg(UUID.randomUUID().toString(), myMac, myName, target, m, "PENDING", true, type)
                        allMessages.add(msg); updateLists(); msgInp.text.clear()
                        showForceSendDialog(msg)
                    }
                }
            }
            if (targetInp != null) addView(targetInp)
            addView(msgInp); addView(btn)
        }
        addView(lv); addView(inputArea)
    }

    private fun createOutboxScreen(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = "תיבת יוצאים - לחץ על הודעה כדי לשדר שוב"; gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30); setBackgroundColor(Color.parseColor("#607D8B")); setTextColor(Color.WHITE)
        })
        addView(ListView(context).apply {
            divider = null; outboxAdapter = MessageAdapter("OUTBOX"); adapter = outboxAdapter; setStackFromBottom(true)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
    }

    private fun createSettingsAndRadar(): ScrollView = ScrollView(this).apply {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        
        container.addView(TextView(context).apply { text = "הגדרות - Kosher Tech"; textSize = 18f; setTextColor(Color.parseColor("#075E54")) })
        val nameInp = EditText(context).apply { setText(myName) }
        container.addView(nameInp)
        container.addView(Button(context).apply {
            text = "שמור שם ציבורי"; setBackgroundColor(Color.LTGRAY)
            setOnClickListener { myName = nameInp.text.toString(); prefs.edit().putString("u", myName).apply(); Toast.makeText(context, "שם עודכן", Toast.LENGTH_SHORT).show() }
        })

        container.addView(TextView(context).apply { text = "ראדאר וניהול אנשי קשר"; textSize = 18f; setTextColor(Color.parseColor("#075E54")); setPadding(0,60,0,20) })
        container.addView(Button(context).apply {
            text = "הפעל סריקה למכשירים"; setBackgroundColor(Color.parseColor("#00897B")); setTextColor(Color.WHITE)
            setOnClickListener { 
                discoveredMacs.clear(); radarAdapter.clear(); radarAdapter.notifyDataSetChanged()
                startService(Intent(context, GossipService::class.java).apply { action = "START_SCAN" })
                Toast.makeText(context, "סורק... הרשימה תתעדכן למטה", Toast.LENGTH_SHORT).show()
                postDelayed({ startService(Intent(context, GossipService::class.java).apply { action = "STOP_SCAN" }) }, 15000)
            }
        })

        val radarList = ListView(context).apply {
            radarAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, mutableListOf())
            adapter = radarAdapter; setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, 600).apply { setMargins(0, 20, 0, 0) }
            setOnItemClickListener { _, _, p, _ ->
                val mac = discoveredMacs.elementAt(p)
                val input = EditText(context).apply { hint = "הכנס כינוי..."; setText(getAlias(mac, "")) }
                AlertDialog.Builder(context).setTitle("ערוך איש קשר").setView(input).setPositiveButton("שמור") { _, _ -> 
                    prefs.edit().putString("alias_$mac", input.text.toString()).apply()
                    radarAdapter.clear(); radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                    updateLists(); Toast.makeText(context, "עודכן!", Toast.LENGTH_SHORT).show()
                }.show()
            }
        }
        container.addView(radarList)
        addView(container)
    }
}
