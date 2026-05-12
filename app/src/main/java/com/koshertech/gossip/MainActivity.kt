package com.koshertech.gossip

import android.Manifest
import android.app.AlertDialog
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
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
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
    
    private lateinit var screens: List<View>
    private lateinit var privateTargetInput: EditText
    
    private lateinit var myName: String
    private lateinit var myMac: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("K", Context.MODE_PRIVATE)
        myName = prefs.getString("u", "User_${(10..99).random()}") ?: "User"
        myMac = prefs.getString("mac_id", null) ?: UUID.randomUUID().toString().substring(0, 8).uppercase().also { prefs.edit().putString("mac_id", it).apply() }

        val main = RelativeLayout(this).apply { setBackgroundColor(Color.parseColor("#E5DDD5")) }
        
        // יצירת 4 המסכים
        val publicScreen = createChatScreen("G")
        val privateScreen = createChatScreen("P")
        val outboxScreen = createOutboxScreen()
        val settingsScreen = createSettingsAndRadar()
        screens = listOf(publicScreen, privateScreen, outboxScreen, settingsScreen)

        // תפריט תחתון
        val nav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            setBackgroundColor(Color.WHITE)
            menu.add(0, 1, 0, "ציבורי").setIcon(android.R.drawable.ic_menu_sort_by_size)
            menu.add(0, 2, 1, "פרטי").setIcon(android.R.drawable.ic_dialog_email)
            menu.add(0, 3, 2, "שליחה").setIcon(android.R.drawable.ic_menu_send)
            menu.add(0, 4, 3, "הגדרות").setIcon(android.R.drawable.ic_menu_manage)
        }

        // מערכת גלילה (החלקה בין מסכים)
        val pager = ViewPager(this).apply {
            id = View.generateViewId()
            offscreenPageLimit = 4
            adapter = object : PagerAdapter() {
                override fun getCount() = screens.size
                override fun isViewFromObject(v: View, o: Any) = v === o
                override fun instantiateItem(container: ViewGroup, pos: Int): Any {
                    container.addView(screens[pos])
                    return screens[pos]
                }
                override fun destroyItem(container: ViewGroup, pos: Int, obj: Any) {
                    container.removeView(obj as View)
                }
            }
        }

        // סנכרון בין התפריט למסך המוחלק
        nav.setOnItemSelectedListener { item ->
            pager.currentItem = item.itemId - 1
            true
        }
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                nav.menu.getItem(position).isChecked = true
            }
        })

        val navLp = RelativeLayout.LayoutParams(-1, 180).apply { addRule(RelativeLayout.ALIGN_PARENT_BOTTOM) }
        val pagerLp = RelativeLayout.LayoutParams(-1, -1).apply { addRule(RelativeLayout.ABOVE, nav.id) }
        
        main.addView(pager, pagerLp)
        main.addView(nav, navLp)
        setContentView(main)

        registerReceivers()
        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.ACCESS_FINE_LOCATION) else arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        else startService(Intent(this, GossipService::class.java))
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        super.onRequestPermissionsResult(rc, p, g)
        if (g.isNotEmpty() && g.all { it == PackageManager.PERMISSION_GRANTED }) startService(Intent(this, GossipService::class.java))
    }

    private fun getAlias(mac: String, defaultName: String): String = prefs.getString("alias_$mac", defaultName) ?: defaultName

    private fun registerReceivers() {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.RECEIVER_NOT_EXPORTED else 0

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
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
        }, IntentFilter("NEW_MSG"), flags)

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val id = i?.getStringExtra("ID"); val status = i?.getStringExtra("STATUS") ?: ""
                allMessages.find { it.id == id }?.status = status
                updateLists()
            }
        }, IntentFilter("MSG_STATUS"), flags)

        ContextCompat.registerReceiver(this, object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val mac = i?.getStringExtra("MAC") ?: return
                if (!discoveredMacs.contains(mac)) {
                    discoveredMacs.add(mac)
                    radarAdapter.clear(); radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                    radarAdapter.notifyDataSetChanged()
                }
            }
        }, IntentFilter("DEVICE_FOUND"), flags)
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
                    if (!msg.isMine) addView(TextView(context).apply { text = msg.senderName; setTextColor(Color.parseColor("#075E54")); textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD) })
                    addView(TextView(context).apply { text = msg.text; setTextColor(Color.BLACK); textSize = 16f; maxWidth = 800 })
                    if (msg.isMine) {
                        val statusTxt = when(msg.status) { "SENT" -> "נשלח"; "PENDING" -> "ממתין..."; "FAILED" -> "נכשל"; else -> "" }
                        addView(TextView(context).apply { text = statusTxt; setTextColor(Color.GRAY); textSize = 10f; gravity = Gravity.END })
                    }
                }
                addView(bubble)
                
                // לחיצה על הודעה: אם שלי - אלץ שליחה. אם קיבלתי בפרטי - העתק שם לתיבת התשובה!
                setOnClickListener { 
                    if (msg.isMine) {
                        showForceSendDialog(msg) 
                    } else if (filterType == "P") {
                        privateTargetInput.setText(msg.senderName)
                        Toast.makeText(context, "משיב ל-${msg.senderName}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showForceSendDialog(msg: ChatMsg) {
        if (discoveredMacs.isEmpty()) {
            Toast.makeText(this, "הראדאר ריק. כנס להגדרות ובצע חיפוש מכשירים.", Toast.LENGTH_SHORT).show()
            return
        }
        val array = discoveredMacs.map { getAlias(it, it) }.toTypedArray()
        val macs = discoveredMacs.toList()
        
        AlertDialog.Builder(this).setTitle("שדר למכשיר:").setItems(array) { _, w ->
            val mac = macs[w]
            val payload = "${msg.type}|$myMac|$myName|${msg.target}|${msg.text}"
            msg.status = "PENDING"; updateLists()
            startService(Intent(this, GossipService::class.java).apply { action = "FORCE_SEND"; putExtra("MAC", mac); putExtra("PAYLOAD", payload); putExtra("MSG_ID", msg.id) })
            Toast.makeText(this, "דוחף ל-${array[w]}...", Toast.LENGTH_SHORT).show()
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
            
            val targetInp: EditText? = if (type == "P") EditText(context).apply { hint = "נמען"; layoutParams = LinearLayout.LayoutParams(0, -2, 0.4f).apply { setMargins(0,0,15,0) }; setBackgroundResource(android.R.drawable.edit_text) }.also { privateTargetInput = it } else null
            
            val msgInp = EditText(context).apply { 
                hint = "הודעה..."; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                background = GradientDrawable().apply { setStroke(1, Color.LTGRAY); cornerRadius = 40f; setColor(Color.parseColor("#F5F5F5")) }
                setPadding(40, 30, 40, 30)
            }
            
            val btn = Button(context).apply {
                text = "שלח"; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(Color.parseColor("#00897B")); cornerRadius = 40f }
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(15, 0, 0, 0) }
                
                setOnClickListener {
                    val m = msgInp.text.toString()
                    val target = targetInp?.text?.toString()?.takeIf { it.isNotBlank() } ?: if(type == "G") "ALL" else return@setOnClickListener
                    if (m.isNotBlank()) {
                        val msg = ChatMsg(UUID.randomUUID().toString(), myMac, myName, target, m, "PENDING", true, type)
                        allMessages.add(msg); updateLists(); msgInp.text.clear()
                        showForceSendDialog(msg) // מקפיץ מייד לאישור שידור!
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
            text = "הודעות יוצאות - לחץ על בועה לאילוץ שליחה מחדש"
            gravity = Gravity.CENTER; setPadding(0, 30, 0, 30)
            setBackgroundColor(Color.parseColor("#607D8B")); setTextColor(Color.WHITE); textSize = 14f
        })
        addView(ListView(context).apply {
            divider = null; outboxAdapter = MessageAdapter("OUTBOX"); adapter = outboxAdapter; setStackFromBottom(true)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        
        // תיבת שליחה חופשית גם מכאן (משלב ציבורי ופרטי)
        val globalSendArea = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(30,30,30,30); setBackgroundColor(Color.WHITE)
            val rg = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
            val rbG = RadioButton(context).apply { text = "לכולם"; id = View.generateViewId(); isChecked = true }
            val rbP = RadioButton(context).apply { text = "אישי"; id = View.generateViewId() }
            rg.addView(rbG); rg.addView(rbP)
            
            val targetE = EditText(context).apply { hint = "שם הנמען"; visibility = View.GONE }
            rbP.setOnCheckedChangeListener { _, c -> targetE.visibility = if (c) View.VISIBLE else View.GONE }
            
            val msgE = EditText(context).apply { hint = "כתוב הודעה..."; background = GradientDrawable().apply { setStroke(1, Color.LTGRAY); cornerRadius = 20f }; setPadding(30,20,30,20) }
            val btnSend = Button(context).apply {
                text = "שגר"; setBackgroundColor(Color.parseColor("#00897B")); setTextColor(Color.WHITE)
                setOnClickListener {
                    val m = msgE.text.toString()
                    val t = if (rbG.isChecked) "ALL" else targetE.text.toString()
                    val type = if (rbG.isChecked) "G" else "P"
                    if (m.isNotBlank() && t.isNotBlank()) {
                        val msg = ChatMsg(UUID.randomUUID().toString(), myMac, myName, t, m, "PENDING", true, type)
                        allMessages.add(msg); updateLists(); msgE.text.clear()
                        showForceSendDialog(msg)
                    }
                }
            }
            addView(rg); addView(targetE); addView(msgE); addView(btnSend.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0,10,0,0) } })
        }
        addView(globalSendArea)
    }

    private fun createSettingsAndRadar(): ScrollView = ScrollView(this).apply {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 50, 50, 50) }
        
        container.addView(TextView(context).apply { text = "הגדרות"; textSize = 18f; setTextColor(Color.parseColor("#075E54")) })
        
        val nameInp = EditText(context).apply { setText(myName); hint = "השם שלי ברשת" }
        container.addView(nameInp)
        container.addView(Button(context).apply {
            text = "שמור שם"; setBackgroundColor(Color.LTGRAY)
            setOnClickListener { myName = nameInp.text.toString(); prefs.edit().putString("u", myName).apply(); Toast.makeText(context, "שם עודכן", Toast.LENGTH_SHORT).show() }
        })

        val soundCheck = CheckBox(context).apply { 
            text = "הפעל התראות קוליות"
            isChecked = prefs.getBoolean("sound_enabled", true)
            setOnCheckedChangeListener { _, isChecked -> prefs.edit().putBoolean("sound_enabled", isChecked).apply() }
        }
        container.addView(soundCheck.apply { setPadding(0,30,0,0) })

        container.addView(TextView(context).apply { text = "ראדאר וחיבורים"; textSize = 18f; setTextColor(Color.parseColor("#075E54")); setPadding(0,60,0,10) })
        container.addView(TextView(context).apply { text = "לחץ על מכשיר ברשימה כדי לתת לו שם מותאם."; textSize = 12f; setTextColor(Color.GRAY); setPadding(0,0,0,20) })
        
        container.addView(Button(context).apply {
            text = "הפעל סריקת מכשירים"; setBackgroundColor(Color.parseColor("#00897B")); setTextColor(Color.WHITE)
            setOnClickListener { 
                discoveredMacs.clear(); radarAdapter.clear(); radarAdapter.notifyDataSetChanged()
                startService(Intent(context, GossipService::class.java).apply { action = "START_SCAN" })
                Toast.makeText(context, "סורק... הרשימה תתעדכן", Toast.LENGTH_SHORT).show()
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
                AlertDialog.Builder(context).setTitle("שמור שם איש קשר").setView(input).setPositiveButton("שמור") { _, _ -> 
                    prefs.edit().putString("alias_$mac", input.text.toString()).apply()
                    radarAdapter.clear(); radarAdapter.addAll(discoveredMacs.map { m -> "מכשיר זמין: ${getAlias(m, m)}" })
                    updateLists(); Toast.makeText(context, "נשמר!", Toast.LENGTH_SHORT).show()
                }.show()
            }
        }
        container.addView(radarList)
        addView(container)
    }
}
