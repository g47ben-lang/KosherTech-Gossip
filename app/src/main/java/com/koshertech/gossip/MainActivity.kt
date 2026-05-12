package com.koshertech.gossip

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
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
    private lateinit var chatAdapter: ArrayAdapter<String>
    private lateinit var prefs: SharedPreferences
    
    // מיכלים למסכים
    private lateinit var chatScreen: LinearLayout
    private lateinit var sendScreen: LinearLayout
    private lateinit var settingsScreen: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("KosherGossip", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            showOldDeviceDialog()
            return
        }

        val mainLayout = RelativeLayout(this)
        
        // 1. מסך צ'אט (לוח מודעות)
        chatScreen = createChatScreen()
        
        // 2. מסך שליחה
        sendScreen = createSendScreen()
        sendScreen.visibility = View.GONE

        // 3. מסך הגדרות
        settingsScreen = createSettingsScreen()
        settingsScreen.visibility = View.GONE

        // תפריט תחתון
        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "לוח").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 2, 1, "שלח").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 3, 2, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
            
            setOnItemSelectedListener { item ->
                chatScreen.visibility = if (item.itemId == 1) View.VISIBLE else View.GONE
                sendScreen.visibility = if (item.itemId == 2) View.VISIBLE else View.GONE
                settingsScreen.visibility = if (item.itemId == 3) View.VISIBLE else View.GONE
                true
            }
        }

        val navParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        }
        
        mainLayout.addView(chatScreen)
        mainLayout.addView(sendScreen)
        mainLayout.addView(settingsScreen)
        mainLayout.addView(nav, navParams)
        
        setContentView(mainLayout)
        checkPermissions()
        
        // האזנה להודעות חדשות מהרשת
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.getStringExtra("DATA")?.let { 
                    messages.add(0, it)
                    chatAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("NEW_MSG"))
    }

    private fun createChatScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#F0F0F0"))
        val title = TextView(context).apply {
            text = "לוח מודעות ישיבתי"
            textSize = 20f
            setPadding(40, 40, 40, 40)
            gravity = Gravity.CENTER
        }
        val listView = ListView(context).apply {
            divider = null
            chatAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_1, messages)
            adapter = chatAdapter
        }
        addView(title)
        addView(listView)
    }

    private fun createSendScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(50, 100, 50, 50)
        gravity = Gravity.CENTER_HORIZONTAL

        val msgInput = EditText(context).apply { hint = "מה על הלב שלך?" }
        val sendBtn = Button(context).apply {
            text = "שלח לרשת"
            setOnClickListener {
                val name = prefs.getString("username", "אנונימי")
                val text = msgInput.text.toString()
                if (text.isNotBlank()) {
                    val intent = Intent(context, GossipService::class.java).apply {
                        action = "SEND_MESSAGE"
                        putExtra("USER_NAME", name)
                        putExtra("MESSAGE_TEXT", text)
                    }
                    context.startService(intent)
                    msgInput.text.clear()
                    Toast.makeText(context, "נשלח להפצה!", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addView(TextView(context).apply { text = "כתיבת הודעה חדשה"; textSize = 18f })
        addView(msgInput)
        addView(sendBtn)
    }

    private fun createSettingsScreen() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(50, 100, 50, 50)
        
        val nameInput = EditText(context).apply { 
            hint = "הכנס שם שיופיע בשליחה"
            setText(prefs.getString("username", ""))
        }
        val saveBtn = Button(context).apply {
            text = "שמור הגדרות"
            setOnClickListener {
                prefs.edit().putString("username", nameInput.text.toString()).apply()
                Toast.makeText(context, "הגדרות נשמרו!", Toast.LENGTH_SHORT).show()
            }
        }
        addView(TextView(context).apply { text = "הגדרות פרופיל"; textSize = 18f })
        addView(nameInput)
        addView(saveBtn)
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
        android.app.AlertDialog.Builder(this)
            .setTitle("Kosher Tech")
            .setMessage("אנדרואיד 4 לא נתמך ברשת ה-Gossip.")
            .setPositiveButton("סגור") { _, _ -> finish() }.show()
    }
}
