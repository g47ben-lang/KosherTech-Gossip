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
    private lateinit var chatAdapter: MessageAdapter
    private lateinit var prefs: SharedPreferences
    private lateinit var chatScreen: View
    private lateinit var sendScreen: View
    private lateinit var settingsScreen: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("KosherGossip", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            showOldDeviceDialog(); return
        }

        val mainLayout = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        chatScreen = createChatScreen()
        sendScreen = createSendScreen().apply { visibility = View.GONE }
        settingsScreen = createSettingsScreen().apply { visibility = View.GONE }

        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "צ'אט").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "חדש").setIcon(android.R.drawable.ic_input_add)
            menu.add(0, 3, 2, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
            
            setOnItemSelectedListener { item ->
                chatScreen.visibility = if (item.itemId == 1) View.VISIBLE else View.GONE
                sendScreen.visibility = if (item.itemId == 2) View.VISIBLE else View.GONE
                settingsScreen.visibility = if (item.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val navParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        
        mainLayout.addView(chatScreen)
        mainLayout.addView(sendScreen)
        mainLayout.addView(settingsScreen)
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

    // אדפטר מותאם אישית לבועות צ'אט
    inner class MessageAdapter : ArrayAdapter<String>(this, 0, messages) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val msg = getItem(position) ?: ""
            val isMine = msg.startsWith("אני:")
            
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (isMine) Gravity.END else Gravity.START
                setPadding(20, 10, 20, 10)
            }

            val bubble = TextView(context).apply {
                text = msg
                setPadding(30, 20, 30, 20)
                textSize = 16f
                setTextColor(Color.BLACK)
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
        val header = TextView(context).apply {
            text = "Kosher Tech Gossip"
            setBackgroundColor(Color.parseColor("#075E54"))
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
        }
        val listView = ListView(context).apply {
            divider = null
            chatAdapter = MessageAdapter()
            adapter = chatAdapter
            stackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        }
        addView(header)
        addView(listView)
    }

    private fun createSendScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(50, 100, 50, 50)
        gravity = Gravity.CENTER_HORIZONTAL
        val input = EditText(context).apply { 
            hint = "כתוב הודעה לחברה..."
            background = GradientDrawable().apply {
                setStroke(2, Color.GRAY)
                cornerRadius = 10f
            }
            setPadding(20, 20, 20, 20)
        }
        val btn = Button(context).apply {
            text = "שלח עכשיו"
            setBackgroundColor(Color.parseColor("#25D366"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                val user = prefs.getString("username", "אנונימי")
                val text = input.text.toString()
                if (text.isNotBlank()) {
                    messages.add("אני: $text")
                    chatAdapter.notifyDataSetChanged()
                    val i = Intent(context, GossipService::class.java).apply {
                        action = "SEND_MESSAGE"
                        putExtra("USER_NAME", user)
                        putExtra("MESSAGE_TEXT", text)
                    }
                    startService(i)
                    input.text.clear()
                }
            }
        }
        addView(input)
        addView(btn)
    }

    private fun createSettingsScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(50, 100, 50, 50)
        val nameInput = EditText(context).apply { 
            hint = "השם שלך ברשת"
            setText(prefs.getString("username", ""))
        }
        val save = Button(context).apply {
            text = "שמור שם משתמש"
            setOnClickListener {
                prefs.edit().putString("username", nameInput.text.toString()).apply()
                Toast.makeText(context, "נשמר!", Toast.LENGTH_SHORT).show()
            }
        }
        addView(nameInput); addView(save)
    }

    private fun checkPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, perms, 1)
        } else {
            startService(Intent(this, GossipService::class.java))
        }
    }

    private fun showOldDeviceDialog() {
        android.app.AlertDialog.Builder(this).setTitle("Kosher Tech").setMessage("מכשיר ישן מדי").show()
    }
}
