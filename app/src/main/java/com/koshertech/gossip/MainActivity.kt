package com.koshertech.gossip

import android.Manifest
import android.bluetooth.BluetoothAdapter
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
    private lateinit var chatAdapter: MessageAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var chatScreen: View
    private lateinit var sendScreen: View
    private lateinit var toolsScreen: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("KosherGossip", Context.MODE_PRIVATE)

        val mainLayout = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChatScreen()
        sendScreen = createSendScreen().apply { visibility = View.GONE }
        toolsScreen = createToolsScreen().apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "לוח").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "שלח").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 3, 2, "כלים").setIcon(android.R.drawable.ic_menu_manage)
            
            setOnItemSelectedListener { item ->
                chatScreen.visibility = if (item.itemId == 1) View.VISIBLE else View.GONE
                sendScreen.visibility = if (item.itemId == 2) View.VISIBLE else View.GONE
                toolsScreen.visibility = if (item.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val navParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        
        mainLayout.addView(chatScreen)
        mainLayout.addView(sendScreen)
        mainLayout.addView(toolsScreen)
        mainLayout.addView(nav, navParams)
        
        setContentView(mainLayout)
        checkPermissions()
        
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra("DATA")?.let { 
                    messages.add(it)
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))
    }

    inner class MessageAdapter : ArrayAdapter<String>(this, 0, messages) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val msg = getItem(position) ?: ""
            val isMine = msg.startsWith("אני:")
            val wrapper = LinearLayout(context).apply {
                gravity = if (isMine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
            }
            val bubble = TextView(context).apply {
                text = msg
                setPadding(30, 20, 30, 20)
                background = GradientDrawable().apply {
                    setColor(if (isMine) Color.parseColor("#DCF8C6") else Color.WHITE)
                    cornerRadius = 25f
                }
                maxWidth = 700
            }
            wrapper.addView(bubble)
            return wrapper
        }
    }

    private fun createChatScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(context).apply {
            text = "Kosher Tech Gossip"
            setBackgroundColor(Color.parseColor("#075E54"))
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER
        })
        val lv = ListView(context).apply {
            divider = null
            chatAdapter = MessageAdapter()
            adapter = chatAdapter
        }
        lv.setStackFromBottom(true)
        addView(lv)
    }

    private fun createSendScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 80, 40, 40)
        val input = EditText(context).apply { hint = "הודעה חדשה..." }
        val btn = Button(context).apply {
            text = "שדר לרשת"
            setOnClickListener {
                val user = prefs.getString("username", "אנונימי")
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    messages.add("אני: $text")
                    chatAdapter.notifyDataSetChanged()
                    startService(Intent(context, GossipService::class.java).apply {
                        action = "SEND_MESSAGE"
                        putExtra("USER_NAME", user)
                        putExtra("MESSAGE_TEXT", text)
                    })
                    input.text.clear()
                }
            }
        }
        addView(input); addView(btn)
    }

    private fun createToolsScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 80, 40, 40)
        
        val nameInput = EditText(context).apply { 
            hint = "השם שלך"
            setText(prefs.getString("username", ""))
        }
        val saveBtn = Button(context).apply {
            text = "שמור שם"
            setOnClickListener { prefs.edit().putString("username", nameInput.text.toString()).apply() }
        }
        
        val diagBtn = Button(context).apply {
            text = "בדיקת מצב בלוטוס"
            setBackgroundColor(Color.BLUE)
            setTextColor(Color.WHITE)
            setOnClickListener { 
                val btEnabled = BluetoothAdapter.getDefaultAdapter()?.isEnabled ?: false
                Toast.makeText(context, if(btEnabled) "בלוטוס פעיל ותקין" else "נא להפעיל בלוטוס!", Toast.LENGTH_LONG).show()
            }
        }

        val refreshBtn = Button(context).apply {
            text = "אתחול רשת Gossip"
            setOnClickListener { 
                stopService(Intent(context, GossipService::class.java))
                startService(Intent(context, GossipService::class.java))
                Toast.makeText(context, "הרשת אותחלה", Toast.LENGTH_SHORT).show()
            }
        }

        addView(TextView(context).apply { text = "ניהול רשת (ללא GPS)"; textSize = 20f; setPadding(0,0,0,40) })
        addView(nameInput); addView(saveBtn); addView(refreshBtn); addView(diagBtn)
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        }
    }
}
