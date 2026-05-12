package com.koshertech.gossip

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val messages = mutableListOf<String>()
    private val radarLogs = mutableListOf<String>()
    private lateinit var chatAdapter: MessageAdapter
    private lateinit var radarAdapter: ArrayAdapter<String>
    
    private lateinit var chatScreen: View
    private lateinit var sendScreen: View
    private lateinit var radarScreen: View
    private lateinit var myName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = p.getString("u", "User_${(100..999).random()}")!!
        
        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChat()
        sendScreen = createSend(p).apply { visibility = View.GONE }
        radarScreen = createRadarAndSettings(p).apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "צ'אט").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "שלח").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 3, 2, "ראדאר").setIcon(android.R.drawable.ic_menu_manage)
            setOnItemSelectedListener {
                chatScreen.visibility = if (it.itemId == 1) View.VISIBLE else View.GONE
                sendScreen.visibility = if (it.itemId == 2) View.VISIBLE else View.GONE
                radarScreen.visibility = if (it.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val lp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        main.addView(chatScreen); main.addView(sendScreen); main.addView(radarScreen); main.addView(nav, lp)
        setContentView(main)

        // רישום למאזינים (רסיברים)
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val target = i?.getStringExtra("TARGET") ?: "ALL"
                val sender = i?.getStringExtra("SENDER") ?: ""
                val msg = i?.getStringExtra("MSG") ?: ""
                if (target == "ALL" || target == myName || sender == myName) {
                    val display = if (target == "ALL") "[$sender]: $msg" else "(פרטי מ-$sender): $msg"
                    messages.add(display)
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i?.getStringExtra("LOG")?.let { 
                    radarLogs.add(0, "• $it") // הוספה למעלה
                    if(radarLogs.size > 20) radarLogs.removeLast() // שומר רק 20 אחרונים
                    radarAdapter.notifyDataSetChanged() 
                }
            }
        }, IntentFilter("RADAR_LOG"))

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val perms = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            startService(Intent(this, GossipService::class.java))
        }
    }

    // הפעלת השירות רק אחרי שהמשתמש אישר את ההרשאות! (מונע את הקריסה)
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startService(Intent(this, GossipService::class.java))
        }
    }

    inner class MessageAdapter : ArrayAdapter<String>(this, 0, messages) {
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val m = getItem(p) ?: ""
            val mine = m.contains("אני:") || m.contains("[$myName]")
            return LinearLayout(context).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
                addView(TextView(context).apply {
                    text = m; setPadding(30, 20, 30, 20); maxWidth = 800
                    background = GradientDrawable().apply {
                        setColor(if (mine) Color.parseColor("#DCF8C6") else Color.WHITE)
                        cornerRadius = 20f
                    }
                })
            }
        }
    }

    private fun createChat() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = "Gossip Mesh"; gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40); setBackgroundColor(Color.parseColor("#075E54"))
            setTextColor(Color.WHITE); textSize = 18f
        })
        addView(ListView(context).apply { divider = null; adapter = MessageAdapter().also { this@MainActivity.chatAdapter = it }; setStackFromBottom(true) })
    }

    private fun createSend(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 60, 50, 50)
        val rbAll = RadioButton(context).apply { text = "לכולם"; id = View.generateViewId(); isChecked = true }
        val rbPriv = RadioButton(context).apply { text = "פרטי"; id = View.generateViewId() }
        addView(RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL; addView(rbAll); addView(rbPriv) })
        
        val targetInp = EditText(context).apply { hint = "שם היעד (לפרטי)"; visibility = View.GONE }
        rbPriv.setOnCheckedChangeListener { _, checked -> targetInp.visibility = if (checked) View.VISIBLE else View.GONE }
        
        val msgInp = EditText(context).apply { hint = "כתוב הודעה..." }
        addView(targetInp); addView(msgInp)
        addView(Button(context).apply {
            text = "שדר לרשת"
            setOnClickListener {
                val m = msgInp.text.toString()
                if (m.isNotBlank()) {
                    val target = if (rbAll.isChecked) "ALL" else targetInp.text.toString()
                    messages.add("אני ($target): $m"); chatAdapter.notifyDataSetChanged()
                    startService(Intent(context, GossipService::class.java).apply {
                        action = "SEND"; putExtra("T", if(rbAll.isChecked) "G" else "P")
                        putExtra("S", myName); putExtra("R", target); putExtra("M", m)
                    })
                    msgInp.text.clear()
                    Toast.makeText(context, "נשלח! עבור לראדאר כדי לראות סטטוס", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun createRadarAndSettings(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40)
        
        // חלק א: הגדרות השם
        val n = EditText(context).apply { hint = "השם שלי ברשת"; setText(myName) }
        addView(TextView(context).apply { text = "הגדרות:"; setTextColor(Color.BLUE) })
        addView(n)
        addView(Button(context).apply {
            text = "שמור שם ורענן רשת"
            setOnClickListener { 
                myName = n.text.toString(); p.edit().putString("u", myName).apply()
                startService(Intent(context, GossipService::class.java)) // מתניע מחדש את השירות במקרה וקרס
                Toast.makeText(context, "נשמר!", Toast.LENGTH_SHORT).show()
            }
        })

        // חלק ב: ראדאר בלוטוס
        addView(TextView(context).apply { text = "\nראדאר חיבורים (לוג חי):"; setTextColor(Color.BLUE); setPadding(0, 20, 0, 10) })
        
        val forceScanBtn = Button(context).apply {
            text = "אלץ חיפוש מכשירים עכשיו"
            setBackgroundColor(Color.parseColor("#D32F2F")); setTextColor(Color.WHITE)
            setOnClickListener { 
                startService(Intent(context, GossipService::class.java).apply { action = "FORCE_SCAN" })
            }
        }
        addView(forceScanBtn)

        val logList = ListView(context).apply {
            radarAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, radarLogs)
            adapter = radarAdapter
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        val listParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(0, 20, 0, 0) }
        addView(logList, listParams)
    }
}
