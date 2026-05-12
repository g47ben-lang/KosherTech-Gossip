package com.koshertech.gossip

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.*
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
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private val messages = mutableListOf<String>()
    private lateinit var adapter: MessageAdapter
    private lateinit var chatScreen: View
    private lateinit var sendScreen: View
    private lateinit var settingsScreen: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("K", Context.MODE_PRIVATE)

        // רקע וואטסאפ קלאסי
        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChat()
        sendScreen = createSend(prefs).apply { visibility = View.GONE }
        settingsScreen = createSettings(prefs).apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "לוח").setIcon(android.R.drawable.ic_dialog_email)
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

        // בקשת הרשאות - אבל לא עוצרים אם אין GPS
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        startService(Intent(this, GossipService::class.java))

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                i?.getStringExtra("DATA")?.let { messages.add(it); adapter.notifyDataSetChanged() }
            }
        }, IntentFilter("NEW_MSG"))
    }

    inner class MessageAdapter : ArrayAdapter<String>(this, 0, messages) {
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val m = getItem(p) ?: ""
            val mine = m.startsWith("אני:")
            return LinearLayout(context).apply {
                gravity = if (mine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
                addView(TextView(context).apply {
                    text = m
                    setPadding(30, 20, 30, 20)
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
            text = "Kosher Tech - לוח מודעות"; gravity = Gravity.CENTER
            setPadding(0, 40, 0, 40); setBackgroundColor(Color.parseColor("#075E54"))
            setTextColor(Color.WHITE); textSize = 18f
        })
        addView(ListView(context).apply { 
            divider = null; adapter = MessageAdapter().also { this@MainActivity.adapter = it }
            setStackFromBottom(true)
        })
    }

    private fun createSend(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 100, 50, 50)
        val inp = EditText(context).apply { hint = "הודעה..." }
        addView(inp)
        addView(Button(context).apply {
            text = "שלח לרשת"
            setOnClickListener {
                val u = p.getString("u", "אנונימי"); val t = inp.text.toString()
                if (t.isNotBlank()) {
                    messages.add("אני: $t"); adapter.notifyDataSetChanged()
                    startService(Intent(context, GossipService::class.java).apply {
                        action = "SEND"; putExtra("U", u); putExtra("M", t)
                    })
                    inp.text.clear()
                }
            }
        })
    }

    private fun createSettings(p: SharedPreferences) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(50, 100, 50, 50)
        val n = EditText(context).apply { hint = "השם שלך"; setText(p.getString("u", "")) }
        addView(n)
        addView(Button(context).apply {
            text = "שמור"
            setOnClickListener { p.edit().putString("u", n.text.toString()).apply() }
        })
        addView(TextView(context).apply { 
            text = "\nסטטוס חומרה:\nBluetooth: ${BluetoothAdapter.getDefaultAdapter()?.isEnabled}\nGPS: לא זוהה (עקיפה פעילה)"
            setPadding(0, 50, 0, 0)
        })
    }
}
