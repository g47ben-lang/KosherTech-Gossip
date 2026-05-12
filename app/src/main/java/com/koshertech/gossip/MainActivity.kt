package com.koshertech.gossip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val messagesList = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String> // התיקון כאן: var במקום val

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // בדיקת תאימות לאנדרואיד 4 (Lollipop הוא API 21)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Kosher Tech")
                .setMessage("המכשיר שלך מריץ גרסת אנדרואיד ישנה (אנדרואיד 4). טכנולוגיית הרשת דורשת אנדרואיד 5 ומעלה. לצערי, לא תוכל לשלוח או לקבל הודעות.")
                .setPositiveButton("הבנתי") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return 
        }

        // יצירת ממשק בסיסי
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val inputField = EditText(this).apply {
            hint = "הקלד הודעה קצרה (עד 140 תווים)..."
            filters = arrayOf(android.text.InputFilter.LengthFilter(140))
        }

        val sendButton = Button(this).apply {
            text = "שדר לכולם"
            setOnClickListener {
                val text = inputField.text.toString()
                if (text.isNotBlank()) {
                    sendMessageToService(text)
                    inputField.text.clear()
                }
            }
        }

        val listView = ListView(this)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, messagesList)
        listView.adapter = adapter

        layout.addView(inputField)
        layout.addView(sendButton)
        layout.addView(listView)
        setContentView(layout)

        checkPermissionsAndStartService()
    }

    private fun checkPermissionsAndStartService() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val allGranted = permissions.all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }

        if (allGranted) {
            startGossipService()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
    }

    private fun startGossipService() {
        val intent = Intent(this, GossipService::class.java)
        startService(intent)
    }

    private fun sendMessageToService(text: String) {
        messagesList.add(0, "אני: $text") // מוסיף להתחלה כדי שיראו מיד
        adapter.notifyDataSetChanged()
        
        val intent = Intent(this, GossipService::class.java).apply {
            action = "SEND_MESSAGE"
            putExtra("MESSAGE_TEXT", text)
        }
        startService(intent)
    }
}
