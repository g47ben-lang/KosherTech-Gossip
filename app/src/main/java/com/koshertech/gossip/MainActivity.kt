package com.koshertech.gossip

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.util.UUID

data class ChatMsg(val id: String, val senderMac: String, val senderName: String, val text: String, var status: String, val isMine: Boolean)

class MainActivity : AppCompatActivity() {

    private val messages = mutableListOf<ChatMsg>()
    private val discoveredMacs = mutableSetOf<String>()
    
    private lateinit var chatAdapter: MessageAdapter
    private lateinit var radarAdapter: ArrayAdapter<String>
    private lateinit var prefs: SharedPreferences
    
    private lateinit var chatScreen: View
    private lateinit var radarScreen: View
    private lateinit var settingsScreen: View
    private lateinit var myName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = prefs.getString("u", "User_${(10..99).random()}")!!
        
        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) } // רקע ווטסאפ קלאסי
        
        chatScreen = createChatAndSend()
        radarScreen = createRadar().apply { visibility = View.GONE }
        settingsScreen = createSettings().apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "צ'אט").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "ראדאר").setIcon(android.R.drawable.ic_menu_search)
            menu.add(0, 3, 2, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
            setOnItemSelectedListener {
                chatScreen.visibility = if (it.itemId == 1) View.VISIBLE else View.GONE
                radarScreen.visibility = if (it.itemId == 2) View.VISIBLE else View.GONE
                settingsScreen.visibility = if (it.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val lp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        main.addView(chatScreen); main.addView(radarScreen); main.addView(settingsScreen); main.addView(nav, lp)
        setContentView(main)

        registerReceivers()
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g[0] == PackageManager.PERMISSION_GRANTED) startService(Intent(this, GossipService::class.java))
    }

    private fun getAlias(mac: String, defaultName: String): String {
        return prefs.getString("alias_$mac", defaultName) ?: defaultName
    }

    private fun registerReceivers() {
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: ""
                val sender = i?.getStringExtra("SENDER") ?: ""
                val target = i?.getStringExtra("TARGET") ?: "ALL"
                val msg = i?.getStringExtra("MSG") ?: ""
                
                val displayName = getAlias(mac, sender)
                
                if (target == "ALL" || target == myName || target == displayName) {
                    messages.add(ChatMsg(UUID.randomUUID().toString(), mac, displayName, msg, "RECEIVED", false))
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val id = i?.getStringExtra("ID"); val status = i?.getStringExtra("STATUS") ?: ""
                messages.find { it.id == id }?.status = status
                chatAdapter.notifyDataSetChanged()
            }
        }, IntentFilter("MSG_STATUS"))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: return
                if (!discoveredMacs.contains(mac)) {
                    discoveredMacs.add(mac)
                    radarAdapter.clear()
                    radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                    radarAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("DEVICE_FOUND"))
    }

    // עיצוב הבועות (מינימליסטי)
    inner class MessageAdapter : ArrayAdapter<ChatMsg>(this, 0, messages) {
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val msg = getItem(p)!!
            return LinearLayout(context).apply {
                gravity = if (msg.isMine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
                orientation = LinearLayout.VERTICAL
                
                val bubble = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(30, 20, 30, 15)
                    background = GradientDrawable().apply {
                        setColor(if (msg.isMine) Color.parseColor("#DCF8C6") else Color.WHITE)
                        cornerRadius = 20f
                    }
                    
                    if (!msg.isMine) {
                        addView(TextView(context).apply { text = msg.senderName; setTextColor(Color.parseColor("#075E54")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD) })
                    }
                    
                    addView(TextView(context).apply { text = msg.text; setTextColor(Color.BLACK); textSize = 16f; maxWidth = 800 })
                    
                    if (msg.isMine) {
                        val statusTxt = if (msg.status == "SENT") "נשלח" else "ממתין לשליחה"
                        addView(TextView(context).apply { text = statusTxt; setTextColor(Color.GRAY); textSize = 10f; gravity = Gravity.END })
                    }
                }
                addView(bubble)
            }
        }
    }

    private fun createChatAndSend() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        
        // כותרת צ'אט
        addView(TextView(context).apply {
            text = "Kosher Tech Mesh"; gravity = Gravity.CENTER; setPadding(0, 40, 0, 40)
            setBackgroundColor(Color.parseColor("#075E54")); setTextColor(Color.WHITE); textSize = 18f
        })
        
        // רשימת הודעות
        addView(ListView(context).apply { 
            divider = null; adapter = MessageAdapter().also { this@MainActivity.chatAdapter = it }; setStackFromBottom(true) 
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        
        // תיבת שליחה חכמה משולבת
        val inputArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20); setBackgroundColor(Color.WHITE)
            
            // בורר ציבורי/פרטי
            val modeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val rbAll = RadioButton(context).apply { text = "ציבורי"; id = View.generateViewId(); isChecked = true }
            val rbPriv = RadioButton(context).apply { text = "פרטי"; id = View.generateViewId() }
            modeGroup.addView(rbAll); modeGroup.addView(rbPriv)
            
            val targetInp = EditText(context).apply { hint = "שם/כינוי היעד"; visibility = View.GONE; textSize = 14f }
            rbPriv.setOnCheckedChangeListener { _, checked -> targetInp.visibility = if (checked) View.VISIBLE else View.GONE }
            
            val textRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val msgInp = EditText(context).apply { 
                hint = "כתוב הודעה..."; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                background = GradientDrawable().apply { setStroke(1, Color.LTGRAY); cornerRadius = 40f; setColor(Color.parseColor("#F5F5F5")) }
                setPadding(40, 30, 40, 30)
            }
            
            val btn = Button(context).apply {
                text = "שלח"; setBackgroundColor(Color.parseColor("#00897B")); setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.parseColor("#00897B")); cornerRadius = 40f }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(15, 0, 0, 0) }
                
                setOnClickListener {
                    val m = msgInp.text.toString()
                    if (m.isNotBlank()) {
                        val msgId = UUID.randomUUID().toString()
                        val target = if (rbAll.isChecked) "ALL" else targetInp.text.toString()
                        messages.add(ChatMsg(msgId, "", myName, "[$target] $m", "PENDING", true))
                        chatAdapter.notifyDataSetChanged()
                        
                        startService(Intent(context, GossipService::class.java).apply {
                            action = "SEND"; putExtra("ID", msgId); putExtra("T", if(rbAll.isChecked) "G" else "P")
                            putExtra("S", myName); putExtra("R", target); putExtra("M", m)
                        })
                        msgInp.text.clear()
                    }
                }
            }
            textRow.addView(msgInp); textRow.addView(btn)
            addView(modeGroup); addView(targetInp); addView(textRow)
        }
        addView(inputArea)
    }

    private fun createRadar() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
        addView(TextView(context).apply { text = "מכשירים מזוהים בסביבה"; textSize = 20f; setTextColor(Color.parseColor("#075E54")); setPadding(0,0,0,20) })
        addView(TextView(context).apply { text = "לחיצה על מכשיר תאפשר לתת לו שם (איש קשר)."; textSize = 14f; setTextColor(Color.GRAY); setPadding(0,0,0,40) })
        
        addView(ListView(context).apply {
            radarAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, mutableListOf())
            adapter = radarAdapter
            setBackgroundColor(Color.WHITE)
            setOnItemClickListener { _, _, pos, _ ->
                val mac = discoveredMacs.elementAt(pos)
                val input = EditText(context).apply { hint = "הכנס שם..."; setText(getAlias(mac, "")) }
                AlertDialog.Builder(context).setTitle("שמירת איש קשר").setView(input)
                    .setPositiveButton("שמור") { _, _ -> 
                        prefs.edit().putString("alias_$mac", input.text.toString()).apply()
                        radarAdapter.clear(); radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                        Toast.makeText(context, "עודכן!", Toast.LENGTH_SHORT).show()
                    }.show()
            }
        })
    }

    private fun createSettings() = ScrollView(this).apply {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        
        container.addView(TextView(context).apply { text = "הגדרות מתקדמות - Kosher Tech"; textSize = 22f; setTextColor(Color.parseColor("#075E54")); setPadding(0,0,0,40) })
        
        // שם משתמש
        val nameInp = EditText(context).apply { setText(myName) }
        container.addView(TextView(context).apply { text = "השם הציבורי שלי:" })
        container.addView(nameInp)
        container.addView(Button(context).apply {
            text = "עדכן שם"; setBackgroundColor(Color.LTGRAY)
            setOnClickListener { myName = nameInp.text.toString(); prefs.edit().putString("u", myName).apply(); Toast.makeText(context, "שם עודכן", Toast.LENGTH_SHORT).show() }
        })

        // צלילים
        val soundCheck = CheckBox(context).apply { 
            text = "הפעל צליל קבלת הודעה"
            isChecked = prefs.getBoolean("sound_enabled", true)
            setOnCheckedChangeListener { _, isC -> prefs.edit().putBoolean("sound_enabled", isC).apply() }
        }
        container.addView(soundCheck.apply { setPadding(0,40,0,0) })

        // מחיקת היסטוריה
        container.addView(Button(context).apply {
            text = "נקה את כל היסטוריית הצ'אט"
            setBackgroundColor(Color.parseColor("#D32F2F")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 60, 0, 0) }
            setOnClickListener { messages.clear(); chatAdapter.notifyDataSetChanged(); Toast.makeText(context, "נוקה", Toast.LENGTH_SHORT).show() }
        })
        
        addView(container)
    }
}
