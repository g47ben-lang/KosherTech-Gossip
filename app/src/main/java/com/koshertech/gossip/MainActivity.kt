package com.koshertech.gossip

import android.Manifest
import android.content.*
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

class MainActivity : AppCompatActivity() {

    private val messages = mutableListOf<String>()
    private lateinit var adapter: MessageAdapter
    private lateinit var chatScreen: View
    private lateinit var sendScreen: View
    private lateinit var settingsScreen: View
    private lateinit var myName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = p.getString("u", "User_${(100..999).random()}")!!
        
        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChat()
        sendScreen = createSend(p).apply { visibility = View.GONE }
        settingsScreen = createSettings(p).apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "צ'אט").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "שלח").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 3, 2, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
            setOnItemSelectedListener {
                chatScreen.visibility = if (it.itemId == 1) View.VISIBLE else View.GONE
                sendScreen.visibility = if (it.itemId == 2) View.VISIBLE else View.GONE
                settingsScreen.visibility = if (it.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val lp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        main.addView(chatScreen); main.addView(sendScreen); main.addView(settingsScreen); main.addView(nav, lp)
        setContentView(main)

        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        startService(Intent(this, GossipService::class.java))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val type = i?.getStringExtra("TYPE") ?: "G"
                val sender = i?.getStringExtra("SENDER") ?: ""
                val target = i?.getStringExtra("TARGET") ?: "ALL"
                val msg = i?.getStringExtra("MSG") ?: ""

                // סינון: הצג אם זה לקבוצה או שזה פרטי אלי
                if (target == "ALL" || target == myName || sender == myName) {
                    val display = if (target == "ALL") "[$sender]: $msg" else "(פרטי מ-$sender): $msg"
                    messages.add(display)
                    adapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))
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
            text = "Kosher Tech Mesh"; gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40); setBackgroundColor(Color.parseColor("#075E54"))
            setTextColor(Color.WHITE); textSize = 18f
        })
        addView(ListView(context).apply { divider = null; adapter = MessageAdapter().also { this@MainActivity.adapter = it }; setStackFromBottom(true) })
    }

    private fun createSend(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 60, 50, 50)
        val modeGroup = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        val rbAll = RadioButton(context).apply { text = "קבוצה"; id = View.generateViewId(); isChecked = true }
        val rbPriv = RadioButton(context).apply { text = "פרטי"; id = View.generateViewId() }
        modeGroup.addView(rbAll); modeGroup.addView(rbPriv)
        
        val targetInp = EditText(context).apply { hint = "שם היעד (לפרטי)"; visibility = View.GONE }
        rbPriv.setOnCheckedChangeListener { _, checked -> targetInp.visibility = if (checked) View.VISIBLE else View.GONE }
        
        val msgInp = EditText(context).apply { hint = "כתוב הודעה..." }
        
        addView(TextView(context).apply { text = "סוג הודעה:" })
        addView(modeGroup); addView(targetInp); addView(msgInp)
        addView(Button(context).apply {
            text = "שדר לרשת"
            setOnClickListener {
                val t = if (rbAll.isChecked) "G" else "P"
                val target = if (rbAll.isChecked) "ALL" else targetInp.text.toString()
                val m = msgInp.text.toString()
                if (m.isNotBlank()) {
                    messages.add("אני ($target): $m"); adapter.notifyDataSetChanged()
                    startService(Intent(context, GossipService::class.java).apply {
                        action = "SEND"; putExtra("T", t); putExtra("S", myName); putExtra("R", target); putExtra("M", m)
                    })
                    msgInp.text.clear()
                }
            }
        })
    }

    private fun createSettings(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 100, 50, 50)
        val n = EditText(context).apply { hint = "השם שלך"; setText(p.getString("u", myName)) }
        addView(TextView(context).apply { text = "הגדרות פרופיל" })
        addView(n)
        addView(Button(context).apply {
            text = "שמור שם"
            setOnClickListener { 
                myName = n.text.toString()
                p.edit().putString("u", myName).apply() 
                Toast.makeText(context, "נשמר!", Toast.LENGTH_SHORT).show()
            }
        })
    }
}
